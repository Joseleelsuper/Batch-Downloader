"""Contiene las pruebas de `test_icon_resolver`.
"""
from types import SimpleNamespace

import httpx
import pytest
import respx

from app.core.config import Settings
from app.scraper.icon_resolver import IconResolver, github_avatar_owner


@pytest.mark.asyncio
@respx.mock
async def test_icon_resolver_extracts_official_favicon(monkeypatch) -> None:
    """Comprueba el escenario `icon_resolver_extracts_official_favicon`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
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
    respx.get("https://example.com/og.png").mock(return_value=httpx.Response(404))

    result = await IconResolver(Settings()).resolve(
        SimpleNamespace(homepage="https://example.com/download")
    )

    assert result is not None
    assert result.url == "https://example.com/favicon.ico"
    assert result.source == "official_link_icon"


@pytest.mark.asyncio
@respx.mock
async def test_icon_resolver_uses_github_readme_image_and_ignores_badges(monkeypatch) -> None:
    """Comprueba el escenario `icon_resolver_uses_github_readme_image_and_ignores_badges`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
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
    """Comprueba el escenario `icon_resolver_uses_custom_github_social_image_after_readme`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
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
    """Comprueba el escenario `icon_resolver_falls_back_to_github_owner_avatar`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
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


@pytest.mark.asyncio
@respx.mock
async def test_icon_resolver_uses_official_image_for_non_github_page(monkeypatch) -> None:
    """Comprueba metadatos de imagen en páginas oficiales que no pertenecen a GitHub."""
    monkeypatch.setattr("app.scraper.icon_resolver.public_https_url", public_url)
    respx.get("https://vendor.example/app").mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "text/html"},
            html=(
                '<meta property="og:image" content="/missing.png">'
                '<meta name="twitter:image" content="/app-logo.png">'
            ),
        )
    )
    respx.get("https://vendor.example/missing.png").mock(return_value=httpx.Response(404))
    respx.get("https://vendor.example/app-logo.png").mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "image/png"},
            content=b"png",
        )
    )

    result = await IconResolver(Settings()).resolve(
        SimpleNamespace(homepage="https://vendor.example/app")
    )

    assert result is not None
    assert result.url == "https://vendor.example/app-logo.png"
    assert result.source == "official_twitter_image"


@pytest.mark.asyncio
@respx.mock
async def test_icon_resolver_uses_github_avatar_after_broken_readme_image(monkeypatch) -> None:
    """Un README con una imagen rota no debe impedir el fallback al avatar del propietario."""
    monkeypatch.setattr("app.scraper.icon_resolver.public_https_url", public_url)
    respx.get("https://api.github.com/repos/vendor/app/readme").mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "text/plain"},
            text="![logo](docs/missing.png)",
        )
    )
    respx.get("https://raw.githubusercontent.com/vendor/app/HEAD/docs/missing.png").mock(
        return_value=httpx.Response(404)
    )
    respx.get("https://github.com/vendor/app").mock(return_value=httpx.Response(404))
    respx.get("https://github.com/vendor.png?size=128").mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "image/png"},
            content=b"png",
        )
    )

    result = await IconResolver(Settings()).resolve(
        SimpleNamespace(homepage="https://github.com/vendor/app")
    )

    assert result is not None
    assert result.url == "https://github.com/vendor.png?size=128"
    assert result.source == "github_owner_avatar"


@pytest.mark.parametrize(
    ("url", "owner"),
    [
        ("https://github.com/octocat/tool", "octocat"),
        ("https://github.com/orgs/octo-org", "octo-org"),
        ("https://microsoft.github.io/tool", "microsoft"),
    ],
)
def test_github_avatar_owner_supports_repos_profiles_and_github_pages(url, owner) -> None:
    assert github_avatar_owner(url) == owner


async def public_url(_: str) -> bool:
    """Prepara el recurso `public_url` usado por las pruebas para aislar el escenario `public
    url` y conservar sus datos de entrada.

    Args:
        _: Entrada `_` del escenario que se mantiene estable para la prueba.
    """
    return True
