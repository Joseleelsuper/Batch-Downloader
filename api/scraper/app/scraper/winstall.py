"""Integra el catálogo y los detalles de Winstall con comprobaciones de integridad, estabilidad y
procedencia de instaladores.

See Also:
    app.scraper.installer_policy: Decide cuándo los datos Winstall sustituyen o complementan
        la exploración oficial.
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
    """Agrupa la versión anunciada por Winstall con su tipo y las URLs de instaladores
    declaradas.

    Attributes:
        version: Etiqueta de versión del grupo, si el proveedor la anuncia.
        installer_type: Tipo de instalador indicado por Winstall.
        installers: URLs asociadas a esa versión; una lista vacía expresa que faltan datos.
    """

    version: str | None

    installer_type: str | None

    installers: list[str] = field(default_factory=list)



@dataclass(frozen=True)
class WinstallDownload:
    """Conserva un enlace de descarga extraído de la página pública de Winstall y la evidencia
    que lo acompaña.

    Attributes:
        url: Destino absoluto o resuelto del enlace.
        label: Texto visible del enlace, si existe.
        context: Fragmento HTML limitado usado como evidencia de procedencia.
    """

    url: str

    label: str | None = None

    context: str | None = None



@dataclass(frozen=True)
class WinstallPageLinks:
    """Resultado de analizar una ficha HTML de Winstall separando enlaces oficiales, código
    fuente y descargas.

    Attributes:
        official_url: Página oficial declarada por el proveedor.
        source_code_url: Repositorio o código fuente enlazado.
        downloads: Descargas deduplicadas en el orden de aparición.
    """

    official_url: str | None

    source_code_url: str | None

    downloads: list[WinstallDownload]



@dataclass(frozen=True)
class WinstallApp:
    """Modelo normalizado de una aplicación Winstall, incluyendo versiones, metadatos y el
    payload original.

    Attributes:
        package_id: Identidad canónica usada para solicitar el detalle exacto.
        versions: Versiones y sus instaladores declarados.
        raw: Payload original para campos que el modelo no proyecta.
        installer_data_complete: Indica si todas las versiones aportaron una lista installers
            explícita.

    See Also:
        installer_urls: Aplana las URLs de todas las versiones.
    """

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

    installer_data_complete: bool = False
    """Indica si el proveedor entregó explícitamente las listas de instaladores."""

    @property
    def installer_urls(self) -> list[str]:
        """Aplana las URLs de instaladores de las versiones y elimina duplicados conservando
        orden.

        Returns:
            lista de URLs declaradas por Winstall.
        """
        urls: list[str] = []
        for version in self.versions:
            urls.extend(version.installers)
        return list(dict.fromkeys(urls))


# La API acepta lotes amplios. Reducir los viajes de red hace que las dos lecturas
# necesarias para estabilizar el conjunto no prolonguen innecesariamente cada run.
WINSTALL_CATALOG_PAGE_SIZE = 500

WINSTALL_CATALOG_STABILITY_PASSES = 2
"""Número de instantáneas idénticas exigidas antes de procesar el catálogo."""
WINSTALL_CATALOG_MAX_ATTEMPTS = 3
"""Número máximo de intentos para obtener una instantánea estable."""


class WinstallProviderError(RuntimeError):
    """Base de los fallos que impiden confiar en una lectura de Winstall como completa o
    utilizable.
    """


class WinstallCatalogIncompleteError(WinstallProviderError):
    """Indica que una página del catálogo carece de total, datos, offsets o identificadores
    consistentes.
    """


class WinstallCatalogUnstableError(WinstallProviderError):
    """Indica que el conjunto de identificadores del catálogo cambió antes de alcanzar las
    pasadas estables exigidas.
    """


class WinstallDetailIncompleteError(WinstallProviderError):
    """Indica que el detalle encontrado no contiene instaladores autoritativos para el package ID
    solicitado.
    """


class WinstallClient:
    """Gestiona las peticiones asíncronas a Winstall y transforma respuestas parciales en modelos
    verificables.
    """

    provider_name = "winstall"


    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        """Configura el cliente con los endpoints y timeout del servicio y permite inyectar una
        sesión HTTP.

        Args:
            settings: Configuración de red y límites del servicio.
            client: Cliente HTTP reutilizable; None permite crear uno gestionado por el
                contexto.
        """
        self.settings = settings

        self._client = client


    async def __aenter__(self) -> WinstallClient:
        """Abre o reutiliza la sesión HTTP y devuelve el cliente listo para consultar Winstall.

        Returns:
            esta instancia con una sesión activa.
        """
        if self._client is None:
            self._client = httpx.AsyncClient(
                timeout=self.settings.request_timeout_seconds,
                follow_redirects=True,
                headers={"User-Agent": "BatchDownloaderScraper/0.1"},
            )
        return self

    async def __aexit__(self, *args) -> None:
        """Cierra la sesión HTTP creada o inyectada y elimina la referencia para impedir su
        reutilización accidental.

        Args:
            args: Argumentos del contexto asíncrono de salida, ignorados por la
                implementación.
        """
        if self._client:
            await self._client.aclose()
            self._client = None

    async def iter_apps(self) -> AsyncIterator[WinstallApp]:
        """Itera las aplicaciones de una instantánea completa y estable del catálogo.

        Yields:
            cada WinstallApp aceptada por catalog_snapshot.
        """
        for app in await self.catalog_snapshot():
            yield app

    async def catalog_snapshot(
        self,
        *,
        stability_passes: int = WINSTALL_CATALOG_STABILITY_PASSES,
        max_attempts: int = WINSTALL_CATALOG_MAX_ATTEMPTS,
    ) -> list[WinstallApp]:
        """Obtiene páginas completas del catálogo y solo devuelve una pasada cuando el conjunto
        de package IDs permanece igual durante las pasadas requeridas.
        Los fallos o cambios reinician la estabilidad y se convierten en
        WinstallCatalogUnstableError tras agotar intentos.

        Args:
            stability_passes: Número de pasadas consecutivas con el mismo conjunto de
                paquetes.
            max_attempts: Máximo de pasadas completas antes de declarar inestable el catálogo.

        Returns:
            aplicaciones normalizadas de una instantánea estable.

        Raises:
            ValueError: Si los límites de estabilidad no son positivos o no caben en los
                intentos.
            WinstallCatalogUnstableError: Si no se obtiene una instantánea estable.
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
        """Recorre todas las páginas anunciadas, valida total y offset y rechaza filas sin
        package ID o duplicadas.

        Returns:
            payloads de aplicación completos en orden de catálogo.

        Raises:
            WinstallCatalogIncompleteError: Si el proveedor omite o contradice datos
                necesarios.
        """
        offset = 0
        announced_total: int | None = None
        rows: list[dict[str, Any]] = []
        while announced_total is None or offset < announced_total:
            payload = await self._fetch_catalog_page(offset, WINSTALL_CATALOG_PAGE_SIZE)
            if payload is None and offset == 0:
                payload = await self._fetch_catalog_from_next_data()
            if not isinstance(payload, dict):
                raise WinstallCatalogIncompleteError(f"catalog_page_unavailable offset={offset}")

            announced_total, data = validate_catalog_page(payload, offset, announced_total)
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
        """Solicita el detalle exacto por API y búsqueda, exige coincidencia de package ID y solo
        acepta versiones con installers completos; la página HTML se usa como diagnóstico.

        Args:
            package_id: Identificador exacto del paquete en Winstall.

        Returns:
            WinstallApp con datos de instaladores autoritativos.

        Raises:
            WinstallDetailIncompleteError: Si ninguna vía ofrece un detalle completo.
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
        """Obtiene los enlaces de descarga de la página pública del paquete.

        Args:
            package_id: Identificador exacto del paquete en Winstall.

        Returns:
            descargas extraídas y deduplicadas.
        """
        links = await self.get_page_links(package_id)
        return links.downloads

    async def get_page_links(self, package_id: str) -> WinstallPageLinks:
        """Descarga la ficha HTML del paquete y delega el análisis CPU-bound; una respuesta no
        satisfactoria produce enlaces vacíos.

        Args:
            package_id: Identificador exacto del paquete en Winstall.

        Returns:
            enlaces oficiales, código fuente y descargas de la ficha.
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
        """Solicita una página JSON del catálogo y reintenta respuestas transitorias; los errores
        no recuperables devuelven None.

        Args:
            offset: Desplazamiento de la página dentro del catálogo.
            limit: Número máximo de elementos que se solicita al proveedor.

        Returns:
            payload JSON o None.
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
        """Solicita el detalle JSON exacto de un paquete y reintenta límites o errores de
        servidor.

        Args:
            package_id: Identificador exacto del paquete en Winstall.

        Returns:
            payload de aplicación o None.
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
        """Busca el paquete y devuelve únicamente la fila cuyo identificador coincide
        exactamente.

        Args:
            package_id: Identificador exacto del paquete en Winstall.

        Returns:
            payload coincidente o None.
        """
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
        """Usa el bloque __NEXT_DATA__ de la página de catálogo como fallback cuando la API no
        devuelve la primera página.

        Returns:
            payload de catálogo o None.
        """
        assert self._client is not None
        response = await self._client.get(f"{self.settings.winstall_base_url}/apps")
        if not response.is_success:
            return None
        return await run_cpu_bound(extract_next_data, response.text, "data")

    async def _fetch_app_from_page(self, package_id: str) -> dict[str, Any] | None:
        """Lee el bloque __NEXT_DATA__ de la ficha HTML para diagnosticar si existe una
        aplicación aunque falten instaladores.

        Args:
            package_id: Identificador exacto del paquete en Winstall.

        Returns:
            payload de detalle o None.
        """
        assert self._client is not None
        response = await self._client.get(f"{self.settings.winstall_base_url}/apps/{package_id}")
        if not response.is_success:
            return None
        return await run_cpu_bound(extract_next_data, response.text, "app")


