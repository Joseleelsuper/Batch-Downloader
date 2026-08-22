"""Implementaciones y wrappers del pipeline HTTP externo."""
from __future__ import annotations

from contextlib import AbstractAsyncContextManager, asynccontextmanager
from urllib.parse import urljoin

import httpx

from app.scraper.http.models import FetchRequest, SafeHttpError, SafeHttpResponse
from app.scraper.http.ports import SingleHopExchange, UrlValidator


class HttpxSingleHopExchange:
    """Transporte base que abre un único GET mediante HTTPX."""

    def __init__(self, client: httpx.AsyncClient) -> None:
        self._client = client

    def stream(self, url: str) -> AbstractAsyncContextManager[httpx.Response]:
        return self._client.stream("GET", url)


class TransportErrorMappingExchange:
    """Convierte excepciones de HTTPX al contrato estable del scraper."""

    def __init__(self, wrapped: SingleHopExchange) -> None:
        self._wrapped = wrapped

    @asynccontextmanager
    async def stream(self, url: str):
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
    """Valida HTTPS, DNS público y SSRF inmediatamente antes de cada salto."""

    def __init__(self, wrapped: SingleHopExchange, validator: UrlValidator) -> None:
        self._wrapped = wrapped
        self._validator = validator

    @asynccontextmanager
    async def stream(self, url: str):
        safe_url = await self._validator(url)
        async with self._wrapped.stream(safe_url) as response:
            yield response


class BoundedResponseReader:
    """Materializa el cuerpo sin superar el máximo permitido."""

    async def read(self, response: httpx.Response, max_bytes: int) -> bytes:
        declared_size = response.headers.get("content-length")
        if declared_size and declared_size.isdigit() and int(declared_size) > max_bytes:
            raise SafeHttpError("content_too_large")
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
    """Resuelve redirecciones de forma explícita y conserva el límite de saltos."""

    def __init__(
        self,
        exchange: SingleHopExchange,
        reader: BoundedResponseReader | None = None,
    ) -> None:
        self._exchange = exchange
        self._reader = reader or BoundedResponseReader()

    async def fetch(self, request: FetchRequest) -> SafeHttpResponse:
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
    """Fábrica por petición de la cadena segura sobre un cliente HTTPX reutilizado por saltos."""

    def __init__(self, validator: UrlValidator) -> None:
        self._validator = validator

    async def fetch(self, request: FetchRequest) -> SafeHttpResponse:
        async with httpx.AsyncClient(
            timeout=request.timeout,
            follow_redirects=False,
            headers={
                "Accept": request.accept,
                "User-Agent": "BatchDownloaderScraper/0.1",
            },
        ) as client:
            exchange: SingleHopExchange = HttpxSingleHopExchange(client)
            exchange = TransportErrorMappingExchange(exchange)
            exchange = PublicHttpsExchange(exchange, self._validator)
            return await RedirectFollowingFetcher(exchange).fetch(request)
