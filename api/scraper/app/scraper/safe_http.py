"""Implementa las responsabilidades del módulo `safe_http`.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from urllib.parse import parse_qsl, urljoin, urlparse, urlunparse

import httpx

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
"""Constante que define `SENSITIVE_QUERY_KEYS`.
"""


class SafeHttpError(Exception):
    """Representa un error relacionado con `SafeHttp`.
    """

    def __init__(self, code: str, *, transient: bool = False) -> None:
        """Inicializa una instancia de `SafeHttpError`.

        Args:
            code (str): Valor de `code` utilizado por la operación.
            transient (bool): Valor de `transient` utilizado por la operación.
        """
        super().__init__(code)
        self.code = code
        """Estado de instancia asociado a `code`.
        """
        self.transient = transient
        """Estado de instancia asociado a `transient`.
        """


@dataclass(frozen=True)
class SafeHttpResponse:
    """Representa una respuesta de `SafeHttp`.
    """
    final_url: str
    """Atributo de clase `final_url` de `SafeHttpResponse`.
    """
    status_code: int
    """Atributo de clase `status_code` de `SafeHttpResponse`.
    """
    content_type: str | None
    """Atributo de clase `content_type` de `SafeHttpResponse`.
    """
    content: bytes
    """Atributo de clase `content` de `SafeHttpResponse`.
    """
    headers: httpx.Headers
    """Atributo de clase `headers` de `SafeHttpResponse`.
    """


def validate_public_https_syntax(url: str) -> str:
    """Valida la operación `public_https_syntax`.

    Args:
        url (str): URL del recurso que debe procesarse.

    Returns:
        str: Resultado producido por la operación.

    Throws:
        SafeHttpError: Si no puede completarse la operación bajo las condiciones requeridas.
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
    """Valida la operación `public_https_url`.

    Args:
        url (str): URL del recurso que debe procesarse.

    Returns:
        str: Resultado producido por la operación.

    Throws:
        SafeHttpError: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    normalized = validate_public_https_syntax(url)
    hostname = urlparse(normalized).hostname
    if not await domain_has_public_dns(hostname):
        raise SafeHttpError("dns_not_public")
    return normalized


def has_sensitive_query(url: str) -> bool:
    """Indica si existe la operación `sensitive_query`.

    Args:
        url (str): URL del recurso que debe procesarse.

    Returns:
        bool: Indica si se cumple la condición evaluada.
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
    """Recupera la operación `public_resource`.

    Args:
        url (str): URL del recurso que debe procesarse.
        timeout (float): Tiempo máximo permitido para completar la operación.
        max_redirects (int): Valor de `max_redirects` utilizado por la operación.
        max_bytes (int): Valor de `max_bytes` utilizado por la operación.
        accept (str): Valor de `accept` utilizado por la operación.

    Returns:
        SafeHttpResponse: Resultado de `fetch_public_resource`.

    Throws:
        SafeHttpError: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    current_url = await validate_public_https_url(url)
    async with httpx.AsyncClient(
        timeout=timeout,
        follow_redirects=False,
        headers={
            "Accept": accept,
            "User-Agent": "BatchDownloaderScraper/0.1",
        },
    ) as client:
        for _redirect in range(max_redirects + 1):
            try:
                async with client.stream("GET", current_url) as response:
                    if response.is_redirect:
                        location = response.headers.get("location")
                        if not location:
                            raise SafeHttpError("redirect_without_location")
                        try:
                            current_url = urljoin(current_url, location)
                        except ValueError as exc:
                            raise SafeHttpError("redirect_invalid_url") from exc
                        current_url = await validate_public_https_url(current_url)
                        continue
                    if response.status_code >= 400:
                        transient = response.status_code in {408, 425, 429}
                        transient = transient or response.status_code >= 500
                        raise SafeHttpError(
                            f"http_{response.status_code}",
                            transient=transient,
                        )
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
                    content_type = (
                        response.headers.get("content-type", "").split(";", 1)[0].strip().lower()
                        or None
                    )
                    return SafeHttpResponse(
                        final_url=str(response.url),
                        status_code=response.status_code,
                        content_type=content_type,
                        content=bytes(content),
                        headers=response.headers,
                    )
            except SafeHttpError:
                raise
            except httpx.TimeoutException as exc:
                raise SafeHttpError("timeout", transient=True) from exc
            except httpx.RequestError as exc:
                raise SafeHttpError("network_error", transient=True) from exc
    raise SafeHttpError("too_many_redirects")
