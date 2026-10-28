"""Comprueba la autorización del socket y los límites completos de respuestas externas."""
import asyncio
from unittest.mock import AsyncMock

import httpcore
import httpx
import pytest

from app.scraper.http.fetchers import fetch_bounded_response
from app.scraper.http.models import SafeHttpError
from app.scraper.http.public_transport import PublicNetworkBackend, public_http_client


@pytest.mark.asyncio
@pytest.mark.parametrize("address", [
    "127.0.0.1", "10.0.0.1", "169.254.169.254", "::1", "224.0.0.1", "100.64.0.1",
])
async def test_socket_rejects_private_dns_answer_before_connecting(monkeypatch, address):
    backend = AsyncMock(spec=httpcore.AsyncNetworkBackend)
    monkeypatch.setattr(asyncio.get_running_loop(), "getaddrinfo", AsyncMock(
        return_value=[(None, None, None, None, (address, 80))],
    ))
    with pytest.raises(httpcore.ConnectError, match="dns_not_public"):
        await PublicNetworkBackend(backend).connect_tcp("publisher.example", 80, timeout=1)
    backend.connect_tcp.assert_not_called()


@pytest.mark.asyncio
async def test_socket_uses_the_approved_ip_without_re_resolving_the_hostname(monkeypatch):
    backend = AsyncMock(spec=httpcore.AsyncNetworkBackend)
    lookup = AsyncMock(return_value=[(None, None, None, None, ("93.184.216.34", 8443))])
    monkeypatch.setattr(asyncio.get_running_loop(), "getaddrinfo", lookup)
    await PublicNetworkBackend(backend).connect_tcp("publisher.example", 8443, timeout=1)
    backend.connect_tcp.assert_awaited_once_with(
        "93.184.216.34", 8443, timeout=1, local_address=None, socket_options=None,
    )
    assert lookup.await_count == 1


class SlowBody(httpx.AsyncByteStream):
    async def __aiter__(self):
        while True:
            await asyncio.sleep(0.01)
            yield b"x"


@pytest.mark.asyncio
async def test_metadata_deadline_closes_a_slow_body_that_keeps_producing_chunks():
    response = httpx.Response(200, stream=SlowBody())
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda request: response)) as client:
        with pytest.raises(SafeHttpError, match="timeout"):
            await fetch_bounded_response(
                client, "https://publisher.example/", timeout=0.04, max_bytes=1000,
            )
    assert response.is_closed


@pytest.mark.asyncio
@pytest.mark.parametrize("headers,body", [
    ({}, b"x" * 101), ({"content-encoding": "gzip"}, b"small"),
])
async def test_metadata_rejects_oversized_or_encoded_bodies(headers, body):
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda request: httpx.Response(200, headers=headers, stream=httpx.ByteStream(body)),
    )) as client:
        with pytest.raises(SafeHttpError):
            await fetch_bounded_response(
                client, "https://publisher.example/", timeout=1, max_bytes=100,
            )


@pytest.mark.asyncio
async def test_metadata_preserves_relative_redirects_and_legitimate_html():
    def respond(request):
        if request.url.path == "/":
            return httpx.Response(302, headers={"location": "/download"})
        assert request.headers["accept-encoding"] == "identity"
        return httpx.Response(200, text="<html>Download</html>")

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(respond), follow_redirects=True,
    ) as client:
        result = await fetch_bounded_response(
            client, "https://publisher.example/", timeout=1, max_bytes=100,
        )
        assert result.text == "<html>Download</html>"
        assert str(result.url) == "https://publisher.example/download"


@pytest.mark.asyncio
async def test_factory_keeps_private_dns_rejection_on_real_httpx_requests(monkeypatch):
    monkeypatch.setattr(asyncio.get_running_loop(), "getaddrinfo", AsyncMock(
        return_value=[(None, None, None, None, ("127.0.0.1", 80))],
    ))
    async with public_http_client(timeout=1, headers={"User-Agent": "test"}) as client:
        with pytest.raises(httpx.ConnectError, match="dns_not_public"):
            await client.get("http://publisher.example/")


@pytest.mark.asyncio
async def test_factory_leaves_redirects_to_the_bounded_reader_and_keeps_headers(monkeypatch):
    from app.scraper.http import public_transport

    requests = []

    def respond(request):
        requests.append(request)
        return httpx.Response(302, headers={"location": "http://127.0.0.1/private"})

    monkeypatch.setattr(public_transport, "PublicAsyncHTTPTransport", lambda: httpx.MockTransport(
        respond,
    ))
    async with public_http_client(timeout=1, headers={"User-Agent": "custom-agent"}) as client:
        response = await client.get("https://publisher.example/")
    assert response.status_code == 302
    assert len(requests) == 1
    assert requests[0].headers["user-agent"] == "custom-agent"
