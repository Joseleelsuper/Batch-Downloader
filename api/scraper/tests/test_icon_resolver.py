from types import SimpleNamespace

import httpx
import pytest
import respx

from app.core.config import Settings
from app.scraper.icon_resolver import IconResolver


@pytest.mark.asyncio
@respx.mock
async def test_icon_resolver_extracts_official_favicon() -> None:
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

    result = await IconResolver(Settings()).resolve(
        SimpleNamespace(homepage="https://example.com/download")
    )

    assert result is not None
    assert result.url == "https://example.com/favicon.ico"
    assert result.source == "official_link_icon"


@pytest.mark.asyncio
@respx.mock
async def test_icon_resolver_uses_github_readme_image_and_ignores_badges() -> None:
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

    result = await IconResolver(Settings()).resolve(
        SimpleNamespace(homepage="https://github.com/vendor/app")
    )

    assert result is not None
    assert result.url == "https://raw.githubusercontent.com/vendor/app/HEAD/docs/logo.png"
    assert result.source == "github_readme_image"
