from __future__ import annotations

import base64
import json
import re
from dataclasses import dataclass
from urllib.parse import urljoin, urlparse

import httpx
from selectolax.parser import HTMLParser

from app.core.config import Settings
from app.scraper.github import parse_github_repo
from app.scraper.winstall import WinstallApp

BADGE_MARKERS = (
    "badge",
    "badgen.net",
    "build",
    "coverage",
    "license",
    "shields.io",
    "workflow",
)

# Este regex encontra imagens em markdown, como ![alt text](image_url "title")
IMAGE_MARKDOWN_PATTERN = re.compile(r"!\[([^\]]*)]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")

# Este regex encontra imagens em HTML, como <img src="image_url" alt="alt text">
IMAGE_HTML_PATTERN = re.compile(r"<img[^>]+src=[\"']([^\"']+)[\"']", re.IGNORECASE)


@dataclass(frozen=True)
class IconResult:
    url: str
    source: str


class IconResolver:
    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        self.settings = settings
        self.client = client

    async def resolve(self, app: WinstallApp) -> IconResult | None:
        homepage = app.homepage
        if not homepage:
            return None

        owns_client = self.client is None
        client = self.client or httpx.AsyncClient(
            timeout=self.settings.request_timeout_seconds,
            follow_redirects=True,
            headers={"User-Agent": "Mozilla/5.0 BatchDownloaderScraper/0.1"},
        )
        try:
            if parse_github_repo(homepage):
                github_icon = await self._from_github(client, homepage)
                if github_icon:
                    return github_icon
            page_icon = await self._from_official_page(client, homepage)
            if page_icon:
                return page_icon
            return None
        finally:
            if owns_client:
                await client.aclose()

    async def _from_official_page(
        self,
        client: httpx.AsyncClient,
        homepage: str,
    ) -> IconResult | None:
        try:
            response = await client.get(homepage)
        except httpx.HTTPError:
            return None
        if not response.is_success:
            return None
        content_type = response.headers.get("content-type", "")
        if content_type and "html" not in content_type:
            return None

        parser = HTMLParser(response.text)
        base_url = str(response.url)
        for selector, attr, source in (
            ("link[rel]", "href", "official_link_icon"),
            ("meta[property='og:image']", "content", "official_og_image"),
            ("meta[name='twitter:image']", "content", "official_twitter_image"),
        ):
            result = self._first_icon_from_nodes(parser, selector, attr, base_url, source)
            if result:
                return result

        manifest = self._first_icon_from_nodes(
            parser,
            "link[rel='manifest']",
            "href",
            base_url,
            "official_manifest",
            allow_rel_any=True,
        )
        if manifest:
            manifest_icon = await self._from_manifest(client, manifest.url)
            if manifest_icon:
                return manifest_icon

        return None

    def _first_icon_from_nodes(
        self,
        parser: HTMLParser,
        selector: str,
        attr: str,
        base_url: str,
        source: str,
        allow_rel_any: bool = False,
    ) -> IconResult | None:
        for node in parser.css(selector):
            if not allow_rel_any:
                rel = node.attributes.get("rel", "")
                if node.tag == "link" and "icon" not in rel.lower():
                    continue
            value = node.attributes.get(attr)
            if not value:
                continue
            icon_url = urljoin(base_url, value)
            if usable_icon_url(icon_url):
                return IconResult(icon_url, source)
        return None

    async def _from_manifest(
        self,
        client: httpx.AsyncClient,
        manifest_url: str,
    ) -> IconResult | None:
        try:
            response = await client.get(manifest_url)
        except httpx.HTTPError:
            return None
        if not response.is_success:
            return None
        try:
            payload = response.json()
        except json.JSONDecodeError:
            return None
        icons = payload.get("icons")
        if not isinstance(icons, list):
            return None
        best_url: str | None = None
        best_size = -1
        for icon in icons:
            if not isinstance(icon, dict):
                continue
            src = icon.get("src")
            if not isinstance(src, str):
                continue
            size = largest_icon_size(icon.get("sizes"))
            icon_url = urljoin(str(response.url), src)
            if usable_icon_url(icon_url) and size >= best_size:
                best_url = icon_url
                best_size = size
        return IconResult(best_url, "official_manifest_icon") if best_url else None

    async def _from_github(
        self,
        client: httpx.AsyncClient,
        homepage: str,
    ) -> IconResult | None:
        repo = parse_github_repo(homepage)
        if not repo:
            return None

        readme = await self._github_readme_icon(client, repo.owner, repo.name)
        if readme:
            return readme

        page_image = await self._github_page_image(client, homepage)
        if page_image:
            return page_image

        avatar = await self._github_avatar(client, repo.owner, repo.name)
        if avatar:
            return avatar

        return None

    async def _github_page_image(
        self,
        client: httpx.AsyncClient,
        homepage: str,
    ) -> IconResult | None:
        try:
            response = await client.get(homepage)
        except httpx.HTTPError:
            return None
        if not response.is_success:
            return None
        parser = HTMLParser(response.text)
        for selector, attr in (
            ("meta[property='og:image']", "content"),
            ("meta[name='twitter:image']", "content"),
        ):
            result = self._first_icon_from_nodes(
                parser,
                selector,
                attr,
                str(response.url),
                "github_page_image",
                allow_rel_any=True,
            )
            if result:
                return result
        return None

    async def _github_avatar(
        self,
        client: httpx.AsyncClient,
        owner: str,
        repo: str,
    ) -> IconResult | None:
        try:
            response = await client.get(f"https://api.github.com/repos/{owner}/{repo}")
        except httpx.HTTPError:
            return None
        if not response.is_success:
            return None
        payload = response.json()
        owner_data = payload.get("owner")
        if not isinstance(owner_data, dict):
            return None
        avatar_url = owner_data.get("avatar_url")
        if isinstance(avatar_url, str) and usable_icon_url(avatar_url):
            return IconResult(avatar_url, "github_owner_avatar")
        return None

    async def _github_readme_icon(
        self,
        client: httpx.AsyncClient,
        owner: str,
        repo: str,
    ) -> IconResult | None:
        try:
            response = await client.get(
                f"https://api.github.com/repos/{owner}/{repo}/readme",
                headers={"Accept": "application/vnd.github.raw"},
            )
        except httpx.HTTPError:
            return None
        if not response.is_success:
            return None
        text = readme_text(response)
        for label, image_url in readme_images(text):
            if is_badge_image(label, image_url):
                continue
            resolved = resolve_github_readme_image(owner, repo, image_url)
            if usable_icon_url(resolved):
                return IconResult(resolved, "github_readme_image")
        return None


