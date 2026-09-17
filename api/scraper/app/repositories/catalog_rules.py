"""Comparte reglas de proyección, identidad de artefactos y procedencia editorial entre consultas
y almacenes del catálogo.
"""

import hashlib
import json
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from urllib.parse import urlparse

import tldextract
from sqlalchemy import Boolean, literal_column
from sqlalchemy.orm import selectinload, with_expression

from app.db.enums import (
    ResolutionStatus,
    ValidationStatus,
)
from app.db.models import (
    DownloadSource,
    ResolvedSource,
    SoftwareApp,
)
from app.scraper.text import normalize_text
from app.scraper.winstall import (
    WinstallApp,
)

AVAILABLE_RESOLUTION_STATUSES = {
    ResolutionStatus.DIRECT.value,
    ResolutionStatus.FALLBACK.value,
}





CATALOG_DOWNLOADABLE_COLUMN = literal_column(
    "resolved_sources.catalog_downloadable",
    Boolean,
)





@dataclass(frozen=True)
class ResolvedSourceCreate:
    """Reúne datos de un instalador comprobado antes de persistir o renovar la resolución
    identificada por su huella.

    Attributes:
        source_id, url, final_domain: Fuente propietaria, URL privada de entrada y dominio
            publicable.
        filename, extension, content_type, size_bytes: Nombre, formato, MIME y bytes
            conocidos; None expresa ausencia de dato.
        version, release_rank, is_latest, version_status: Versión del instalador y posición
            respecto de sus publicaciones.
        score, status, validation_status: Preferencia y resultados de resolución y validación.
        metadata, artifact_fingerprint: Evidencias opcionales y huella precomputada; si falta
            se deriva al guardar.

    See Also:
        app.repositories.catalog_sources.CatalogSourceStore.save_resolved_source: Mantiene el
            UUID cuando reconoce la misma fuente y huella.
    """

    source_id: uuid.UUID

    url: str

    final_domain: str

    filename: str | None

    extension: str | None

    content_type: str | None

    size_bytes: int | None

    version: str | None

    score: int

    status: ResolutionStatus

    validation_status: ValidationStatus

    release_rank: int | None = None

    is_latest: bool = False

    version_status: str | None = None

    metadata: dict | None = None

    artifact_fingerprint: str | None = None
    """Huella aportada por un resolver especializado; se calcula si se omite."""


def _public_resolved_sources_loader():
    """Precarga fuentes y resoluciones por lotes e incorpora a cada resolución su expresión de
    disponibilidad materializada.

    Returns:
        opción SQLAlchemy para las consultas públicas de aplicaciones.
    """
    return (
        selectinload(SoftwareApp.sources)
        .selectinload(DownloadSource.resolved_sources)
        .options(
            with_expression(
                ResolvedSource.catalog_downloadable,
                CATALOG_DOWNLOADABLE_COLUMN,
            )
        )
    )


def has_icon_url(value: str | None) -> bool:
    """Descarta iconos ausentes, vacíos y el marcador de ausencia guion.

    Args:
        value: Texto o metadato opcional que se normaliza.

    Returns:
        True si existe un texto de URL utilizable por la política editorial.
    """
    return bool(value and value.strip() and value.strip() != "-")


def has_verified_binary_history(
    validation_status: str,
    resolution_status: str,
    metadata: dict,
) -> bool:
    """Reconoce historial de validación binaria directa o fallback y excluye metadatos de
    confianza solo atestiguada.

    Args:
        validation_status: Estado persistido de validación.
        resolution_status: Vía persistida de resolución.
        metadata: Evidencias persistidas de confianza y transporte de la resolución.

    Returns:
        True si los estados y evidencias permiten considerar comprobado el binario, sin
            comprobar caducidad.
    """
    confidence = str(metadata.get("validation_confidence") or "").lower()
    return (
        validation_status == ValidationStatus.VALID.value
        and resolution_status in {ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value}
        and confidence in {"", "validated", "verified"}
        and metadata.get("transport_security")
        not in {"https_winstall_edge_attested", "http_winstall_verified"}
    )


def is_github_homepage(value: str | None) -> bool:
    """Reconoce URL HTTPS cuyo netloc corresponde exactamente a github.com o www.github.com.

    Args:
        value: Texto o metadato opcional que se normaliza.

    Returns:
        True para esas páginas GitHub; no exige una ruta concreta de repositorio.
    """
    if not value:
        return False
    parsed = urlparse(value)
    return parsed.scheme == "https" and parsed.netloc.lower() in {"github.com", "www.github.com"}


def is_replaceable_github_icon(value: str | None) -> bool:
    """Permite reemplazar un icono ausente o servido por OpenGraph GitHub y sus subdominios.

    Args:
        value: Texto o metadato opcional que se normaliza.

    Returns:
        True si la sincronización puede sustituir ese icono.
    """
    if not has_icon_url(value):
        return True
    hostname = (urlparse(value or "").hostname or "").lower()
    return hostname == "opengraph.githubassets.com" or hostname.endswith(
        ".opengraph.githubassets.com"
    )


