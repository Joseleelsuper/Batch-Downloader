"""Implementa las responsabilidades del módulo `winstall`.
"""
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
from app.core.cpu_pool import run_cpu_bound
from app.scraper.candidates import detect_extension
from app.scraper.text import normalize_text


@dataclass(frozen=True)
class WinstallVersion:
    """Representa el componente `WinstallVersion`.
    """
    version: str | None
    """Atributo de clase `version` de `WinstallVersion`.
    """
    installer_type: str | None
    """Atributo de clase `installer_type` de `WinstallVersion`.
    """
    installers: list[str] = field(default_factory=list)
    """Atributo de clase `installers` de `WinstallVersion`.
    """


@dataclass(frozen=True)
class WinstallDownload:
    """Representa el componente `WinstallDownload`.
    """
    url: str
    """Atributo de clase `url` de `WinstallDownload`.
    """
    label: str | None = None
    """Atributo de clase `label` de `WinstallDownload`.
    """
    context: str | None = None
    """Atributo de clase `context` de `WinstallDownload`.
    """


@dataclass(frozen=True)
class WinstallPageLinks:
    """Representa el componente `WinstallPageLinks`.
    """
    official_url: str | None
    """Atributo de clase `official_url` de `WinstallPageLinks`.
    """
    source_code_url: str | None
    """Atributo de clase `source_code_url` de `WinstallPageLinks`.
    """
    downloads: list[WinstallDownload]
    """Atributo de clase `downloads` de `WinstallPageLinks`.
    """


@dataclass(frozen=True)
class WinstallApp:
    """Representa el componente `WinstallApp`.
    """
    package_id: str
    """Atributo de clase `package_id` de `WinstallApp`.
    """
    name: str
    """Atributo de clase `name` de `WinstallApp`.
    """
    description: str | None
    """Atributo de clase `description` de `WinstallApp`.
    """
    publisher: str | None
    """Atributo de clase `publisher` de `WinstallApp`.
    """
    homepage: str | None
    """Atributo de clase `homepage` de `WinstallApp`.
    """
    icon: str | None
    """Atributo de clase `icon` de `WinstallApp`.
    """
    icon_url: str | None
    """Atributo de clase `icon_url` de `WinstallApp`.
    """
    latest_version: str | None
    """Atributo de clase `latest_version` de `WinstallApp`.
    """
    tags: list[str]
    """Atributo de clase `tags` de `WinstallApp`.
    """
    versions: list[WinstallVersion]
    """Atributo de clase `versions` de `WinstallApp`.
    """
    raw: dict[str, Any]
    """Atributo de clase `raw` de `WinstallApp`.
    """

    @property
    def installer_urls(self) -> list[str]:
        """Ejecuta `installer_urls` dentro de `WinstallApp`.

        Returns:
            list[str]: Colección de elementos obtenidos por la operación.
        """
        urls: list[str] = []
        for version in self.versions:
            urls.extend(version.installers)
        return list(dict.fromkeys(urls))


WINSTALL_CATALOG_PAGE_SIZE = 60
"""Constante que define `WINSTALL_CATALOG_PAGE_SIZE`.
"""