def largest_icon_size(value: object) -> int:
    if not isinstance(value, str):
        return 0
    sizes = [int(match) for match in re.findall(r"(\d+)x\d+", value)]
    return max(sizes) if sizes else 0


def readme_text(response: httpx.Response) -> str:
    content_type = response.headers.get("content-type", "")
    if "json" not in content_type:
        return response.text
    payload = response.json()
    content = payload.get("content")
    if isinstance(content, str):
        try:
            return base64.b64decode(content).decode("utf-8", errors="ignore")
        except ValueError:
            return ""
    return ""


def readme_images(text: str) -> list[tuple[str, str]]:
    images: list[tuple[str, str]] = []
    for match in IMAGE_MARKDOWN_PATTERN.finditer(text):
        images.append((match.group(1), match.group(2)))
    for match in IMAGE_HTML_PATTERN.finditer(text):
        images.append(("", match.group(1)))
    return images


def resolve_github_readme_image(owner: str, repo: str, image_url: str) -> str:
    parsed = urlparse(image_url)
    if parsed.scheme in {"http", "https"}:
        return image_url
    return f"https://raw.githubusercontent.com/{owner}/{repo}/HEAD/{image_url.lstrip('./')}"


def usable_icon_url(url: str) -> bool:
    parsed = urlparse(url)
    return parsed.scheme in {"http", "https"} and not is_badge_image("", url)


def is_badge_image(label: str, url: str) -> bool:
    text = f"{label} {url}".lower()
    return any(marker in text for marker in BADGE_MARKERS)
