"""Prueba el socket real del proxy sin permitir que la fixture contacte con Internet."""
import asyncio

import httpx
import pytest

from app.scraper.http.browser_proxy import public_browser_proxy
from app.scraper.http.public_transport import public_addresses


@pytest.mark.asyncio
async def test_proxy_preserves_public_http_and_rejects_private_redirects_and_connect(monkeypatch):
    private_requests = []

    async def private(reader, writer):
        private_requests.append(await reader.read(1024))
        writer.close()

    private_server = await asyncio.start_server(private, "127.0.0.1", 0)
    private_port = private_server.sockets[0].getsockname()[1]

    async def publisher(reader, writer):
        header = await reader.readuntil(b"\r\n\r\n")
        if b"/redirect " in header:
            writer.write((f"HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:{private_port}/secret"
                          "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").encode())
        else:
            writer.write(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
        await writer.drain()
        writer.close()

    publisher_server = await asyncio.start_server(publisher, "127.0.0.1", 0)
    publisher_port = publisher_server.sockets[0].getsockname()[1]

    async def fixture_addresses(host, port):
        # Solo el origen ficticio aprobado se conecta al servidor aislado de la prueba.
        return ["127.0.0.1"] if host == "publisher.example" else await public_addresses(host, port)

    monkeypatch.setattr("app.scraper.http.browser_proxy.public_addresses", fixture_addresses)
    try:
        async with public_browser_proxy(2) as proxy, httpx.AsyncClient(
            proxy=proxy, trust_env=False, follow_redirects=True,
        ) as client:
            result = await client.get(f"http://publisher.example:{publisher_port}/normal")
            assert result.text == "ok"
            with pytest.raises(httpx.HTTPError):
                await client.get(f"http://publisher.example:{publisher_port}/redirect")
            with pytest.raises(httpx.HTTPError):
                await client.get(f"https://127.0.0.1:{private_port}/secret")
        assert private_requests == []
    finally:
        publisher_server.close()
        private_server.close()
        await publisher_server.wait_closed()
        await private_server.wait_closed()
