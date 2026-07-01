from __future__ import annotations

from dataclasses import dataclass
from urllib.parse import urlparse

import httpx

from app.core.config import Settings
from app.scraper.candidates import (
    InstallerCandidate,
    detect_extension,
    extract_candidates,
    is_github_release_asset,
    is_github_source_archive,
)


@dataclass(frozen=True)
class GitHubRepo:
    owner: str
    name: str


class GitHubReleaseResolver:
    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        self.settings = settings
        self.client = client

    async def collect(self, url: str) -> list[InstallerCandidate]:
        repo = parse_github_repo(url)
        if not repo:
            return []

        owns_client = self.client is None
        client = self.client or httpx.AsyncClient(
            timeout=self.settings.request_timeout_seconds,
            follow_redirects=True,
            headers={"User-Agent": "BatchDownloaderScraper/0.1"},
        )
        try:
            candidates = await self._collect_from_api(client, repo)
            if not candidates:
                candidates = await self._collect_from_html(client, repo)
            return candidates
        finally:
            if owns_client:
                await client.aclose()

    async def _collect_from_api(
        self,
        client: httpx.AsyncClient,
        repo: GitHubRepo,
    ) -> list[InstallerCandidate]:
        response = await client.get(
            f"https://api.github.com/repos/{repo.owner}/{repo.name}/releases/latest"
        )
        if not response.is_success:
            return []

        candidates: dict[str, InstallerCandidate] = {}
        release = response.json()
        if release.get("draft"):
            return []
        release_label = " ".join(
            value
            for value in (release.get("name"), release.get("tag_name"))
            if isinstance(value, str)
        )
        for asset in release.get("assets") or []:
            if not isinstance(asset, dict):
                continue
            asset_url = asset.get("browser_download_url")
            asset_name = asset.get("name")
            if not isinstance(asset_url, str) or not is_allowed_github_asset(asset_url):
                continue
            candidates.setdefault(
                asset_url,
                InstallerCandidate(
                    url=asset_url,
                    source="github_release_api",
                    label=asset_name if isinstance(asset_name, str) else None,
                    context=release_label,
                    asset_kind=asset_kind_for_github_asset(asset_url),
                )
            )
        return list(candidates.values())

    async def _collect_from_html(
        self,
        client: httpx.AsyncClient,
        repo: GitHubRepo,
    ) -> list[InstallerCandidate]:
        response = await client.get(f"https://github.com/{repo.owner}/{repo.name}/releases/latest")
        if not response.is_success:
            return []
        candidates = extract_candidates(
            response.text,
            f"https://github.com/{repo.owner}/{repo.name}/releases/latest",
        )
        return [
            InstallerCandidate(
                url=candidate.url,
                source="github_release_html",
                label=candidate.label,
                context=candidate.context,
                asset_kind=asset_kind_for_github_asset(candidate.url),
            )
            for candidate in candidates
            if is_allowed_github_asset(candidate.url)
        ]


def parse_github_repo(url: str) -> GitHubRepo | None:
    parsed = urlparse(url)
    if parsed.netloc.lower() not in {"github.com", "www.github.com"}:
        return None
    parts = [part for part in parsed.path.split("/") if part]
    if len(parts) < 2:
        return None
    if parts[0].lower() in {"features", "marketplace", "pricing", "topics"}:
        return None
    return GitHubRepo(owner=parts[0], name=parts[1])


def is_allowed_github_asset(url: str) -> bool:
    if is_github_source_archive(url):
        return False
    extension = detect_extension(url)
    if extension == ".zip":
        return is_github_release_asset(url)
    return extension in {".exe", ".msi", ".msix", ".appx"}


def asset_kind_for_github_asset(url: str) -> str:
    return "release_zip" if detect_extension(url) == ".zip" else "installer"
