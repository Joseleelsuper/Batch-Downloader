"""Proxy efímero que aplica la misma autorización de sockets al navegador del scraper."""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager

import httpcore
import httpx

from app.scraper.http.public_transport import public_addresses


async def _copy(source, destination):
    while block := await source.read(65536):
        destination.write(block)
        await destination.drain()


async def _open_public(host, port):
    addresses = await public_addresses(host, port)
    failure = None
    for address in addresses:
        try:
            return await asyncio.open_connection(address, port)
        except OSError as exc:
            failure = exc
    raise failure


async def _close_connections(connections):
    pending = list(connections)
    for task in pending:
        task.cancel()
    if pending:
        await asyncio.gather(*pending, return_exceptions=True)


@asynccontextmanager
async def public_browser_proxy(timeout: float):
    """Mantiene TLS en Chromium y autoriza cada túnel, incluidos redirects y WebSockets.

    Solo escucha en loopback, limita cabeceras y conexiones y cierra todos los túneles al salir.
    """
    connections: set[asyncio.Task] = set()

    async def handle(reader, writer):
        task = asyncio.current_task()
        upstream = None
        pipes = []
        if len(connections) >= 64:
            writer.close()
            return
        connections.add(task)
        try:
            async with asyncio.timeout(timeout):
                header = await reader.readuntil(b"\r\n\r\n")
                first, *headers = header[:-4].split(b"\r\n")
                method, target, version = first.decode("ascii").split(" ")
                url = httpx.URL("https://" + target if method == "CONNECT" else target)
                if url.userinfo or not url.host or url.scheme not in {"http", "https"}:
                    raise ValueError("invalid_proxy_destination")
                port = url.port or (443 if url.scheme == "https" else 80)
                remote, upstream = await _open_public(url.host, port)
                if method == "CONNECT":
                    writer.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                    await writer.drain()
                else:
                    # El HTTP sin TLS no reutiliza una conexión de proxy para otro origen.
                    headers = [line for line in headers if line.split(b":", 1)[0].lower()
                               not in {b"connection", b"proxy-connection", b"proxy-authorization"}]
                    upstream.write(method.encode() + b" " + url.raw_path + b" " + version.encode()
                                   + b"\r\n" + b"\r\n".join(headers)
                                   + b"\r\nConnection: close\r\n\r\n")
                    await upstream.drain()
                pipes = [asyncio.create_task(_copy(reader, upstream)),
                         asyncio.create_task(_copy(remote, writer))]
                await asyncio.wait(pipes, return_when=asyncio.FIRST_COMPLETED)
        except (ValueError, OSError, httpcore.NetworkError, TimeoutError,
                asyncio.IncompleteReadError, asyncio.LimitOverrunError):
            pass
        finally:
            for pipe in pipes:
                pipe.cancel()
            if pipes:
                await asyncio.gather(*pipes, return_exceptions=True)
            if upstream is not None:
                upstream.close()
            writer.close()
            connections.discard(task)

    server = await asyncio.start_server(handle, "127.0.0.1", 0, limit=16384)
    try:
        yield f"http://127.0.0.1:{server.sockets[0].getsockname()[1]}"
    finally:
        server.close()
        await server.wait_closed()
        await _close_connections(connections)
