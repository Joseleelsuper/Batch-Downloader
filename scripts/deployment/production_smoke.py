"""Comprueba HTTPS, salud web, locales y el proxy de descargas de producción."""

from __future__ import annotations

import argparse
import json
import socket
import ssl
import sys
from collections.abc import Sequence
from datetime import datetime, timezone
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

DEFAULT_URLS = (
    "https://batchdownloader.dev/healthz",
    "https://batchdownloader.dev/api/v1/locales/es",
    "https://downloads.batchdownloader.dev/healthz",
)


class SmokeError(RuntimeError):
    """Indica que una comprobación externa no cumple el contrato de producción."""


def certificate_days_remaining(hostname: str, port: int, timeout: float) -> float:
    """Devuelve los días de validez restantes del certificado presentado."""
    context = ssl.create_default_context()
    with socket.create_connection((hostname, port), timeout=timeout) as connection:
        with context.wrap_socket(connection, server_hostname=hostname) as tls:
            certificate = tls.getpeercert()
    expires = certificate.get("notAfter")
    if not isinstance(expires, str):
        raise SmokeError(f"{hostname}: el certificado no incluye notAfter")
    expiry = datetime.fromtimestamp(ssl.cert_time_to_seconds(expires), timezone.utc)
    return (expiry - datetime.now(timezone.utc)).total_seconds() / 86_400


def check_url(url: str, timeout: float) -> None:
    """Exige una respuesta JSON correcta para el endpoint indicado."""
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise SmokeError(f"Solo se admiten endpoints HTTPS: {url}")
    request = Request(
        url,
        headers={"Accept": "application/json", "User-Agent": "batch-downloader-smoke/1"},
    )
    with urlopen(request, timeout=timeout) as response:  # noqa: S310 - URL fija/validada.
        status = response.status
        body = response.read(262_144)
    if not 200 <= status < 300:
        raise SmokeError(f"{url}: HTTP {status}")
    try:
        payload = json.loads(body)
    except json.JSONDecodeError as exception:
        raise SmokeError(f"{url}: la respuesta no es JSON") from exception
    if parsed.path == "/healthz":
        if not isinstance(payload, dict) or payload.get("status") != "ok":
            raise SmokeError(f"{url}: healthcheck sin estado ok")


def run_smoke(
    urls: Sequence[str] = DEFAULT_URLS,
    *,
    timeout: float = 15,
    minimum_certificate_days: float = 14,
) -> None:
    """Ejecuta todas las pruebas y lanza ``SmokeError`` ante el primer fallo."""
    checked_hosts: set[tuple[str, int]] = set()
    for url in urls:
        parsed = urlsplit(url)
        if parsed.scheme != "https" or not parsed.hostname:
            raise SmokeError(f"Solo se admiten endpoints HTTPS: {url}")
        host = (parsed.hostname, parsed.port or 443)
        if host not in checked_hosts:
            days = certificate_days_remaining(*host, timeout)
            if days < minimum_certificate_days:
                raise SmokeError(
                    f"{host[0]}: el certificado caduca en {days:.1f} días"
                )
            checked_hosts.add(host)
        check_url(url, timeout)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", action="append", dest="urls")
    parser.add_argument("--timeout", type=float, default=15)
    parser.add_argument("--minimum-certificate-days", type=float, default=14)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = build_parser().parse_args(argv)
    try:
        run_smoke(
            tuple(arguments.urls or DEFAULT_URLS),
            timeout=arguments.timeout,
            minimum_certificate_days=arguments.minimum_certificate_days,
        )
    except (OSError, SmokeError) as exception:
        print(f"ERROR: {exception}", file=sys.stderr)
        return 1
    print("Producción responde por HTTPS, locales y descargas.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