def extract_next_data(html: str, key: str) -> dict[str, Any] | None:
    """Parsea __NEXT_DATA__ y devuelve una propiedad de pageProps solo cuando su valor es un
    mapa.

    Args:
        html: Documento HTML del que se extraen enlaces de descarga.
        key: Nombre de la propiedad dentro de pageProps.

    Returns:
        diccionario solicitado o None ante HTML, JSON o estructura inválidos.
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
    """Extrae únicamente la lista de descargas de una ficha Winstall.

    Args:
        html: Documento HTML del que se extraen enlaces de descarga.
        base_url: Página base usada para resolver enlaces relativos.

    Returns:
        descargas deduplicadas de extract_winstall_page_links.
    """
    return extract_winstall_page_links(html, base_url).downloads


def extract_winstall_page_links(html: str, base_url: str) -> WinstallPageLinks:
    """Recorre anchors, identifica sitio oficial y código fuente y conserva enlaces con intención
    de descarga o extensión conocida; excluye navegación interna y deduplica por URL.

    Args:
        html: Documento HTML del que se extraen enlaces de descarga.
        base_url: Página base usada para resolver enlaces relativos.

    Returns:
        WinstallPageLinks con el orden visible del HTML.
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
    """Convierte un payload Winstall en el modelo normalizado y marca incompleto el detalle que
    no aporta installers como listas explícitas.

    Args:
        payload: Mapa recibido del proveedor.

    Returns:
        WinstallApp normalizada.

    Raises:
        ValueError: Si falta el identificador de paquete.
    """
    raw_versions = payload.get("versions")
    installer_data_complete = isinstance(raw_versions, list) and all(
        isinstance(item, dict) and "installers" in item and isinstance(item.get("installers"), list)
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
    """Lee el identificador canónico probando _id, id y packageIdentifier.

    Args:
        payload: Mapa recibido del proveedor.

    Returns:
        identificador como texto o None.
    """
    value = payload.get("_id") or payload.get("id") or payload.get("packageIdentifier")
    return str(value) if value else None


def winstall_summary_fingerprint(app: WinstallApp) -> str:
    """Calcula una huella estable de identidad, última versión y fecha de actualización para
    detectar cambios ligeros.

    Args:
        app: Aplicación Winstall normalizada.

    Returns:
        SHA-256 hexadecimal.
    """
    payload = {
        "package_id": app.package_id,
        "latest_version": app.latest_version,
        "updated_at": app.raw.get("updatedAt"),
    }
    return _winstall_fingerprint(payload)


def winstall_detail_fingerprint(app: WinstallApp) -> str:
    """Calcula una huella estable del detalle, versiones, tipos y URLs de instalador.

    Args:
        app: Aplicación Winstall normalizada.

    Returns:
        SHA-256 hexadecimal.
    """
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
    """Serializa un mapa ordenado y calcula su SHA-256 UTF-8 para comparar snapshots
    reproducibles.

    Args:
        payload: Mapa recibido del proveedor.

    Returns:
        huella hexadecimal.
    """
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def validate_catalog_page(
    payload: dict[str, Any], offset: int, announced_total: int | None
) -> tuple[int, list[dict[str, Any]]]:
    """Comprueba que total, offset y data de una página siguen el contrato y que el catálogo no
    termina antes de lo anunciado.

    Args:
        payload: Mapa recibido del proveedor.
        offset: Desplazamiento de la página dentro del catálogo.
        announced_total: Total anunciado por una página anterior, o None en la primera página.

    Returns:
        total anunciado y filas de aplicación.

    Raises:
        WinstallCatalogIncompleteError: Ante total inválido, cambio de total, offset
            incorrecto, datos vacíos o filas no objeto.
    """
    try:
        page_total = int(payload["total"])
    except (KeyError, TypeError, ValueError) as exc:
        raise WinstallCatalogIncompleteError(f"catalog_total_invalid offset={offset}") from exc
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
        raise WinstallCatalogIncompleteError(f"catalog_offset_invalid expected={offset}") from exc
    if normalized_offset != offset:
        raise WinstallCatalogIncompleteError(
            f"catalog_offset_mismatch expected={offset} actual={normalized_offset}"
        )

    data = payload.get("data")
    if not isinstance(data, list):
        raise WinstallCatalogIncompleteError(f"catalog_data_invalid offset={offset}")
    if not data and offset < announced_total:
        raise WinstallCatalogIncompleteError(
            f"catalog_ended_early offset={offset} total={announced_total}"
        )
    if any(not isinstance(item, dict) for item in data):
        raise WinstallCatalogIncompleteError(f"catalog_item_invalid offset={offset}")
    return announced_total, data
