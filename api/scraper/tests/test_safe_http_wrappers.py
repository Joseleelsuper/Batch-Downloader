"""Pruebas de contrato de los wrappers HTTP del scraper."""
from __future__ import annotations

from contextlib import asynccontextmanager

import httpx
import pytest

from app.scraper.http.fetchers import PublicHttpsExchange, RedirectFollowingFetcher
from app.scraper.http.models import FetchRequest, SafeHttpError
from app.scraper.safe_http import probe_public_resource_size


class StubExchange:
    """Exchange determinista de un salto para verificar la composición."""

    def __init__(self, responses: list[httpx.Response]) -> None:
        self.responses = iter(responses)
        self.urls: list[str] = []

    @asynccontextmanager
    async def stream(self, url: str):
        self.urls.append(url)
        yield next(self.responses)


def response(status: int, url: str, **kwargs) -> httpx.Response:
    """Crea una respuesta en streaming todavía no consumida."""
    return httpx.Response(
        status,
        request=httpx.Request("GET", url),
        stream=httpx.ByteStream(kwargs.pop("content", b"")),
        **kwargs,
    )


@pytest.mark.asyncio
async def test_validates_every_redirect_immediately_before_network_access() -> None:
    transport = StubExchange([
        response(302, "https://downloads.example/latest", headers={"location": "/App.exe"}),
        response(200, "https://downloads.example/App.exe", content=b"installer"),
    ])
    validated: list[str] = []

    async def validator(url: str) -> str:
        validated.append(url)
        return url

    fetcher = RedirectFollowingFetcher(PublicHttpsExchange(transport, validator))
    result = await fetcher.fetch(FetchRequest(
        url="https://downloads.example/latest",
        timeout=1,
        max_redirects=2,
        max_bytes=20,
        accept="application/octet-stream",
    ))

    assert result.content == b"installer"
    assert validated == [
        "https://downloads.example/latest",
        "https://downloads.example/App.exe",
    ]
    assert transport.urls == validated


@pytest.mark.asyncio
async def test_rejects_stream_when_actual_body_crosses_the_limit() -> None:
    transport = StubExchange([
        response(200, "https://downloads.example/App.exe", content=b"123456"),
    ])

    async def validator(url: str) -> str:
        return url

    fetcher = RedirectFollowingFetcher(PublicHttpsExchange(transport, validator))
    with pytest.raises(SafeHttpError, match="content_too_large") as captured:
        await fetcher.fetch(FetchRequest(
            url="https://downloads.example/App.exe",
            timeout=1,
            max_redirects=0,
            max_bytes=5,
            accept="application/octet-stream",
        ))

    assert captured.value.transient is False


@pytest.mark.asyncio
@pytest.mark.parametrize("location", ["/App.exe", "https://127.0.0.1/App.exe",
                                      "https://user:password@example.test/App.exe"])
async def test_size_probe_checks_each_head_destination_without_reading_body(
    respx_mock, monkeypatch, location
) -> None:
    class UnreadBody(httpx.AsyncByteStream):
        async def __aiter__(self):
            raise AssertionError("A size probe must never read the installer body")
            yield b""  # pragma: no cover

    async def public_dns(host: str) -> bool:
        return host != "127.0.0.1"

    monkeypatch.setattr("app.scraper.safe_http.domain_has_public_dns", public_dns)
    initial = respx_mock.head("https://example.test/latest").mock(
        return_value=httpx.Response(302, headers={"location": location}, stream=UnreadBody())
    )
    final = respx_mock.head("https://example.test/App.exe").mock(
        return_value=httpx.Response(200, headers={"content-length": "8192"}, stream=UnreadBody())
    )
    if location == "/App.exe":
        assert await probe_public_resource_size(
            "https://example.test/latest", timeout=1, max_redirects=2
        ) == 8192
        assert final.call_count == 1
    else:
        with pytest.raises(SafeHttpError):
            await probe_public_resource_size(
                "https://example.test/latest", timeout=1, max_redirects=2
            )
        assert final.call_count == 0
    assert initial.call_count == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("length", [None, "0", "invalid", "-1", str(2**63)])
async def test_size_probe_leaves_unannounced_or_invalid_size_unknown(
    respx_mock, monkeypatch, length
) -> None:
    async def public_dns(_host: str) -> bool:
        return True

    monkeypatch.setattr("app.scraper.safe_http.domain_has_public_dns", public_dns)
    respx_mock.head("https://example.test/App.exe").mock(return_value=httpx.Response(
        200, headers={} if length is None else {"content-length": length}
    ))
    assert await probe_public_resource_size(
        "https://example.test/App.exe", timeout=1, max_redirects=2
    ) is None
