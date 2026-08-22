"""Pruebas de contrato de los wrappers HTTP del scraper."""
from __future__ import annotations

from contextlib import asynccontextmanager

import httpx
import pytest

from app.scraper.http.fetchers import PublicHttpsExchange, RedirectFollowingFetcher
from app.scraper.http.models import FetchRequest, SafeHttpError


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