def has_current_available_installer(
    app: SoftwareApp,
    now: datetime | None = None,
) -> bool:
    # ``now`` permanece en la firma para llamadores anteriores a la proyección
    # persistente. El tiempo por sí solo ya no retira un candidato válido del catálogo.
    """Busca una fuente directa o fallback válida con al menos una resolución de historial
    binario verificado.
    La caducidad no interviene en esta comprobación de historial; el parámetro now se conserva
    por compatibilidad.

    Args:
        app: Aplicación con fuentes y resoluciones precargadas.
        now: Instante UTC sin tzinfo; None usa el reloj actual donde se permite.

    Returns:
        True si existe ese historial de instalador.
    """
    del now
    for source in app.sources:
        if source.resolution_status not in AVAILABLE_RESOLUTION_STATUSES:
            continue
        if source.validation_status != ValidationStatus.VALID.value:
            continue
        for resolved in source.resolved_sources:
            if resolved.status not in AVAILABLE_RESOLUTION_STATUSES:
                continue
            if has_verified_binary_history(
                resolved.validation_status,
                resolved.status,
                resolved.metadata_json or {},
            ):
                return True
    return False


def registered_domain(url: str | None) -> str | None:
    """Extrae dominio y sufijo público de una URL y omite subdominios para comparar procedencia.

    Args:
        url: URL de la que se obtiene el dominio registrado.

    Returns:
        dominio registrado en minúsculas o None si no puede determinarse.
    """
    if not url:
        return None
    extracted = tldextract.extract(url)
    if not extracted.domain or not extracted.suffix:
        return None
    return f"{extracted.domain}.{extracted.suffix}".lower()


def inferred_platform_for_resolved_source(resolved: ResolvedSource) -> str | None:
    """Infiere plataforma por nombre y etiqueta, después por metadatos guardados y por último por
    formato del archivo.

    Args:
        resolved: Resolución persistida cuyo instalador y fuente se comprueban.

    Returns:
        windows, macos, linux o None cuando la evidencia no basta.
    """
    metadata = resolved.metadata_json or {}
    platform_text = " ".join(
        str(value).lower()
        for value in (
            resolved.filename,
            metadata.get("candidate_label"),
        )
        if value
    )
    if any(token in platform_text for token in ("macos", "mac-os", "darwin", "apple-silicon")):
        return "macos"
    if any(token in platform_text for token in ("windows", "win32", "win64", "win-x64")):
        return "windows"
    if any(token in platform_text for token in ("linux", "ubuntu", "debian", "appimage")):
        return "linux"

    stored_platform = metadata.get("operating_system")
    if stored_platform in {"windows", "macos", "linux"}:
        return str(stored_platform)

    return platform_from_artifact(resolved.extension, resolved.filename)


def inferred_architecture_for_resolved_source(
    resolved: ResolvedSource,
    fallback: str,
) -> str:
    """Busca marcadores de ARM64, x86 y x86_64 en nombre, formato y evidencia de candidato, con
    ese orden de precedencia.

    Args:
        resolved: Resolución persistida cuyo instalador y fuente se comprueban.
        fallback: Arquitectura persistida usada si la evidencia no permite inferir otra.

    Returns:
        arquitectura inferida, respaldo recibido o UNKNOWN.
    """
    metadata = resolved.metadata_json or {}
    text = " ".join(
        str(value)
        for value in (
            resolved.filename,
            resolved.extension,
            metadata.get("candidate_label"),
            metadata.get("candidate_source"),
        )
        if value
    ).lower()
    if any(token in text for token in ("aarch64", "arm64", "apple silicon", "m1", "m2", "m3")):
        return "aarch64"
    if any(token in text for token in ("i386", "i686", "win32", "32-bit", "32bit")):
        return "x86"
    if any(token in text for token in ("x86_64", "amd64", "x64", "win64", "64-bit", "64bit")):
        return "x86_64"
    return fallback or "UNKNOWN"


def normalized_extension(value: str | None) -> str | None:
    """Elimina espacios exteriores, convierte a minúsculas y añade el punto inicial cuando falta.

    Args:
        value: Texto o metadato opcional que se normaliza.

    Returns:
        extensión normalizada; None si la entrada original es None o una cadena de longitud
            cero.
    """
    if not value:
        return None
    extension = value.lower().strip()
    return extension if extension.startswith(".") else f".{extension}"


def is_manual_download_source(source: DownloadSource) -> bool:
    """Reconoce fuentes del resolutor manual o marcadas con procedencia admin_manual.

    Args:
        source: Fuente que puede provenir de una inspección manual.

    Returns:
        True si la sincronización automática debe tratarlas como manuales.
    """
    return source.resolver_type == "manual_http" or (
        (source.resolver_config or {}).get("source") == "admin_manual"
    )


def manual_field_sources(software_app: SoftwareApp) -> dict[str, str]:
    """Recupera las procedencias de campos dentro de manual_installer y convierte sus claves y
    valores a texto.

    Args:
        software_app: Entidad local que se actualiza en la sesión del llamador.

    Returns:
        mapa de procedencias o vacío si la estructura no está disponible.
    """
    metadata = software_app.metadata_json or {}
    manual = metadata.get("manual_installer")
    if not isinstance(manual, dict):
        return {}
    sources = manual.get("field_sources")
    if not isinstance(sources, dict):
        return {}
    return {str(key): str(value) for key, value in sources.items()}


