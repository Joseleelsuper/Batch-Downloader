import httpx
import pytest
import respx

from app.core.config import Settings
from app.scraper.github import GitHubReleaseResolver, parse_github_repo


def test_parse_github_repo() -> None:
    repo = parse_github_repo("https://github.com/bibletime/bibletime")

    assert repo is not None
    assert repo.owner == "bibletime"
    assert repo.name == "bibletime"


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
