"""Implementa las responsabilidades del módulo `winstall`.
"""
from __future__ import annotations

import hashlib
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
    installer_data_complete: bool = False
    """Indica si el proveedor entregó explícitamente las listas de instaladores."""

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


# La API acepta lotes amplios. Reducir los viajes de red hace que las dos lecturas
# necesarias para estabilizar el conjunto no prolonguen innecesariamente cada run.
WINSTALL_CATALOG_PAGE_SIZE = 500
"""Constante que define `WINSTALL_CATALOG_PAGE_SIZE`.
"""
WINSTALL_CATALOG_STABILITY_PASSES = 2
"""Número de instantáneas idénticas exigidas antes de procesar el catálogo."""
WINSTALL_CATALOG_MAX_ATTEMPTS = 3
"""Número máximo de intentos para obtener una instantánea estable."""


class WinstallProviderError(RuntimeError):
    """Representa un fallo recuperable o incompleto del proveedor Winstall."""


class WinstallCatalogIncompleteError(WinstallProviderError):
    """Indica que una pasada del catálogo no coincide con el total anunciado."""


class WinstallCatalogUnstableError(WinstallProviderError):
    """Indica que no fue posible obtener dos instantáneas consecutivas idénticas."""


class WinstallDetailIncompleteError(WinstallProviderError):
    """Indica que no se obtuvo un detalle con campos de instaladores autoritativos."""


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
        for app in await self.catalog_snapshot():
            yield app

    async def catalog_snapshot(
        self,
        *,
        stability_passes: int = WINSTALL_CATALOG_STABILITY_PASSES,
        max_attempts: int = WINSTALL_CATALOG_MAX_ATTEMPTS,
    ) -> list[WinstallApp]:
        """Obtiene un conjunto completo y estable de aplicaciones de Winstall.

        La API usa paginación por desplazamiento y no ofrece un cursor de snapshot. Para
        evitar omisiones silenciosas mientras cambia el catálogo, se exigen pasadas
        consecutivas idénticas antes de entregar trabajo al pipeline.
        """
        if stability_passes < 1:
            raise ValueError("stability_passes_must_be_positive")
        if max_attempts < stability_passes:
            raise ValueError("max_attempts_must_cover_stability_passes")

        previous_ids: frozenset[str] | None = None
        stable_count = 0
        latest_apps: list[WinstallApp] = []
        diagnostics: list[str] = []
        for _attempt in range(max_attempts):
            try:
                raw_apps = await self._fetch_complete_catalog_once()
            except Exception as exc:  # noqa: BLE001 - cada pasada es un intento estable
                diagnostics.append(exc.__class__.__name__)
                previous_ids = None
                stable_count = 0
                continue

            latest_apps = [parse_winstall_app(item) for item in raw_apps]
            current_ids = frozenset(app.package_id for app in latest_apps)
            if current_ids == previous_ids:
                stable_count += 1
            else:
                stable_count = 1
            previous_ids = current_ids
            if stable_count >= stability_passes:
                return latest_apps

        detail = diagnostics[-1] if diagnostics else "catalog_changed_between_passes"
        raise WinstallCatalogUnstableError(
            f"Winstall catalog did not stabilize after {max_attempts} attempts: {detail}"
        )

    async def _fetch_complete_catalog_once(self) -> list[dict[str, Any]]:
        """Recupera una pasada completa y valida totales, offsets e identificadores."""
        offset = 0
        announced_total: int | None = None
        rows: list[dict[str, Any]] = []
        while announced_total is None or offset < announced_total:
            payload = await self._fetch_catalog_page(offset, WINSTALL_CATALOG_PAGE_SIZE)
            if payload is None and offset == 0:
                payload = await self._fetch_catalog_from_next_data()
            if not isinstance(payload, dict):
                raise WinstallCatalogIncompleteError(
                    f"catalog_page_unavailable offset={offset}"
                )

            try:
                page_total = int(payload["total"])
            except (KeyError, TypeError, ValueError) as exc:
                raise WinstallCatalogIncompleteError(
                    f"catalog_total_invalid offset={offset}"
                ) from exc
            if page_total < 0:
                raise WinstallCatalogIncompleteError("catalog_total_negative")
            if announced_total is None:
                announced_total = page_total
            elif page_total != announced_total:
                raise WinstallCatalogIncompleteError(
                    f"catalog_total_changed expected={announced_total} actual={page_total}"
                )

            payload_offset = payload.get("offset", offset)
            try:
                normalized_offset = int(payload_offset)
            except (TypeError, ValueError) as exc:
                raise WinstallCatalogIncompleteError(
                    f"catalog_offset_invalid expected={offset}"
                ) from exc
            if normalized_offset != offset:
                raise WinstallCatalogIncompleteError(
                    f"catalog_offset_mismatch expected={offset} actual={normalized_offset}"
                )

            data = payload.get("data")
            if not isinstance(data, list):
                raise WinstallCatalogIncompleteError(
                    f"catalog_data_invalid offset={offset}"
                )
            if not data and offset < announced_total:
                raise WinstallCatalogIncompleteError(
                    f"catalog_ended_early offset={offset} total={announced_total}"
                )
            if any(not isinstance(item, dict) for item in data):
                raise WinstallCatalogIncompleteError(
                    f"catalog_item_invalid offset={offset}"
                )
            rows.extend(data)
            offset += len(data)

        if announced_total is None:
            raise WinstallCatalogIncompleteError("catalog_total_missing")
        package_ids = [package_id_from_payload(item) for item in rows]
        if any(package_id is None for package_id in package_ids):
            raise WinstallCatalogIncompleteError("catalog_item_without_package_id")
        normalized_ids = [str(package_id) for package_id in package_ids]
        if len(normalized_ids) != announced_total:
            raise WinstallCatalogIncompleteError(
                f"catalog_count_mismatch expected={announced_total} actual={len(normalized_ids)}"
            )
        if len(set(normalized_ids)) != len(normalized_ids):
            raise WinstallCatalogIncompleteError("catalog_contains_duplicate_ids")
        return rows

    async def get_app(self, package_id: str) -> WinstallApp:
        """Obtiene la operación `app`.

        Args:
            package_id (str): Identificador de `package` utilizado por la operación.

        Returns:
            WinstallApp: Resultado de `get_app`.

        Throws:
            LookupError: Si no existe el elemento solicitado.
        """
        diagnostics: list[str] = []
        for loader in (self._fetch_app, self._fetch_app_from_search):
            try:
                payload = await loader(package_id)
            except Exception as exc:  # noqa: BLE001 - conserva el fallback del proveedor
                diagnostics.append(exc.__class__.__name__)
                continue
            if not payload:
                continue
            app = parse_winstall_app(payload)
            if app.package_id != package_id:
                diagnostics.append("package_id_mismatch")
                continue
            if app.installer_data_complete:
                return app
            diagnostics.append("installer_fields_missing")

        # El HTML actual conserva metadatos y versiones, pero elimina `installers[]`.
        # Se consulta solo para distinguir una página existente de un 404 y nunca se
        # acepta como evidencia negativa de instaladores.
        try:
            page_payload = await self._fetch_app_from_page(package_id)
        except Exception as exc:  # noqa: BLE001 - se informa como proveedor incompleto
            diagnostics.append(exc.__class__.__name__)
            page_payload = None
        if page_payload:
            diagnostics.append("html_detail_is_slim")
        reason = ",".join(dict.fromkeys(diagnostics)) or "detail_unavailable"
        raise WinstallDetailIncompleteError(
            f"Winstall detail incomplete for {package_id}: {reason}"
        )

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
        if response.status_code in {408, 425, 429} or response.status_code >= 500:
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
        if response.status_code in {408, 425, 429} or response.status_code >= 500:
            response.raise_for_status()
        if not response.is_success:
            return None
        return response.json()

    @retry(wait=wait_exponential(multiplier=0.5, min=0.5, max=4), stop=stop_after_attempt(3))
    async def _fetch_app_from_search(self, package_id: str) -> dict[str, Any] | None:
        """Busca un detalle completo y exige coincidencia exacta de package ID."""
        assert self._client is not None
        response = await self._client.get(
            f"{self.settings.winstall_api_base_url}/apps/search",
            params={"q": package_id, "offset": 0, "limit": WINSTALL_CATALOG_PAGE_SIZE},
        )
        if response.status_code in {408, 425, 429} or response.status_code >= 500:
            response.raise_for_status()
        if not response.is_success:
            return None
        payload = response.json()
        data = payload.get("data") if isinstance(payload, dict) else None
        if not isinstance(data, list):
            return None
        for item in data:
            if not isinstance(item, dict):
                continue
            if package_id_from_payload(item) == package_id:
                return item
        return None

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
    raw_versions = payload.get("versions")
    installer_data_complete = isinstance(raw_versions, list) and all(
        isinstance(item, dict)
        and "installers" in item
        and isinstance(item.get("installers"), list)
        for item in raw_versions
    )
    versions = [
        WinstallVersion(
            version=item.get("version"),
            installer_type=item.get("installerType"),
            installers=[url for url in item.get("installers", []) if isinstance(url, str)],
        )
        for item in raw_versions or []
        if isinstance(item, dict)
    ]
    package_id = package_id_from_payload(payload)
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
        installer_data_complete=installer_data_complete,
    )


def package_id_from_payload(payload: dict[str, Any]) -> str | None:
    """Extrae el identificador canónico admitido por las variantes del API."""
    value = payload.get("_id") or payload.get("id") or payload.get("packageIdentifier")
    return str(value) if value else None


def winstall_summary_fingerprint(app: WinstallApp) -> str:
    """Calcula una huella estable de los campos ligeros que anuncian cambios."""
    payload = {
        "package_id": app.package_id,
        "latest_version": app.latest_version,
        "updated_at": app.raw.get("updatedAt"),
    }
    return _winstall_fingerprint(payload)


def winstall_detail_fingerprint(app: WinstallApp) -> str:
    """Calcula una huella estable del detalle y sus URLs de instalador."""
    payload = {
        "package_id": app.package_id,
        "latest_version": app.latest_version,
        "updated_at": app.raw.get("updatedAt"),
        "homepage": app.homepage,
        "versions": [
            {
                "version": version.version,
                "installer_type": version.installer_type,
                "installers": sorted(version.installers),
            }
            for version in app.versions
        ],
    }
    return _winstall_fingerprint(payload)


def _winstall_fingerprint(payload: dict[str, Any]) -> str:
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()