def parse_provider_datetime(value: object) -> datetime | None:
    """Interpreta fechas ISO del proveedor, acepta Z y normaliza las que incluyen zona a UTC sin
    tzinfo.

    Args:
        value: Texto o metadato opcional que se normaliza.

    Returns:
        fecha interpretada o None ante entrada vacía, no textual o inválida.
    """
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is not None:
        parsed = parsed.astimezone(UTC).replace(tzinfo=None)
    return parsed


def artifact_fingerprint(item: ResolvedSourceCreate) -> str:
    """Calcula SHA-256 del dominio, ruta, nombre, formato, bytes, versión, huella y contexto de
    plataforma normalizados.
    Excluye parámetros de consulta de la URL para conservar identidad frente a cambios de
    credenciales o firmas temporales.

    Args:
        item: Datos del instalador que se usarán para calcular su identidad estable.

    Returns:
        huella hexadecimal estable del artefacto.
    """
    metadata = item.metadata or {}
    parsed = urlparse(item.url)
    payload = {
        "domain": (item.final_domain or parsed.hostname or "").lower(),
        "path": parsed.path,
        "filename": (item.filename or "").lower(),
        "extension": (item.extension or "").lower(),
        "size": item.size_bytes,
        "version": item.version,
        "sha256": metadata.get("sha256") or metadata.get("expected_sha256"),
        "operating_system": metadata.get("operating_system"),
        "architecture": metadata.get("architecture"),
    }
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def text_fingerprint(value: str | None) -> str | None:
    """Calcula SHA-256 del texto UTF-8 sin espacios exteriores para comparar evidencia de origen.

    Args:
        value: Texto o metadato opcional que se normaliza.

    Returns:
        huella hexadecimal o None si el texto no contiene contenido.
    """
    if value is None or not value.strip():
        return None
    return hashlib.sha256(value.strip().encode("utf-8")).hexdigest()


def sync_provider_fields(
    software_app: SoftwareApp, app: WinstallApp, manual_fields: dict[str, str]
) -> bool:
    """Aplica datos Winstall salvo campos fijados manualmente y evita cambiar la versión visible
    de una aplicación disponible.
    Actualiza también el nombre normalizado cuando cambia el nombre.

    Args:
        software_app: Entidad local que se actualiza en la sesión del llamador.
        app: Resumen o detalle de Winstall con los nuevos valores del proveedor.
        manual_fields: Mapa de procedencias por campo; manual protege el valor frente a
            cambios del proveedor.

    Returns:
        True si modificó algún campo de la entidad, sin aumentar por sí misma su versión.
    """
    changed = False
    icon_url = app.icon_url
    if app.icon and not app.icon.startswith("http"):
        icon_key = app.icon.removesuffix(".png")
        icon_url = f"https://api.winstall.app/icons/next/{icon_key}.webp"
    provider_fields = {
        "name": app.name or app.package_id,
        "publisher": app.publisher,
        "officialUrl": app.homepage,
        "latestVersion": app.latest_version,
        "description": app.description,
        "iconUrl": icon_url,
    }
    attributes = {
        "name": "name",
        "publisher": "publisher",
        "officialUrl": "official_url",
        "latestVersion": "latest_version",
        "description": "description",
        "iconUrl": "icon_url",
    }
    for field_name, value in provider_fields.items():
        if manual_fields.get(field_name) == "manual":
            continue
        if (
            field_name == "latestVersion"
            and software_app.catalog_status == "available"
            and software_app.latest_version != value
        ):
            # La versión publicada se promueve después de validar su artefacto.
            continue
        attribute = attributes[field_name]
        if getattr(software_app, attribute) != value:
            setattr(software_app, attribute, value)
            changed = True
            if attribute == "name":
                software_app.normalized_name = normalize_text(value or app.package_id)
    return changed


def platform_from_artifact(extension: str | None, filename: str | None) -> str | None:
    """Relaciona formatos conocidos con plataforma y utiliza el sufijo del nombre cuando la
    extensión declarada no resuelve el caso.

    Args:
        extension: Extensión declarada, con o sin punto y con None si se desconoce.
        filename: Nombre de archivo usado como respaldo para reconocer el formato.

    Returns:
        windows, macos, linux o None para un formato desconocido.
    """
    extension = normalized_extension(extension)
    if extension in {".exe", ".msi", ".msix", ".appx"}:
        return "windows"
    if extension in {".dmg", ".pkg"}:
        return "macos"
    if extension in {".deb", ".rpm", ".appimage", ".tar.gz", ".jar"}:
        return "linux"

    filename = (filename or "").lower()
    if filename.endswith((".exe", ".msi", ".msix", ".appx")):
        return "windows"
    if filename.endswith((".dmg", ".pkg")):
        return "macos"
    if filename.endswith((".deb", ".rpm", ".appimage", ".tar.gz", ".jar")):
        return "linux"
    return None
