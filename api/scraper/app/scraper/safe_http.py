"""Proporciona la política HTTPS y detección de consultas sensibles compartidas por inspecciones
y descubrimientos.
"""
from __future__ import annotations

import re
from urllib.parse import parse_qsl, urljoin, urlparse, urlunparse

import httpx

from app.scraper.http import (
    FetchRequest,
    HttpxPublicResourceFetcher,
    SafeHttpError,
    SafeHttpResponse,
)
from app.scraper.validator import domain_has_public_dns

SENSITIVE_QUERY_KEYS = {
    "access_token",
    "api_key",
    "apikey",
    "auth",
    "authorization",
    "key",
    "password",
    "sig",
    "signature",
    "token",
}



def validate_public_https_syntax(url: str) -> str:
    """Recorta espacios, exige HTTPS y host, prohíbe credenciales y controles y limita la URL a
    2048 caracteres.
    Normaliza esquema y elimina fragmento; admite puertos explícitos entre uno y 65535.

    Args:
        url: URL completa que se solicita o valida antes de abrir la conexión.

    Returns:
        URL normalizada sin fragmento.

    Raises:
        app.scraper.http.models.SafeHttpError: Si la sintaxis, esquema, host, credenciales o
            puerto incumplen estas restricciones.
    """
    value = url.strip()
    if not value or len(value) > 2048 or any(ord(character) < 32 for character in value):
        raise SafeHttpError("invalid_url")
    try:
        parsed = urlparse(value)
        port = parsed.port
    except ValueError as exc:
        raise SafeHttpError("invalid_url") from exc
    if parsed.scheme.lower() != "https":
        raise SafeHttpError("https_required")
    if not parsed.hostname:
        raise SafeHttpError("missing_domain")
    if parsed.username is not None or parsed.password is not None:
        raise SafeHttpError("url_credentials_forbidden")
    if port is not None and not 1 <= port <= 65535:
        raise SafeHttpError("invalid_port")
    return urlunparse(parsed._replace(scheme="https", fragment=""))


async def validate_public_https_url(url: str) -> str:
    """Valida la sintaxis y exige que el dominio supere la política DNS antes de abrir una
    conexión.

    Args:
        url: URL completa que se solicita o valida antes de abrir la conexión.

    Returns:
        URL HTTPS normalizada.

    Raises:
        app.scraper.http.models.SafeHttpError: Por sintaxis inválida o dns_not_public si la
            resolución no es admitida.
    """
    normalized = validate_public_https_syntax(url)
    hostname = urlparse(normalized).hostname
    if not await domain_has_public_dns(hostname):
        raise SafeHttpError("dns_not_public")
    return normalized


async def probe_public_resource_size(
    url: str, *, timeout: float, max_redirects: int
) -> int | None:
    """Consulta solo HEAD y valida HTTPS, DNS y credenciales antes de cada salto.

    No lee el cuerpo ni recurre a GET cuando el proveedor no anuncia un tamaño válido.
    """
    async with httpx.AsyncClient(
        timeout=timeout,
        follow_redirects=False,
        headers={"Accept-Encoding": "identity", "User-Agent": "BatchDownloaderScraper/0.1"},
    ) as client:
        for _redirect in range(max_redirects + 1):
            url = await validate_public_https_url(url)
            async with client.stream("HEAD", url) as response:
                if response.is_redirect:
                    location = response.headers.get("location")
                    if not location:
                        return None
                    url = urljoin(url, location)
                    continue
                if response.status_code != 200:
                    return None
                size = response.headers.get("content-length", "")
                if not size.isascii() or not size.isdecimal() or len(size) > 19:
                    return None
                value = int(size)
                return value if 0 < value <= 2**63 - 1 else None
    return None


def has_sensitive_query(url: str) -> bool:
    """Normaliza nombres de parámetros y detecta claves relacionadas con credenciales, firmas,
    contraseñas y tokens.

    Args:
        url: URL completa que se solicita o valida antes de abrir la conexión.

    Returns:
        True si la consulta puede contener secretos o la URL no puede interpretarse.
    """
    try:
        query = parse_qsl(urlparse(url).query, keep_blank_values=True)
    except ValueError:
        return True
    for key, _value in query:
        normalized = re.sub(r"[^a-z0-9]+", "_", key.casefold()).strip("_")
        if normalized in SENSITIVE_QUERY_KEYS:
            return True
        if any(
            marker in normalized
            for marker in (
                "access_key",
                "accesskey",
                "api_key",
                "apikey",
                "authorization",
                "credential",
                "password",
                "secret",
                "signature",
                "token",
            )
        ):
            return True
    return False


async def fetch_public_resource(
    url: str,
    *,
    timeout: float,
    max_redirects: int,
    max_bytes: int,
    accept: str,
) -> SafeHttpResponse:
    """Compone la consulta acotada con validación HTTPS y DNS en cada destino inicial o
    redirigido.

    Args:
        url: URL completa que se solicita o valida antes de abrir la conexión.
        timeout: Timeout HTTPX de la consulta, en segundos.
        max_redirects: Número máximo de redirecciones; la primera petición no cuenta como
            salto.
        max_bytes: Máximo de bytes del cuerpo aceptado en memoria.
        accept: Valor de Accept que describe los tipos de recurso solicitados.

    Returns:
        recurso completo dentro de los límites.

    Raises:
        app.scraper.http.models.SafeHttpError: Si la consulta infringe una política o falla el
            transporte.
    """
    fetcher = HttpxPublicResourceFetcher(validate_public_https_url)
    return await fetcher.fetch(
        FetchRequest(
            url=url,
            timeout=timeout,
            max_redirects=max_redirects,
            max_bytes=max_bytes,
            accept=accept,
        )
    )
