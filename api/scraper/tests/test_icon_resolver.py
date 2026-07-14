from types import SimpleNamespace

import httpx
import pytest
import respx

from app.core.config import Settings
from app.scraper.icon_resolver import IconResolver


@pytest.mark.asyncio
@respx.mock
async def test_icon_resolver_extracts_official_favicon(monkeypatch) -> None:
    monkeypatch.setattr("app.scraper.icon_resolver.public_https_url", public_url)
    respx.get("https://example.com/download").mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "text/html"},
            html="""
            <html>
              <head>
                <link rel="icon" href="/favicon.ico">
                <meta property="og:image" content="/og.png">
              </head>
            </html>
            """,
        )
    )
    respx.get("https://example.com/favicon.ico").mock(
        return_value=httpx.Response(200, headers={"content-type": "image/x-icon"}, content=b"icon")
    )

    result = await IconResolver(Settings()).resolve(
        SimpleNamespace(homepage="https://example.com/download")
    )

    assert result is not None
    assert result.url == "https://example.com/favicon.ico"
    assert result.source == "official_link_icon"


@pytest.mark.asyncio
@respx.mock
async def test_icon_resolver_uses_github_readme_image_and_ignores_badges(monkeypatch) -> None:
    monkeypatch.setattr("app.scraper.icon_resolver.public_https_url", public_url)
    respx.get("https://api.github.com/repos/vendor/app/readme").mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "text/plain"},
            text="""
            ![build](https://img.shields.io/github/actions/workflow/status/vendor/app/ci.yml)
            ![logo](docs/logo.png)
            """,
        )
    )
    respx.get("https://raw.githubusercontent.com/vendor/app/HEAD/docs/logo.png").mock(
        return_value=httpx.Response(200, headers={"content-type": "image/png"}, content=b"png")
    )

    result = await IconResolver(Settings()).resolve(
        SimpleNamespace(homepage="https://github.com/vendor/app")
    )

    assert result is not None
    assert result.url == "https://raw.githubusercontent.com/vendor/app/HEAD/docs/logo.png"
    assert result.source == "github_readme_image"


@pytest.mark.asyncio
@respx.mock
async def test_icon_resolver_uses_custom_github_social_image_after_readme(monkeypatch) -> None:
    monkeypatch.setattr("app.scraper.icon_resolver.public_https_url", public_url)
    respx.get("https://api.github.com/repos/vendor/app/readme").mock(return_value=httpx.Response(404))
    respx.get("https://github.com/vendor/app").mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "text/html"},
            html=(
                '<meta property="og:image" '
                'content="https://repository-images.githubusercontent.com/123/logo.png">'
            ),
        )
    )
    respx.get("https://repository-images.githubusercontent.com/123/logo.png").mock(
        return_value=httpx.Response(200, headers={"content-type": "image/png"}, content=b"png")
    )

    result = await IconResolver(Settings()).resolve(
        SimpleNamespace(homepage="https://github.com/vendor/app")
    )

    assert result is not None
    assert result.url == "https://repository-images.githubusercontent.com/123/logo.png"
    assert result.source == "github_page_image"


@pytest.mark.asyncio
@respx.mock
async def test_icon_resolver_falls_back_to_github_owner_avatar(monkeypatch) -> None:
    monkeypatch.setattr("app.scraper.icon_resolver.public_https_url", public_url)
    respx.get("https://api.github.com/repos/vendor/app/readme").mock(return_value=httpx.Response(404))
    respx.get("https://github.com/vendor/app").mock(return_value=httpx.Response(404))
    respx.get("https://github.com/vendor.png?size=128").mock(
        return_value=httpx.Response(200, headers={"content-type": "image/png"}, content=b"png")
    )

    result = await IconResolver(Settings()).resolve(
        SimpleNamespace(homepage="https://github.com/vendor/app")
    )

    assert result is not None
    assert result.url == "https://github.com/vendor.png?size=128"
    assert result.source == "github_owner_avatar"


async def public_url(_: str) -> bool:
    return True
