"""Implementa las responsabilidades del módulo `safe_http`.
"""
from __future__ import annotations

import re
from urllib.parse import parse_qsl, urlparse, urlunparse

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
"""Constante que define `SENSITIVE_QUERY_KEYS`.
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
