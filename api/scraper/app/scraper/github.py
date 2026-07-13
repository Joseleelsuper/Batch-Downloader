from __future__ import annotations

from dataclasses import dataclass
from urllib.parse import quote, urlparse

import httpx

from app.core.config import Settings
from app.scraper.candidates import (
    LINUX_INSTALLER_EXTENSIONS,
    MACOS_INSTALLER_EXTENSIONS,
    WINDOWS_INSTALLER_EXTENSIONS,
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

    async def collect(self, url: str, version: str | None = None) -> list[InstallerCandidate]:
        repo = parse_github_repo(url)
        if not repo:
            return []
        tags = release_tags_from_url_or_version(url, version)

        owns_client = self.client is None
        client = self.client or httpx.AsyncClient(
            timeout=self.settings.request_timeout_seconds,
            follow_redirects=True,
            headers={"User-Agent": "Mozilla/5.0 BatchDownloaderScraper/0.1"},
        )
        try:
            candidates = await self._collect_from_api(client, repo, tags)
            if not candidates:
                candidates = await self._collect_from_html(client, repo, tags)
            return candidates
        finally:
            if owns_client:
                await client.aclose()

    async def _collect_from_api(
        self,
        client: httpx.AsyncClient,
        repo: GitHubRepo,
        tags: list[str],
    ) -> list[InstallerCandidate]:
        endpoints = [
            *[
                (
                    f"https://api.github.com/repos/{repo.owner}/{repo.name}/releases/tags/"
                    f"{quote(tag, safe='')}"
                )
                for tag in tags
            ],
            f"https://api.github.com/repos/{repo.owner}/{repo.name}/releases/latest",
        ]

        candidates: dict[str, InstallerCandidate] = {}
        for endpoint in endpoints:
            response = await client.get(endpoint)
            if not response.is_success:
                continue
            candidates.update(await self._candidates_from_api_release(response.json()))
            if candidates:
                break
        return list(candidates.values())

    async def _candidates_from_api_release(
        self,
        release: dict,
    ) -> dict[str, InstallerCandidate]:
        candidates: dict[str, InstallerCandidate] = {}
        if release.get("draft"):
            return candidates
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
        return candidates

    async def _collect_from_html(
        self,
        client: httpx.AsyncClient,
        repo: GitHubRepo,
        tags: list[str],
    ) -> list[InstallerCandidate]:
        discovered_tags = list(tags)
        for tag in discovered_tags:
            candidates = await self._collect_from_expanded_assets(client, repo, tag)
            if candidates:
                return candidates

        for tag in discovered_tags:
            response = await client.get(
                f"https://github.com/{repo.owner}/{repo.name}/releases/tag/{quote(tag, safe='')}"
            )
            if not response.is_success:
                continue
            candidates = self._candidates_from_html(
                response.text,
                str(response.url),
                "github_release_html",
                release_tag=tag,
            )
            if candidates:
                return candidates

        latest_response = await client.get(
            f"https://github.com/{repo.owner}/{repo.name}/releases/latest"
        )
        if latest_response.is_success:
            latest_tag = release_tag_from_url(str(latest_response.url))
            if latest_tag and latest_tag not in discovered_tags:
                discovered_tags.append(latest_tag)
            candidates = self._candidates_from_html(
                latest_response.text,
                str(latest_response.url),
                "github_release_html",
                release_tag=latest_tag,
            )
            if candidates:
                return candidates

        for tag in discovered_tags:
            candidates = await self._collect_from_expanded_assets(client, repo, tag)
            if candidates:
                return candidates

        for tag in discovered_tags:
            response = await client.get(
                f"https://github.com/{repo.owner}/{repo.name}/releases/tag/{quote(tag, safe='')}"
            )
            if not response.is_success:
                continue
            candidates = self._candidates_from_html(
                response.text,
                str(response.url),
                "github_release_html",
                release_tag=tag,
            )
            if candidates:
                return candidates
        return []

    async def _collect_from_expanded_assets(
        self,
        client: httpx.AsyncClient,
        repo: GitHubRepo,
        tag: str,
    ) -> list[InstallerCandidate]:
        response = await client.get(
            f"https://github.com/{repo.owner}/{repo.name}/releases/expanded_assets/"
            f"{quote(tag, safe='')}"
        )
        if not response.is_success:
            return []
        return self._candidates_from_html(
            response.text,
            f"https://github.com/{repo.owner}/{repo.name}/releases/tag/{tag}",
            "github_release_expanded_assets",
            release_tag=tag,
        )

    def _candidates_from_html(
        self,
        html: str,
        base_url: str,
        source: str,
        release_tag: str | None = None,
    ) -> list[InstallerCandidate]:
        candidates = extract_candidates(html, base_url)
        return [
            InstallerCandidate(
                url=candidate.url,
                source=source,
                label=candidate.label,
                context=github_candidate_context(candidate.context, release_tag),
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


def release_tag_from_url(url: str) -> str | None:
    parts = [part for part in urlparse(url).path.split("/") if part]
    try:
        index = parts.index("tag")
    except ValueError:
        return None
    if index + 1 >= len(parts):
        return None
    return parts[index + 1]


def release_tags_from_url_or_version(url: str, version: str | None) -> list[str]:
    tags: list[str] = []
    url_tag = release_tag_from_url(url)
    if url_tag:
        tags.append(url_tag)
    if version:
        cleaned = version.strip()
        if cleaned:
            tags.append(cleaned)
            if not cleaned.lower().startswith("v"):
                tags.append(f"v{cleaned}")
    return list(dict.fromkeys(tags))


def is_allowed_github_asset(url: str) -> bool:
    if is_github_source_archive(url):
        return False
    extension = detect_extension(url)
    if not extension or not is_github_release_asset(url):
        return False
    if extension in {".zip", ".tar.gz"}:
        return is_github_release_asset(url)
    return extension in (
        WINDOWS_INSTALLER_EXTENSIONS
        + MACOS_INSTALLER_EXTENSIONS
        + LINUX_INSTALLER_EXTENSIONS
    )


def asset_kind_for_github_asset(url: str) -> str:
    return "release_zip" if detect_extension(url) == ".zip" else "installer"


def github_candidate_context(existing_context: str | None, release_tag: str | None) -> str | None:
    if existing_context and release_tag:
        return f"{existing_context} {release_tag}"
    return existing_context or release_tag
