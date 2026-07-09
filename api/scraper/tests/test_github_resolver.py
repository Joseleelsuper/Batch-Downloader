import httpx
import pytest
import respx

from app.core.config import Settings
from app.scraper.candidates import extract_version
from app.scraper.github import GitHubReleaseResolver, parse_github_repo, release_tags_from_url_or_version


def test_parse_github_repo() -> None:
    repo = parse_github_repo("https://github.com/bibletime/bibletime")

    assert repo is not None
    assert repo.owner == "bibletime"
    assert repo.name == "bibletime"


def test_release_tags_include_url_tag_version_and_v_prefixed_version() -> None:
    assert release_tags_from_url_or_version(
        "https://github.com/owner/repo/releases/tag/1.2.3",
        "2.0.0",
    ) == ["1.2.3", "2.0.0", "v2.0.0"]


@pytest.mark.asyncio
@respx.mock
async def test_github_resolver_uses_release_assets_and_skips_source_zips() -> None:
    respx.get("https://api.github.com/repos/bibletime/bibletime/releases/latest").mock(
        return_value=httpx.Response(
            200,
            json={
                "name": "BibleTime 3.1.1",
                "tag_name": "v3.1.1",
                "draft": False,
                "assets": [
                    {
                        "name": "BibleTime-3.1.1-win64.exe",
                        "browser_download_url": "https://github.com/bibletime/bibletime/releases/download/v3.1.1/BibleTime-3.1.1-win64.exe",
                    },
                    {
                        "name": "source.zip",
                        "browser_download_url": "https://codeload.github.com/bibletime/bibletime/zip/refs/heads/main",
                    },
                    {
                        "name": "BibleTime-3.1.1-portable.zip",
                        "browser_download_url": "https://github.com/bibletime/bibletime/releases/download/v3.1.1/BibleTime-3.1.1-portable.zip",
                    },
                ],
            },
        )
    )

    candidates = await GitHubReleaseResolver(Settings()).collect(
        "https://github.com/bibletime/bibletime"
    )

    urls = {candidate.url for candidate in candidates}
    assert "https://github.com/bibletime/bibletime/releases/download/v3.1.1/BibleTime-3.1.1-win64.exe" in urls
    assert "https://github.com/bibletime/bibletime/releases/download/v3.1.1/BibleTime-3.1.1-portable.zip" in urls
    assert all("codeload.github.com" not in url for url in urls)


@pytest.mark.asyncio
@respx.mock
async def test_github_resolver_prefers_winstall_version_tag_over_latest() -> None:
    respx.get(
        "https://api.github.com/repos/0xGingi/0xgingi-browser-windows/releases/tags/115.0.5790.110"
    ).mock(
        return_value=httpx.Response(
            200,
            json={
                "name": "115.0.5790.110",
                "tag_name": "115.0.5790.110",
                "draft": False,
                "assets": [
                    {
                        "name": "0xgingi-browser_115.0.5790.110-1.1_installer.exe",
                        "browser_download_url": "https://github.com/0xGingi/0xgingi-browser-windows/releases/download/115.0.5790.110/0xgingi-browser_115.0.5790.110-1.1_installer.exe",
                    },
                ],
            },
        )
    )
    respx.get(
        "https://api.github.com/repos/0xGingi/0xgingi-browser-windows/releases/latest"
    ).mock(
        return_value=httpx.Response(
            200,
            json={
                "name": "Older latest",
                "tag_name": "115.0.5790.102",
                "draft": False,
                "assets": [
                    {
                        "name": "0xgingi-browser_115.0.5790.102-1.1_installer.exe",
                        "browser_download_url": "https://github.com/0xGingi/0xgingi-browser-windows/releases/download/115.0.5790.102/0xgingi-browser_115.0.5790.102-1.1_installer.exe",
                    },
                ],
            },
        )
    )

    candidates = await GitHubReleaseResolver(Settings()).collect(
        "https://github.com/0xGingi/0xgingi-browser-windows",
        "115.0.5790.110",
    )

    urls = {candidate.url for candidate in candidates}
    assert "https://github.com/0xGingi/0xgingi-browser-windows/releases/download/115.0.5790.110/0xgingi-browser_115.0.5790.110-1.1_installer.exe" in urls
    assert all("115.0.5790.102" not in url for url in urls)


@pytest.mark.asyncio
@respx.mock
async def test_github_resolver_falls_back_to_expanded_assets_when_api_is_rate_limited() -> None:
    respx.get("https://api.github.com/repos/0x192/universal-android-debloater/releases/latest").mock(
        return_value=httpx.Response(403, json={"message": "API rate limit exceeded"})
    )
    respx.get("https://api.github.com/repos/0x192/universal-android-debloater/releases/tags/0.5.1").mock(
        return_value=httpx.Response(403, json={"message": "API rate limit exceeded"})
    )
    respx.get("https://api.github.com/repos/0x192/universal-android-debloater/releases/tags/v0.5.1").mock(
        return_value=httpx.Response(404)
    )
    respx.get("https://github.com/0x192/universal-android-debloater/releases/latest").mock(
        return_value=httpx.Response(200, html="<html><title>Releases</title></html>")
    )
    respx.get("https://github.com/0x192/universal-android-debloater/releases/expanded_assets/0.5.1").mock(
        return_value=httpx.Response(
            200,
            html="""
            <a href="/0x192/universal-android-debloater/releases/download/0.5.1/uad_gui-windows.exe">uad_gui-windows.exe</a>
            <a href="/0x192/universal-android-debloater/releases/download/0.5.1/uad_gui-linux.tar.gz">uad_gui-linux.tar.gz</a>
            <a href="/0x192/universal-android-debloater/releases/download/0.5.1/uad_gui-macos.tar.gz">uad_gui-macos.tar.gz</a>
            <a href="/0x192/universal-android-debloater/archive/refs/tags/0.5.1.zip">Source code</a>
            """,
        )
    )

    candidates = await GitHubReleaseResolver(Settings()).collect(
        "https://github.com/0x192/universal-android-debloater",
        "0.5.1",
    )

    urls = {candidate.url for candidate in candidates}
    assert "https://github.com/0x192/universal-android-debloater/releases/download/0.5.1/uad_gui-windows.exe" in urls
    assert "https://github.com/0x192/universal-android-debloater/releases/download/0.5.1/uad_gui-linux.tar.gz" in urls
    assert "https://github.com/0x192/universal-android-debloater/releases/download/0.5.1/uad_gui-macos.tar.gz" in urls
    assert all("/archive/" not in url for url in urls)
    assert {extract_version(candidate) for candidate in candidates} == {"0.5.1"}
