"""Comprueba la identidad exacta de los enlaces GitHub."""
import httpx
import pytest

from app.scraper.github_asset_urls import recover_github_asset_url, stable_installer_url

STABLE = "https://github.com/vendor/app/releases/download/v1.2/App.exe"
OLD = "https://release-assets.githubusercontent.com/github-production-release-asset/123/original?jwt=expired"


def test_keeps_exact_release_link_but_not_a_spoofed_host():
    assert stable_installer_url(STABLE, OLD) == STABLE
    assert stable_installer_url(STABLE.replace("github.com", "evilgithub.com"), OLD) == OLD


@pytest.mark.asyncio
@pytest.mark.parametrize("same_asset", [False, True])
async def test_recovers_only_a_matching_immutable_cdn_path(monkeypatch, same_asset):
    calls = []
    def request(req):
        calls.append(req)
        if req.url.host == "api.github.com":
            return httpx.Response(200, json=[{"assets": [
                {"name": "App.exe", "browser_download_url": STABLE.replace("v1.2", "v2.0")},
                {"name": "App.exe", "browser_download_url": STABLE},
                {"name": "App.exe", "browser_download_url": "http://localhost/private"},
            ]}])
        assert req.method == "HEAD"
        path = "original" if same_asset and "/v1.2/" in req.url.path else "different"
        return httpx.Response(302, headers={"location": OLD.replace("original", path)})
    monkeypatch.setattr(
        "app.scraper.github_asset_urls.public_http_client",
        lambda **kw: httpx.AsyncClient(transport=httpx.MockTransport(request), **kw),
    )
    result = await recover_github_asset_url(OLD, "App.exe", timeout=5)
    assert result == (STABLE if same_asset else None)
    assert all(req.url.host in {"api.github.com", "github.com"} for req in calls)


@pytest.mark.asyncio
async def test_ignores_unknown_cdn_shapes_without_network(monkeypatch):
    def unexpected(**kw):
        pytest.fail("No debe consultar la red sin identidad recuperable")
    monkeypatch.setattr("app.scraper.github_asset_urls.public_http_client", unexpected)
    result = await recover_github_asset_url("https://example.test/asset", "App.exe", timeout=5)
    assert result is None


@pytest.mark.asyncio
async def test_recovers_from_original_metadata_without_using_the_github_api(monkeypatch):
    def request(req):
        assert req.url.host == "github.com"
        return httpx.Response(302, headers={"location": OLD.replace("expired", "fresh")})
    monkeypatch.setattr(
        "app.scraper.github_asset_urls.public_http_client",
        lambda **kw: httpx.AsyncClient(transport=httpx.MockTransport(request), **kw),
    )
    result = await recover_github_asset_url(OLD, "App.exe", timeout=5, known_urls=[STABLE])
    assert result == STABLE
