"""Compone validación por salto, clasificación de fallos, seguimiento de redirecciones y lectura
acotada de recursos públicos.
"""
from __future__ import annotations

import asyncio
from contextlib import AbstractAsyncContextManager, asynccontextmanager
from urllib.parse import urljoin

import httpx

from app.scraper.http.models import FetchRequest, SafeHttpError, SafeHttpResponse
from app.scraper.http.ports import SingleHopExchange, UrlValidator
from app.scraper.http.public_transport import PublicAsyncHTTPTransport


class HttpxSingleHopExchange:
    """Adapta el streaming GET de un cliente HTTPX al contrato de intercambio de un solo destino."""

    def __init__(self, client: httpx.AsyncClient) -> None:
        """Conserva el cliente externo sin abrir ni cerrar su ciclo de vida.

        Args:
            client: Cliente HTTPX cedido por el llamador; su propietario controla el cierre.
        """
        self._client = client

    def stream(self, url: str) -> AbstractAsyncContextManager[httpx.Response]:
        """Crea el contexto de un GET en streaming con el cliente configurado por el llamador.

        Args:
            url: URL completa que se solicita o valida antes de abrir la conexión.

        Returns:
            contexto HTTPX; el cliente debe tener seguimiento automático de redirecciones
                desactivado.
        """
        return self._client.stream("GET", url, follow_redirects=False)


class TransportErrorMappingExchange:
    """Traduce excepciones de HTTPX a códigos reintentables sin modificar rechazos de política ya
    clasificados.
    """

    def __init__(self, wrapped: SingleHopExchange) -> None:
        """Envuelve el intercambio al que se aplicará la clasificación de fallos de transporte.

        Args:
            wrapped: Intercambio de un solo salto al que se añade la política.
        """
        self._wrapped = wrapped

    @asynccontextmanager
    async def stream(self, url: str):
        """Mantiene la respuesta abierta durante el uso del contexto y traduce timeout o fallos
        de petición de apertura, lectura o cierre.

        Args:
            url: URL completa que se solicita o valida antes de abrir la conexión.

        Yields:
            respuesta del intercambio envuelto.

        Raises:
            app.scraper.http.models.SafeHttpError: timeout o network_error recuperables, o el
                rechazo de política original.
        """
        try:
            async with self._wrapped.stream(url) as response:
                yield response
        except SafeHttpError:
            raise
        except httpx.TimeoutException as exc:
            raise SafeHttpError("timeout", transient=True) from exc
        except httpx.RequestError as exc:
            raise SafeHttpError("network_error", transient=True) from exc


class PublicHttpsExchange:
    """Comprueba la URL antes de cada salto para impedir que una redirección eluda la política de
    destino público.
    """

    def __init__(self, wrapped: SingleHopExchange, validator: UrlValidator) -> None:
        """Conecta el intercambio con la validación asíncrona de URL del despliegue.

        Args:
            wrapped: Intercambio de un solo salto al que se añade la política.
            validator: Validación asíncrona que devuelve la URL admitida o propaga su rechazo.
        """
        self._wrapped = wrapped
        self._validator = validator

    @asynccontextmanager
    async def stream(self, url: str):
        """Valida y normaliza la URL antes de delegar la apertura del intercambio.

        Args:
            url: URL completa que se solicita o valida antes de abrir la conexión.

        Yields:
            respuesta del destino admitido.

        Raises:
            app.scraper.http.models.SafeHttpError: Si la URL o su DNS incumplen la política
                del validador.
        """
        safe_url = await self._validator(url)
        async with self._wrapped.stream(safe_url) as response:
            yield response


class BoundedResponseReader:
    """Limita memoria de cuerpos HTTP aunque el servidor omita o falsee Content-Length."""

    async def read(self, response: httpx.Response, max_bytes: int) -> bytes:
        """Rechaza una longitud declarada excesiva y lee el cuerpo crudo por fragmentos hasta
        detectar que supera el máximo.

        Args:
            response: Respuesta HTTP abierta cuyo cuerpo o metadatos se leen.
            max_bytes: Máximo de bytes del cuerpo aceptado en memoria.

        Returns:
            bytes recibidos dentro del límite.

        Raises:
            app.scraper.http.models.SafeHttpError: content_too_large si la cabecera o los
                bytes observados superan el máximo.
        """
        declared_size = response.headers.get("content-length")
        if response.headers.get("content-encoding", "identity").lower() != "identity":
            raise SafeHttpError("encoded_response_not_supported")
        if declared_size and declared_size.isdigit() and int(declared_size) > max_bytes:
            raise SafeHttpError("content_too_large")
        if response.is_stream_consumed:
            if len(response.content) > max_bytes:
                raise SafeHttpError("content_too_large")
            return response.content
        content = bytearray()
        async for chunk in response.aiter_raw():
            remaining = max_bytes + 1 - len(content)
            if remaining <= 0:
                break
            content.extend(chunk[:remaining])
            if len(content) > max_bytes:
                raise SafeHttpError("content_too_large")
        return bytes(content)