class WinstallClient:
    """Encapsula la comunicación con `Winstall`.
    """
    provider_name = "winstall"
    """Atributo de clase `provider_name` de `WinstallClient`.
    """

    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        """Inicializa una instancia de `WinstallClient`.

        Args:
            settings (Settings): Configuración del servicio.
            client (httpx.AsyncClient | None): Cliente utilizado para ejecutar el escenario.
        """
        self.settings = settings
        """Estado de instancia asociado a `settings`.
        """
        self._client = client
        """Estado de instancia asociado a `_client`.
        """

    async def __aenter__(self) -> WinstallClient:
        """Abre el contexto asíncrono y devuelve la instancia preparada.

        Returns:
            WinstallClient: Resultado producido por la operación.
        """
        if self._client is None:
            self._client = httpx.AsyncClient(
                timeout=self.settings.request_timeout_seconds,
                follow_redirects=True,
                headers={"User-Agent": "BatchDownloaderScraper/0.1"},
            )
        return self

    async def __aexit__(self, *args) -> None:
        """Cierra el contexto asíncrono y libera sus recursos.

        Args:
            *args (Any): Valor de `args` utilizado por la operación.
        """
        if self._client:
            await self._client.aclose()
            self._client = None

    async def iter_apps(self) -> AsyncIterator[WinstallApp]:
        """Ejecuta `iter_apps` dentro de `WinstallClient`.

        Yields:
            AsyncIterator[WinstallApp]: Elemento producido por la operación.
        """
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
        """Obtiene la operación `app`.

        Args:
            package_id (str): Identificador de `package` utilizado por la operación.

        Returns:
            WinstallApp: Resultado de `get_app`.

        Throws:
            LookupError: Si no existe el elemento solicitado.
        """
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
        """Obtiene la operación `downloads`.

        Args:
            package_id (str): Identificador de `package` utilizado por la operación.

        Returns:
            list[WinstallDownload]: Colección de elementos obtenidos por la operación.
        """
        links = await self.get_page_links(package_id)
        return links.downloads

    async def get_page_links(self, package_id: str) -> WinstallPageLinks:
        """Obtiene la operación `page_links`.

        Args:
            package_id (str): Identificador de `package` utilizado por la operación.

        Returns:
            WinstallPageLinks: Resultado de `get_page_links`.
        """
        assert self._client is not None
        response = await self._client.get(f"{self.settings.winstall_base_url}/apps/{package_id}")
        if not response.is_success:
            return WinstallPageLinks(official_url=None, source_code_url=None, downloads=[])
        return await run_cpu_bound(
            extract_winstall_page_links,
            response.text,
            f"{self.settings.winstall_base_url}/apps/{package_id}",
        )

    @retry(wait=wait_exponential(multiplier=0.5, min=0.5, max=4), stop=stop_after_attempt(3))
    async def _fetch_catalog_page(self, offset: int, limit: int) -> dict[str, Any] | None:
        """Ejecuta el paso interno `_fetch_catalog_page`.

        Args:
            offset (int): Valor de `offset` utilizado por la operación.
            limit (int): Número máximo de elementos que se recuperarán.

        Returns:
            dict[str, Any] | None: Mapa con los datos producidos por la operación.
        """
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
        """Ejecuta el paso interno `_fetch_app`.

        Args:
            package_id (str): Identificador de `package` utilizado por la operación.

        Returns:
            dict[str, Any] | None: Mapa con los datos producidos por la operación.
        """
        assert self._client is not None
        response = await self._client.get(
            f"{self.settings.winstall_api_base_url}/apps/{package_id}"
        )
        if response.status_code >= 500:
            response.raise_for_status()
        if not response.is_success:
            return None
        return response.json()

    async def _fetch_catalog_from_next_data(self) -> dict[str, Any] | None:
        """Ejecuta el paso interno `_fetch_catalog_from_next_data`.

        Returns:
            dict[str, Any] | None: Mapa con los datos producidos por la operación.
        """
        assert self._client is not None
        response = await self._client.get(f"{self.settings.winstall_base_url}/apps")
        if not response.is_success:
            return None
        return await run_cpu_bound(extract_next_data, response.text, "data")

    async def _fetch_app_from_page(self, package_id: str) -> dict[str, Any] | None:
        """Ejecuta el paso interno `_fetch_app_from_page`.

        Args:
            package_id (str): Identificador de `package` utilizado por la operación.

        Returns:
            dict[str, Any] | None: Mapa con los datos producidos por la operación.
        """
        assert self._client is not None
        response = await self._client.get(f"{self.settings.winstall_base_url}/apps/{package_id}")
        if not response.is_success:
            return None
        return await run_cpu_bound(extract_next_data, response.text, "app")


def extract_next_data(html: str, key: str) -> dict[str, Any] | None:
    """Ejecuta la operación `extract_next_data`.

    Args:
        html (str): Valor de `html` utilizado por la operación.
        key (str): Valor de `key` utilizado por la operación.

    Returns:
        dict[str, Any] | None: Mapa con los datos producidos por la operación.
    """
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
    """Ejecuta la operación `extract_winstall_downloads`.

    Args:
        html (str): Valor de `html` utilizado por la operación.
        base_url (str): Dirección de `base` que debe procesarse.

    Returns:
        list[WinstallDownload]: Colección de elementos obtenidos por la operación.
    """
    return extract_winstall_page_links(html, base_url).downloads


def extract_winstall_page_links(html: str, base_url: str) -> WinstallPageLinks:
    """Ejecuta la operación `extract_winstall_page_links`.

    Args:
        html (str): Valor de `html` utilizado por la operación.
        base_url (str): Dirección de `base` que debe procesarse.

    Returns:
        WinstallPageLinks: Resultado producido por la operación.
    """
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
            WinstallDownload(
                url=url,
                label=label or None,
                context=(node.html or "")[:500],
            ),
        )

    return WinstallPageLinks(
        official_url=official_url,
        source_code_url=source_code_url,
        downloads=list(downloads.values()),
    )


def parse_winstall_app(payload: dict[str, Any]) -> WinstallApp:
    """Analiza la operación `winstall_app`.

    Args:
        payload (dict[str, Any]): Carga de datos recibida por la operación.

    Returns:
        WinstallApp: Resultado producido por la operación.

    Throws:
        ValueError: Si los datos recibidos no cumplen las restricciones requeridas.
    """
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
