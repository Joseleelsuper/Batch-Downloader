"""Fija cada conexión externa a direcciones públicas sin cambiar su origen TLS ni su Host."""
from __future__ import annotations

import asyncio
import ipaddress
import socket

import httpcore
import httpx


async def public_addresses(host: str, port: int) -> list[str]:
    """Devuelve IPs numéricas públicas; nunca una autorización DNS reutilizable como booleano."""
    entries = await asyncio.get_running_loop().getaddrinfo(host, port, type=socket.SOCK_STREAM)
    addresses = list(dict.fromkeys(str(entry[4][0]) for entry in entries))
    if not addresses or not all(
        ipaddress.ip_address(ip).is_global
        and not ipaddress.ip_address(ip).is_multicast
        and not ipaddress.ip_address(ip).is_reserved
        for ip in addresses
    ):
        raise httpcore.ConnectError("dns_not_public")
    return addresses


class PublicNetworkBackend(httpcore.AsyncNetworkBackend):
    """Resuelve una vez por conexión y entrega únicamente IPs ya autorizadas al socket."""

    def __init__(self, wrapped: httpcore.AsyncNetworkBackend) -> None:
        self._wrapped = wrapped

    async def connect_tcp(self, host, port, timeout=None, local_address=None, socket_options=None):
        try:
            async with asyncio.timeout(timeout):
                addresses = await public_addresses(host, port)
                failure = None
                for address in addresses:
                    try:
                        return await self._wrapped.connect_tcp(
                            address, port, timeout=timeout, local_address=local_address,
                            socket_options=socket_options,
                        )
                    except (httpcore.ConnectError, httpcore.ConnectTimeout) as exc:
                        failure = exc
                raise failure
        except TimeoutError as exc:
            raise httpcore.ConnectTimeout("public_connection_timeout") from exc
        except OSError as exc:
            raise httpcore.ConnectError("public_dns_failed") from exc

    async def connect_unix_socket(self, path, timeout=None, socket_options=None):
        raise httpcore.ConnectError("unix_socket_forbidden")

    async def sleep(self, seconds):
        await self._wrapped.sleep(seconds)


class PublicAsyncHTTPTransport(httpx.AsyncHTTPTransport):
    """Conserva HTTPX y su pool por origen; sustituye solo la apertura TCP de HTTPcore."""

    def __init__(self) -> None:
        super().__init__(trust_env=False)
        # Punto de integración de HTTPX 0.28.1: no reescribir la URL a una IP, pues
        # el pool, SNI y la comprobación del certificado deben conservar el hostname.
        self._pool._network_backend = PublicNetworkBackend(self._pool._network_backend)

    async def handle_async_request(self, request):
        if request.url.scheme not in {"http", "https"} or request.url.userinfo:
            raise httpx.ConnectError("invalid_public_url", request=request)
        return await super().handle_async_request(request)
