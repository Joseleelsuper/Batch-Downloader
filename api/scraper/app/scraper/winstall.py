from __future__ import annotations

import json
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urljoin, urlparse

import httpx
from selectolax.parser import HTMLParser
from tenacity import retry, stop_after_attempt, wait_exponential

from app.core.config import Settings
from app.scraper.candidates import detect_extension
from app.scraper.text import normalize_text


@dataclass(frozen=True)
class WinstallVersion:
    version: str | None
    installer_type: str | None
    installers: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class WinstallDownload:
    url: str
    label: str | None = None
    context: str | None = None


@dataclass(frozen=True)
class WinstallPageLinks:
    official_url: str | None
    source_code_url: str | None
    downloads: list[WinstallDownload]


@dataclass(frozen=True)
class WinstallApp:
    package_id: str
    name: str
    description: str | None
    publisher: str | None
    homepage: str | None
    icon: str | None
    icon_url: str | None
    latest_version: str | None
    tags: list[str]
    versions: list[WinstallVersion]
    raw: dict[str, Any]

    @property
    def installer_urls(self) -> list[str]:
        urls: list[str] = []
        for version in self.versions:
            urls.extend(version.installers)
        return list(dict.fromkeys(urls))


WINSTALL_CATALOG_PAGE_SIZE = 60


class WinstallClient:
    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        self.settings = settings
        self._client = client

    async def __aenter__(self) -> WinstallClient:
        if self._client is None:
            self._client = httpx.AsyncClient(
                timeout=self.settings.request_timeout_seconds,
                follow_redirects=True,
                headers={"User-Agent": "BatchDownloaderScraper/0.1"},
            )
        return self

    async def __aexit__(self, *args) -> None:
        if self._client:
            await self._client.aclose()
            self._client = None

    async def iter_apps(self) -> AsyncIterator[WinstallApp]:
        offset = 0
        total: int | None = None
        while total is None or offset < total:
            try:
                payload = await self._fetch_catalog_page(offset, WINSTALL_CATALOG_PAGE_SIZE)
            except Exception:
                payload = None
            if payload is None and offset == 0:
                payload = await self._fetch_catalog_from_next_data()
            if not payload:
                break

            total = int(payload.get("total") or 0)
            data = payload.get("data") or []
            if not data:
                break
            for raw_app in data:
                yield parse_winstall_app(raw_app)
            offset += len(data)

    async def get_app(self, package_id: str) -> WinstallApp:
        try:
            payload = await self._fetch_app(package_id)
        except Exception:
            payload = None
        if payload is None:
            payload = await self._fetch_app_from_page(package_id)
        if not payload:
            raise LookupError(f"Winstall app not found: {package_id}")
        return parse_winstall_app(payload)

    async def get_downloads(self, package_id: str) -> list[WinstallDownload]:
        links = await self.get_page_links(package_id)
        return links.downloads

    async def get_page_links(self, package_id: str) -> WinstallPageLinks:
        assert self._client is not None
        response = await self._client.get(f"{self.settings.winstall_base_url}/apps/{package_id}")
        if not response.is_success:
            return WinstallPageLinks(official_url=None, source_code_url=None, downloads=[])
        return extract_winstall_page_links(
            response.text,
            f"{self.settings.winstall_base_url}/apps/{package_id}",
        )

    @retry(wait=wait_exponential(multiplier=0.5, min=0.5, max=4), stop=stop_after_attempt(3))
    async def _fetch_catalog_page(self, offset: int, limit: int) -> dict[str, Any] | None:
        assert self._client is not None
        url = f"{self.settings.winstall_api_base_url}/apps"
        response = await self._client.get(url, params={"offset": offset, "limit": limit})
        if response.status_code >= 500:
            response.raise_for_status()
        if not response.is_success:
            return None
        return response.json()

    @retry(wait=wait_exponential(multiplier=0.5, min=0.5, max=4), stop=stop_after_attempt(3))
    async def _fetch_app(self, package_id: str) -> dict[str, Any] | None:
        assert self._client is not None
        response = await self._client.get(f"{self.settings.winstall_api_base_url}/apps/{package_id}")
        if response.status_code >= 500:
            response.raise_for_status()
        if not response.is_success:
            return None
        return response.json()

    async def _fetch_catalog_from_next_data(self) -> dict[str, Any] | None:
        assert self._client is not None
        response = await self._client.get(f"{self.settings.winstall_base_url}/apps")
        if not response.is_success:
            return None
        return extract_next_data(response.text, "data")

    async def _fetch_app_from_page(self, package_id: str) -> dict[str, Any] | None:
        assert self._client is not None
        response = await self._client.get(f"{self.settings.winstall_base_url}/apps/{package_id}")
        if not response.is_success:
            return None
        return extract_next_data(response.text, "app")


def extract_next_data(html: str, key: str) -> dict[str, Any] | None:
    parser = HTMLParser(html)
    node = parser.css_first("script#__NEXT_DATA__")
    if node is None or not node.text():
        return None
    try:
        payload = json.loads(node.text())
    except json.JSONDecodeError:
        return None
    page_props = payload.get("props", {}).get("pageProps", {})
    value = page_props.get(key)
    return value if isinstance(value, dict) else None


def extract_winstall_downloads(html: str, base_url: str) -> list[WinstallDownload]:
    return extract_winstall_page_links(html, base_url).downloads


def extract_winstall_page_links(html: str, base_url: str) -> WinstallPageLinks:
    parser = HTMLParser(html)
    downloads: dict[str, WinstallDownload] = {}
    official_url: str | None = None
    source_code_url: str | None = None

    for node in parser.css("a"):
        href = node.attributes.get("href")
        if not href:
            continue
        label = node.text(separator=" ", strip=True)
        text = normalize_text(f"{label} {href}")
        try:
            url = urljoin(base_url, href)
            parsed_url = urlparse(url)
            parsed_base = urlparse(base_url)
        except ValueError:
            continue
        if official_url is None and ("view site" in text or "sitio" in text):
            official_url = url
            continue
        if source_code_url is None and "source code" in text:
            source_code_url = url
            continue
        if (
            parsed_url.netloc.lower() == parsed_base.netloc.lower()
            and parsed_url.path.startswith("/apps/")
            and "download" not in normalize_text(label)
        ):
            continue
        if "download" not in text and not detect_extension(href):
            continue
        downloads.setdefault(
            url,
            WinstallDownload(url=url, label=label or None, context=node.html[:500]),
        )

    return WinstallPageLinks(
        official_url=official_url,
        source_code_url=source_code_url,
        downloads=list(downloads.values()),
    )


def parse_winstall_app(payload: dict[str, Any]) -> WinstallApp:
    versions = [
        WinstallVersion(
            version=item.get("version"),
            installer_type=item.get("installerType"),
            installers=[url for url in item.get("installers", []) if isinstance(url, str)],
        )
        for item in payload.get("versions", [])
        if isinstance(item, dict)
    ]
    package_id = payload.get("_id") or payload.get("id") or payload.get("packageIdentifier")
    if not package_id:
        raise ValueError("Winstall app payload has no package id")
    return WinstallApp(
        package_id=str(package_id),
        name=str(payload.get("name") or package_id),
        description=payload.get("desc") or payload.get("description"),
        publisher=payload.get("publisher"),
        homepage=payload.get("homepage"),
        icon=payload.get("icon"),
        icon_url=payload.get("iconUrl"),
        latest_version=payload.get("latestVersion"),
        tags=[tag for tag in payload.get("tags", []) if isinstance(tag, str)],
        versions=versions,
        raw=payload,
    )