class RedirectFollowingFetcher:
    """Sigue redirecciones explícitas con validación por salto y rechaza resultados HTTP de error
    antes de leer el cuerpo.
    """

    def __init__(
        self,
        exchange: SingleHopExchange,
        reader: BoundedResponseReader | None = None,
    ) -> None:
        """Conecta el intercambio con el lector acotado que procesará la respuesta final.

        Args:
            exchange: Intercambio por salto con las políticas de URL y error ya compuestas.
            reader: Lector acotado opcional; None crea el lector predeterminado.
        """
        self._exchange = exchange
        self._reader = reader or BoundedResponseReader()

    async def fetch(self, request: FetchRequest) -> SafeHttpResponse:
        """Resuelve Location relativo, limita saltos y clasifica HTTP 408/425/429 o desde 500
        como transitorios.
        En éxito lee el cuerpo acotado y normaliza el MIME sin parámetros.

        Args:
            request: URL, límites de tiempo y bytes, saltos máximos y cabecera Accept de la
                consulta.

        Returns:
            respuesta final cerrada con contenido y cabeceras.

        Raises:
            app.scraper.http.models.SafeHttpError: Si falta Location, la redirección es
                inválida, se agotan saltos, falla HTTP o se supera el límite de bytes.
        """
        try:
            async with asyncio.timeout(request.timeout):
                return await self._fetch(request)
        except TimeoutError as exc:
            raise SafeHttpError("timeout", transient=True) from exc

    async def _fetch(self, request: FetchRequest) -> SafeHttpResponse:
        current_url = request.url
        for _redirect in range(request.max_redirects + 1):
            async with self._exchange.stream(current_url) as response:
                if response.is_redirect:
                    location = response.headers.get("location")
                    if not location:
                        raise SafeHttpError("redirect_without_location")
                    try:
                        current_url = urljoin(str(response.request.url), location)
                    except ValueError as exc:
                        raise SafeHttpError("redirect_invalid_url") from exc
                    continue
                if response.status_code >= 400:
                    transient = response.status_code in {408, 425, 429}
                    transient = transient or response.status_code >= 500
                    raise SafeHttpError(
                        f"http_{response.status_code}",
                        transient=transient,
                    )
                content = await self._reader.read(response, request.max_bytes)
                content_type = (
                    response.headers.get("content-type", "").split(";", 1)[0].strip().lower()
                    or None
                )
                return SafeHttpResponse(
                    final_url=str(response.url),
                    status_code=response.status_code,
                    content_type=content_type,
                    content=content,
                    headers=response.headers,
                )
        raise SafeHttpError("too_many_redirects")


class HttpxPublicResourceFetcher:
    """Crea un cliente por consulta y compone políticas de errores, URL, redirecciones y tamaño
    antes de acceder al recurso.

    See Also:
        app.scraper.safe_http.validate_public_https_url: Validador habitual de sintaxis y DNS.
    """

    def __init__(self, validator: UrlValidator) -> None:
        """Conserva el validador de destino que debe aplicarse en cada salto.

        Args:
            validator: Validación asíncrona que devuelve la URL admitida o propaga su rechazo.
        """
        self._validator = validator

    async def fetch(self, request: FetchRequest) -> SafeHttpResponse:
        """Abre un cliente con timeout, Accept y User-Agent del scraper, desactiva redirecciones
        automáticas y cierra el cliente al terminar.

        Args:
            request: URL, límites de tiempo y bytes, saltos máximos y cabecera Accept de la
                consulta.

        Returns:
            respuesta acotada del recurso solicitado.

        Raises:
            app.scraper.http.models.SafeHttpError: Si falla el transporte o cualquiera de las
                políticas compuestas.
        """
        async with httpx.AsyncClient(
            timeout=request.timeout,
            follow_redirects=False,
            transport=PublicAsyncHTTPTransport(),
            trust_env=False,
            headers={
                "Accept": request.accept,
                "Accept-Encoding": "identity",
                "User-Agent": "BatchDownloaderScraper/0.1",
            },
        ) as client:
            exchange: SingleHopExchange = HttpxSingleHopExchange(client)
            exchange = TransportErrorMappingExchange(exchange)
            exchange = PublicHttpsExchange(exchange, self._validator)
            return await RedirectFollowingFetcher(exchange).fetch(request)


async def fetch_bounded_response(
    client: httpx.AsyncClient, url: str, *, timeout: float, max_bytes: int,
    max_redirects: int = 5, headers=None, params=None, method: str = "GET", content=None,
    follow_redirects: bool = True,
) -> httpx.Response:
    """Lee metadatos completos bajo un único deadline, sin buffers ni redirects implícitos.

    El cliente de producción debe utilizar PublicAsyncHTTPTransport; los clientes inyectados
    conservan su transporte para pruebas. Cada salto vuelve a pasar por ese transporte.
    """
    current = str(httpx.URL(url).copy_merge_params(params)) if params else url
    request_headers = {**(headers or {}), "Accept-Encoding": "identity"}
    try:
        async with asyncio.timeout(timeout):
            for _ in range(max_redirects + 1):
                parsed = httpx.URL(current)
                if parsed.scheme not in {"http", "https"} or parsed.userinfo:
                    raise SafeHttpError("invalid_public_url")
                async with client.stream(
                    method, current, headers=request_headers, content=content,
                    follow_redirects=False,
                ) as response:
                    if response.is_redirect:
                        if not follow_redirects:
                            return httpx.Response(
                                response.status_code, headers=response.headers,
                                content=b"", request=response.request,
                            )
                        location = response.headers.get("location")
                        if not location:
                            raise SafeHttpError("redirect_without_location")
                        redirected = urljoin(str(response.url), location)
                        if httpx.URL(redirected).netloc != httpx.URL(current).netloc:
                            request_headers = {
                                key: value for key, value in request_headers.items()
                                if key.lower() not in {"authorization", "cookie", "host"}
                            }
                        if response.status_code == 303 or (
                            response.status_code in {301, 302} and method == "POST"
                        ):
                            method, content = "GET", None
                        current = redirected
                        continue
                    body = await BoundedResponseReader().read(response, max_bytes)
                    return httpx.Response(
                        response.status_code, headers=response.headers, content=body,
                        request=response.request,
                    )
            raise SafeHttpError("too_many_redirects")
    except TimeoutError as exc:
        raise SafeHttpError("timeout", transient=True) from exc
