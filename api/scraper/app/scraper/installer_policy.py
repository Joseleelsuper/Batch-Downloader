"""Centraliza las reglas puras que convierten evidencia de Winstall, GitHub y webs oficiales en
instaladores publicables.

See Also:
    app.scraper.validator: Aporta la validación técnica del recurso.
    app.scraper.winstall: Proporciona el detalle y catálogo del proveedor.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from urllib.parse import urlparse, urlunparse

from packaging.version import InvalidVersion, Version

from app.core.config import Settings
from app.db.enums import ResolutionStatus
from app.scraper.candidates import (
    InstallerCandidate,
    candidate_has_download_intent,
    candidate_variants,
    detect_extension,
    extract_version,
    infer_operating_system,
    is_download_candidate,
    operating_system_for_extension,
    product_tokens,
    registered_domain,
    score_candidate,
)
from app.scraper.github import parse_github_repo
from app.scraper.text import normalize_text
from app.scraper.validator import (
    ValidationConfidence,
    ValidationResult,
    is_sourceforge_download_url,
)
from app.scraper.winstall import WinstallApp


@dataclass(frozen=True)
class ValidInstaller:
    """Agrupa un candidato y su validación con la plataforma, arquitectura, versión y vía de
    resolución que se publicarán.

    Attributes:
        candidate: Candidato original con procedencia y puntuación.
        result: Resultado técnico de validación.
        status: Vía de resolución directa o fallback.
        operating_system: Sistema operativo derivado del artefacto.
        architecture: Arquitectura derivada de nombre, URL o formato.
        version: Versión validada, si hay evidencia suficiente.
    """

    candidate: InstallerCandidate
    """Candidato original que condujo al recurso validado."""
    result: ValidationResult
    """Resultado verificable de la descarga o inspección remota."""
    status: ResolutionStatus
    """Tipo de resolución que se materializará en el catálogo."""
    operating_system: str
    """Sistema operativo inferido a partir del recurso validado."""
    architecture: str
    """Arquitectura inferida a partir del recurso validado."""
    version: str | None
    """Versión extraída del instalador, si existe evidencia suficiente."""


def fallback_candidates(payload: dict[str, Any], app: WinstallApp) -> list[InstallerCandidate]:
    """Convierte instaladores declarados y enlaces de página de Winstall en candidatos,
    priorizando la asociación URL-versión del API y deduplicando al final.

    Args:
        payload: Mapa recibido del proveedor.
        app: Aplicación Winstall normalizada.

    Returns:
        candidatos Winstall en orden de evidencia.
    """
    candidates: list[InstallerCandidate] = []
    winstall_referer = payload.get("winstall_url")

    # El detalle de la API contiene la asociación autoritativa URL-versión. Se
    # materializa primero para que los enlaces duplicados extraídos de la página
    # no sustituyan ese contexto por la cadena genérica ``winstall_api``.
    declared: dict[str, tuple[str | None, str | None]] = {}
    for release in app.versions:
        for url in release.installers:
            current = declared.get(url)
            if current is None or version_label_is_preferred(
                current[0],
                release.version,
                getattr(app, "latest_version", None),
            ):
                declared[url] = (release.version, release.installer_type)
    for url, (version_label, installer_type) in declared.items():
        candidates.append(
            InstallerCandidate(
                url=url,
                source="winstall_api",
                label=f"{app.name} {installer_type or ''}".strip(),
                context=version_label,
                asset_kind="winstall_download",
                referer=winstall_referer,
            )
        )

    for item in payload.get("winstall_downloads") or []:
        if isinstance(item, dict) and item.get("url"):
            candidates.append(
                InstallerCandidate(
                    url=str(item["url"]),
                    source="winstall_page",
                    label=item.get("label") or app.name,
                    context=item.get("context"),
                    asset_kind="winstall_download",
                    referer=winstall_referer,
                )
            )
    for url in payload.get("winstall_download_urls") or []:
        if isinstance(url, str):
            candidates.append(
                InstallerCandidate(
                    url=url,
                    source="winstall_page",
                    label=app.name,
                    asset_kind="winstall_download",
                    referer=winstall_referer,
                )
            )
    return dedupe_candidates(candidates)


def version_label_is_preferred(
    current: str | None,
    candidate: str | None,
    latest: str | None,
) -> bool:
    """Decide qué etiqueta conservar para una URL, priorizando la última versión y después la
    mayor versión parseable.

    Args:
        current: Etiqueta de versión ya asociada a una URL.
        candidate: Candidato con URL y evidencias de procedencia.
        latest: Versión más reciente anunciada por el proveedor.

    Returns:
        True si candidate debe sustituir a current.
    """
    if versions_equal(candidate, latest):
        return not versions_equal(current, latest)
    if versions_equal(current, latest):
        return False
    current_version = parse_version(current)
    candidate_version = parse_version(candidate)
    if current_version is not None and candidate_version is not None:
        return candidate_version > current_version
    return current is None and candidate is not None


def known_official_candidates(app: WinstallApp) -> list[InstallerCandidate]:
    """Obtiene endpoints oficiales conocidos para el package ID de la aplicación.

    Args:
        app: Aplicación Winstall normalizada.

    Returns:
        candidatos oficiales específicos del proveedor.
    """
    return known_official_candidates_for_package(
        app.package_id,
        getattr(app, "latest_version", None),
    )


def known_official_candidates_for_package(
    package_id: str,
    latest_version: str | None = None,
) -> list[InstallerCandidate]:
    """Construye endpoints oficiales deterministas para aplicaciones con rutas documentadas y
    versión conocida.

    Args:
        package_id: Identificador exacto del paquete en Winstall.
        latest_version: Versión más reciente anunciada por el proveedor, o None si no existe.

    Returns:
        lista de candidatos oficiales o vacía.
    """
    if package_id == "ItchIo.Itch":
        return [
            InstallerCandidate(
                url="https://itch.io/app/download?platform=windows",
                source="official_known_endpoint",
                label="itch Windows installer",
                context="Official itch app Windows download endpoint.",
                asset_kind="installer",
            ),
            InstallerCandidate(
                url="https://itch.io/app/download?platform=osx",
                source="official_known_endpoint",
                label="itch macOS installer",
                context="Official itch app macOS download endpoint.",
                asset_kind="installer",
            ),
            InstallerCandidate(
                url="https://itch.io/app/download?platform=linux",
                source="official_known_endpoint",
                label="itch Linux installer",
                context="Official itch app Linux download endpoint.",
                asset_kind="installer",
            ),
        ]
    if package_id == "EpicGames.EpicGamesLauncher":
        return [
            InstallerCandidate(
                url=(
                    "https://launcher-public-service-prod06.ol.epicgames.com/"
                    "launcher/api/installer/download/EpicGamesLauncherInstaller.exe"
                ),
                source="official_known_endpoint",
                label="Epic Games Launcher Windows installer",
                context="Official Epic Games launcher download API.",
                asset_kind="installer",
            )
        ]
    if package_id == "115.115Chrome" and latest_version:
        version = latest_version.strip().removeprefix("v")
        return [
            InstallerCandidate(
                url=f"https://down.115.com/client/win/115br_v{version}_x64.exe",
                source="official_known_endpoint",
                label="115 Browser Windows x64 installer",
                context="Official 115 Browser Windows download endpoint.",
                asset_kind="installer",
            ),
            InstallerCandidate(
                url=f"https://down.115.com/client/mac/115br_v{version}_x64.dmg",
                source="official_known_endpoint",
                label="115 Browser macOS x64 installer",
                context="Official 115 Browser macOS download endpoint.",
                asset_kind="installer",
            ),
            InstallerCandidate(
                url=f"https://down.115.com/client/mac/115br_v{version}_arm64.dmg",
                source="official_known_endpoint",
                label="115 Browser macOS ARM64 installer",
                context="Official 115 Browser macOS download endpoint.",
                asset_kind="installer",
            ),
            InstallerCandidate(
                url=f"https://down.115.com/client/115pc/lin/115br_v{version}.deb",
                source="official_known_endpoint",
                label="115 Browser Linux DEB installer",
                context="Official 115 Browser Linux download endpoint.",
                asset_kind="installer",
            ),
        ]
    if package_id == "123.123pan" and latest_version:
        version = normalized_123pan_version(latest_version)
        compact_version = "".join(character for character in version if character.isdigit())
        if compact_version:
            return [
                InstallerCandidate(
                    url=(
                        "https://app.123957.com/pc-pro/windows/"
                        f"{compact_version}/123pan_{version}.exe"
                    ),
                    source="official_known_endpoint",
                    label="123云盘 Windows installer",
                    context="Official 123云盘 Windows download endpoint.",
                    asset_kind="installer",
                )
            ]
    return []


def use_only_known_official_candidates(
    app: WinstallApp,
    known_candidates: list[InstallerCandidate],
) -> bool:
    """Indica si un paquete sensible debe limitarse a sus endpoints oficiales conocidos.

    Args:
        app: Aplicación Winstall normalizada.
        known_candidates: Endpoints oficiales conocidos para el paquete.

    Returns:
        True para los paquetes con política explícita.
    """
    return bool(known_candidates) and app.package_id in {
        "EpicGames.EpicGamesLauncher",
        "ItchIo.Itch",
        "115.115Chrome",
        "123.123pan",
    }


def use_winstall_fallback_only(
    app: WinstallApp,
    fallback: list[InstallerCandidate],
) -> bool:
    """Indica si la evidencia Winstall es la única vía permitida para paquetes cuya web oficial
    es ambigua.

    Args:
        app: Aplicación Winstall normalizada.
        fallback: Candidatos conservados como respaldo de Winstall.

    Returns:
        True para los paquetes con fallback obligatorio.
    """
    return bool(fallback) and app.package_id in {
        "360.360DocProtect",
        "360.360SE",
        "360.360Zip",
        "3TSoftwareLabs.Studio3T",
        "86Box.86BoxManager",
    }


def should_collect_official_installers(
    app: WinstallApp,
    official_url: str | None,
    *,
    use_official: bool,
    fallback: list[InstallerCandidate],
) -> bool:
    """Decide si la estrategia puede explorar la web oficial según endpoints conocidos, flag de
    política y fallback disponible.

    Args:
        app: Aplicación Winstall normalizada.
        official_url: Página oficial que puede explorarse para obtener instaladores.
        use_official: Indica si la política permite consultar la página oficial.
        fallback: Candidatos conservados como respaldo de Winstall.

    Returns:
        True cuando debe recopilarse evidencia oficial.
    """
    if known_official_candidates(app):
        return True
    return bool(use_official and official_url and not use_winstall_fallback_only(app, fallback))


def normalized_123pan_version(value: str) -> str:
    """Normaliza la versión de 123云盘 quitando v y conservando hasta los tres componentes
    numéricos iniciales.

    Args:
        value: Valor textual que se normaliza o transforma.

    Returns:
        versión utilizable en la ruta oficial.
    """
    parts = value.strip().removeprefix("v").split(".")
    if len(parts) >= 3 and all(part.isdigit() for part in parts[:3]):
        return ".".join(parts[:3])
    return value.strip().removeprefix("v")


def is_download_landing_page(
    candidate: InstallerCandidate,
    official_url: str,
    official_domain: str | None,
) -> bool:
    """Reconoce una página HTTP(S) del mismo dominio oficial que funciona como paso intermedio de
    descarga.

    Args:
        candidate: Candidato con URL y evidencias de procedencia.
        official_url: Página oficial que puede explorarse para obtener instaladores.
        official_domain: Dominio registrado de la página oficial.

    Returns:
        True si la página tiene intención o ruta de descarga.
    """
    if candidate.url == official_url or candidate.extension:
        return False
    parsed = urlparse(candidate.url)
    if parsed.scheme not in {"http", "https"}:
        return False
    if not official_domain or registered_domain(candidate.url) != official_domain:
        return False
    route = f"{parsed.path}?{parsed.query}".lower()
    return candidate_has_download_intent(candidate) or any(
        marker in route for marker in ("download", "installer", "setup", "desktop")
    )


def is_actionable_installer_candidate(candidate: InstallerCandidate) -> bool:
    """Acepta destinos HTTP(S) con extensión o clase de artefacto que permite intentar validación
    binaria.

    Args:
        candidate: Candidato con URL y evidencias de procedencia.

    Returns:
        True si el candidato merece validarse.
    """
    parsed = urlparse(candidate.url)
    if parsed.scheme not in {"http", "https"}:
        return False
    return bool(candidate.extension) or candidate.asset_kind in {
        "installer",
        "release_zip",
        "winstall_download",
    }


def github_collection_timeout_seconds(settings: Settings) -> float:
    """Limita la exploración adicional de GitHub a un intervalo entre cinco y quince segundos.

    Args:
        settings: Configuración de red y límites del servicio.

    Returns:
        timeout en segundos.
    """
    return max(5.0, min(15.0, settings.request_timeout_seconds + 2.0))


def winstall_parent_index_url(url: str) -> str | None:
    """Deriva el índice de directorio padre de un artefacto Winstall para recuperar contexto de
    navegación.

    Args:
        url: URL de entrada o destino que se analiza.

    Returns:
        URL padre sin query ni fragmento, o None si no es segura.
    """
    try:
        parsed = urlparse(url)
    except ValueError:
        return None
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        return None
    if parse_github_repo(url):
        return None

    segments = parsed.path.split("/")
    file_index: int | None = None
    for index in range(len(segments) - 1, -1, -1):
        if detect_extension(segments[index]):
            file_index = index
            break
    if file_index is None:
        return None
    parent_path = "/".join(segments[:file_index]) + "/"
    if parent_path == "/":
        return None
    return urlunparse(parsed._replace(path=parent_path, params="", query="", fragment=""))


def dedupe_candidates(candidates: list[InstallerCandidate]) -> list[InstallerCandidate]:
    """Elimina candidatos repetidos por URL conservando la primera evidencia.

    Args:
        candidates: Candidatos que se deben deduplicar, puntuar o combinar.

    Returns:
        lista deduplicada en orden de entrada.
    """
    deduped: dict[str, InstallerCandidate] = {}
    for candidate in candidates:
        if candidate.url and candidate.url not in deduped:
            deduped[candidate.url] = candidate
    return list(deduped.values())


def prepare_scored_candidates(
    candidates: list[InstallerCandidate],
    app_name: str | None,
    package_id: str | None,
    publisher: str | None,
    version: str | None,
) -> list[InstallerCandidate]:
    """Expande variantes, calcula puntuaciones de identidad y plataforma y conserva solo destinos
    descargables.

    Args:
        candidates: Candidatos que se deben deduplicar, puntuar o combinar.
        app_name: Nombre visible de la aplicación usado para puntuar identidad.
        package_id: Identificador exacto del paquete en Winstall.
        publisher: Editor visible usado para separar identidad de calificadores.
        version: Versión solicitada o declarada por el proveedor.

    Returns:
        candidatos ordenados de mayor a menor puntuación.
    """
    expanded = [
        variant
        for candidate in dedupe_candidates(candidates)
        for variant in candidate_variants(candidate)
    ]
    scored = [
        score_candidate(
            candidate,
            app_name=app_name,
            package_id=package_id,
            publisher=publisher,
            version=version,
        )
        for candidate in dedupe_candidates(expanded)
        if is_download_candidate(candidate)
    ]
    return sorted(scored, key=lambda candidate: candidate.score, reverse=True)


def dedupe_valid_installers(installers: list[ValidInstaller]) -> list[ValidInstaller]:
    """Deduplica instaladores por URL estable, sistema operativo y arquitectura, conservando la
    mayor puntuación.

    Args:
        installers: Instaladores ya validados y con plataforma inferida.

    Returns:
        instaladores publicables sin colisiones de plataforma.
    """
    deduped: dict[tuple[str, str, str], ValidInstaller] = {}
    for installer in installers:
        url = catalog_url_for_installer(installer)
        parsed = urlparse(url)
        stable_url = parsed._replace(query="", fragment="").geturl()
        key = (stable_url, installer.operating_system, installer.architecture)
        current = deduped.get(key)
        if current is None or installer.candidate.score > current.candidate.score:
            deduped[key] = installer
    return list(deduped.values())


def validated_installer_version(
    candidate: InstallerCandidate,
    result: ValidationResult,
) -> str | None:
    """Extrae la versión primero del recurso validado, después del candidato original y por
    último del contexto Winstall permitido.

    Args:
        candidate: Candidato con URL y evidencias de procedencia.
        result: Resultado técnico de validar el recurso.

    Returns:
        versión validada o None.
    """
    final_candidate = InstallerCandidate(
        url=result.final_url or candidate.url,
        source=candidate.source,
        label=result.filename or candidate.label,
    )
    original_candidate = InstallerCandidate(
        url=candidate.url,
        source=candidate.source,
        label=candidate.label,
    )
    return (
        extract_version(final_candidate)
        or extract_version(original_candidate)
        or (
            candidate.context
            if (
                candidate.asset_kind == "winstall_download"
                or candidate.source.startswith("winstall_")
            )
            and parse_version(candidate.context) is not None
            else None
        )
    )


def validated_installers_cover_latest_version(
    latest_version: str | None,
    installers: list[ValidInstaller],
) -> bool:
    """Comprueba que algún instalador validado representa la versión más reciente anunciada.

    Args:
        latest_version: Versión más reciente anunciada por el proveedor, o None si no existe.
        installers: Instaladores ya validados y con plataforma inferida.

    Returns:
        True cuando existe cobertura de la versión latest.
    """
    if not latest_version or not latest_version.strip():
        return False
    return any(versions_equal(latest_version, installer.version) for installer in installers)


def versions_equal(first: str | None, second: str | None) -> bool:
    """Compara versiones con packaging y usa etiquetas normalizadas como fallback textual.

    Args:
        first: Primera etiqueta de versión que se compara.
        second: Segunda etiqueta de versión que se compara.

    Returns:
        True si ambas representan la misma versión.
    """
    if not first or not second:
        return False
    first_version = parse_version(first)
    second_version = parse_version(second)
    if first_version is not None and second_version is not None:
        return first_version == second_version
    return normalized_version_label(first) == normalized_version_label(second)


def compact_numeric_versions_equal(first: str | None, second: str | None) -> bool:
    """Reconoce versiones numéricas equivalentes al compactar sus componentes, solo para la
    comprobación fuerte de identidad.

    Args:
        first: Primera etiqueta de versión que se compara.
        second: Segunda etiqueta de versión que se compara.

    Returns:
        True si las formas compactas coinciden.
    """
    if not first or not second:
        return False

    def compact(value: str) -> str | None:
        normalized = normalized_version_label(value)
        parts = normalized.split(".")
        if len(parts) < 2 or any(not part.isdigit() for part in parts):
            return None
        return "".join(parts)

    first_compact = compact(first)
    return first_compact is not None and first_compact == compact(second)


def installer_app_compatibility_reason(
    app: WinstallApp,
    installer: ValidInstaller,
) -> str | None:
    """Verifica que el artefacto validado pertenece a la aplicación y a una versión declarada;
    devuelve el motivo de rechazo cuando la evidencia no basta.

    Args:
        app: Aplicación Winstall normalizada.
        installer: Instalador validado que se ordena, materializa o comprueba.

    Returns:
        None si es compatible o código de incompatibilidad.
    """
    declared_urls = {
        normalized_artifact_identity(url)
        for version in app.versions
        for url in version.installers
        if url
    }
    observed_urls = {
        normalized_artifact_identity(url)
        for url in (
            installer.candidate.url,
            installer.result.final_url,
        )
        if url
    }
    if declared_urls & observed_urls:
        return None

    declared_versions = [
        version.version for version in app.versions if version.version
    ]
    if app.latest_version:
        declared_versions.append(app.latest_version)
    if (
        installer.candidate.asset_kind == "winstall_download"
        and any(
            versions_equal(installer.candidate.context, version)
            for version in declared_versions
        )
    ):
        return None

    identity_tokens = app_identity_tokens(app)
    identity_text = normalize_text(
        " ".join(
            value
            for value in (
                installer.candidate.url,
                installer.candidate.label,
                installer.result.final_url,
                installer.result.filename,
            )
            if value
        )
    ).replace(" ", "")
    identity_matches = any(
        token.replace(" ", "") in identity_text for token in identity_tokens
    )
    if installer.version and declared_versions and not any(
        versions_equal(installer.version, version) for version in declared_versions
    ):
        if not (
            identity_matches
            and any(
                compact_numeric_versions_equal(installer.version, version)
                for version in declared_versions
            )
        ):
            return "version_not_declared_for_app"

    if not identity_tokens:
        return None
    if identity_matches:
        return None
    if installer.candidate.asset_kind == "winstall_download" and not installer.version:
        # Los endpoints opacos de la API pueden no revelar nombre ni versión; la
        # asociación explícita del proveedor sigue siendo evidencia de identidad.
        return None
    return "product_identity_mismatch"


def persisted_installer_app_compatibility_reason(
    app: WinstallApp,
    *,
    url: str,
    filename: str | None,
    version: str | None,
    metadata: dict[str, Any] | None,
) -> str | None:
    """Reconstruye un candidato a partir de una fila publicada y reevalúa su identidad con las
    reglas actuales.

    Args:
        app: Aplicación Winstall normalizada.
        url: URL de entrada o destino que se analiza.
        filename: Nombre de archivo observado en la validación.
        version: Versión solicitada o declarada por el proveedor.
        metadata: Metadatos persistidos del candidato, posiblemente ausentes o incompletos.

    Returns:
        None si la fila sigue siendo compatible o código de incompatibilidad.
    """
    safe_metadata = metadata if isinstance(metadata, dict) else {}
    source = str(safe_metadata.get("candidate_source") or "persisted_catalog")
    asset_kind = str(safe_metadata.get("asset_kind") or "installer")
    context_value = safe_metadata.get("candidate_context")
    context = str(context_value) if context_value else None
    if context is None and (
        asset_kind == "winstall_download" or source.startswith("winstall_")
    ):
        context = version
    label_value = safe_metadata.get("candidate_label")
    candidate = InstallerCandidate(
        url=url,
        source=source,
        label=str(label_value) if label_value else None,
        context=context,
        asset_kind=asset_kind,
    )
    return installer_app_compatibility_reason(
        app,
        ValidInstaller(
            candidate=candidate,
            result=ValidationResult(
                ok=True,
                url=url,
                final_url=url,
                final_domain=registered_domain(url),
                filename=filename,
                confidence=ValidationConfidence.VALIDATED,
            ),
            status=ResolutionStatus.FALLBACK,
            operating_system=str(safe_metadata.get("operating_system") or "unknown"),
            architecture=str(safe_metadata.get("architecture") or "UNKNOWN"),
            version=version,
        ),
    )


def app_identity_tokens(app: WinstallApp) -> tuple[str, ...]:
    """Extrae tokens distintivos del nombre y package ID excluyendo editor, edición, arquitectura
    y calificadores genéricos.

    Args:
        app: Aplicación Winstall normalizada.

    Returns:
        tupla de tokens de identidad.
    """
    package_product = app.package_id.rsplit(".", 1)[-1]
    publisher_tokens = set(product_tokens(app.publisher or ""))
    qualifiers = {
        "business",
        "community",
        "edition",
        "enterprise",
        "free",
        "home",
        "premium",
        "professional",
        "pro",
        "standard",
        "ultimate",
        "x64",
        "x86",
        "win32",
        "win64",
        "arm64",
        "aarch64",
    }
    tokens = product_tokens(f"{app.name} {package_product}")
    return tuple(
        token
        for token in tokens
        if token not in publisher_tokens
        and token not in qualifiers
        and not token.isdigit()
    )


def normalized_artifact_identity(url: str) -> str:
    """Normaliza host, ruta y consulta de una URL sin esquema, permitiendo comparar HTTP y HTTPS
    del mismo artefacto.

    Args:
        url: URL de entrada o destino que se analiza.

    Returns:
        identidad URL estable.
    """
    try:
        parsed = urlparse(url)
    except ValueError:
        return url
    return urlunparse(
        (
            "",
            (parsed.hostname or "").lower(),
            parsed.path.rstrip("/"),
            "",
            parsed.query,
            "",
        )
    )


def normalized_version_label(value: str) -> str:
    """Retira prefijos Version y v y normaliza mayúsculas sin alterar la estructura numérica.

    Args:
        value: Valor textual que se normaliza o transforma.

    Returns:
        etiqueta normalizada.
    """
    normalized = value.strip().casefold()
    if normalized.startswith("version"):
        normalized = normalized[len("version") :].lstrip(" :-_")
    if normalized.startswith("v") and len(normalized) > 1 and normalized[1].isdigit():
        normalized = normalized[1:]
    return normalized


def rank_installers(
    installers: list[ValidInstaller],
    latest_version: str | None = None,
) -> list[tuple[ValidInstaller, int, bool]]:
    """Agrupa por sistema y arquitectura y ordena cada grupo por latest, versión, vía directa y
    puntuación.

    Args:
        installers: Instaladores ya validados y con plataforma inferida.
        latest_version: Versión más reciente anunciada por el proveedor, o None si no existe.

    Returns:
        tuplas de instalador, posición y marca de principal.
    """
    grouped: dict[tuple[str, str], list[ValidInstaller]] = {}
    for installer in installers:
        grouped.setdefault(
            (installer.operating_system, installer.architecture),
            [],
        ).append(installer)

    ranked: list[tuple[ValidInstaller, int, bool]] = []
    for group in grouped.values():
        group.sort(
            key=lambda installer: installer_sort_key(installer, latest_version),
            reverse=True,
        )
        for index, installer in enumerate(group):
            ranked.append((installer, index, index == 0))
    return ranked


def infer_validated_operating_system(
    candidate: InstallerCandidate,
    result: ValidationResult,
) -> str | None:
    """Infiere plataforma usando extensión validada, nombre, URL final, candidato y finalmente el
    contexto de un ZIP Winstall.

    Args:
        candidate: Candidato con URL y evidencias de procedencia.
        result: Resultado técnico de validar el recurso.

    Returns:
        windows, macos, linux o None.
    """
    if result.extension != ".tar.gz":
        operating_system = operating_system_for_extension(result.extension)
        if operating_system:
            return operating_system
    if result.filename:
        filename_probe = InstallerCandidate(
            url=f"https://local.invalid/{result.filename}",
            source=candidate.source,
            label=candidate.label,
            context=candidate.context,
        )
        operating_system = infer_operating_system(filename_probe)
        if operating_system:
            return operating_system
    if result.final_url:
        final_probe = InstallerCandidate(
            url=result.final_url,
            source=candidate.source,
            label=candidate.label,
            context=candidate.context,
        )
        operating_system = infer_operating_system(final_probe)
        if operating_system:
            return operating_system
    operating_system = infer_operating_system(candidate)
    if operating_system:
        return operating_system
    if is_windows_winstall_archive(candidate, result.extension):
        return "windows"
    return None


def is_windows_winstall_archive(
    candidate: InstallerCandidate,
    extension: str | None = None,
) -> bool:
    """Reconoce un ZIP como artefacto Windows cuando la procedencia Winstall o sus tokens lo
    respaldan.

    Args:
        candidate: Candidato con URL y evidencias de procedencia.
        extension: Extensión detectada del artefacto, o None si no se conoce.

    Returns:
        True si el ZIP puede tratarse como Windows.
    """
    detected_extension = extension or candidate.extension
    return detected_extension == ".zip" and (
        candidate.source in {"winstall_api", "winstall_page"}
        or candidate.asset_kind == "winstall_download"
        or bool(candidate.match_tokens)
    )


def installer_sort_key(
    installer: ValidInstaller,
    latest_version: str | None = None,
) -> tuple[int, int, Any, int, int]:
    """Construye la clave total para priorizar latest, versiones parseables, resolución directa y
    puntuación.

    Args:
        installer: Instalador validado que se ordena, materializa o comprueba.
        latest_version: Versión más reciente anunciada por el proveedor, o None si no existe.

    Returns:
        tupla comparable de ordenación.
    """
    version = parse_version(installer.version)
    return (
        1 if versions_equal(installer.version, latest_version) else 0,
        1 if version is not None else 0,
        version or Version("0"),
        1 if installer.status == ResolutionStatus.DIRECT else 0,
        installer.candidate.score,
    )


def parse_version(value: str | None) -> Version | None:
    """Convierte una etiqueta a packaging.version y devuelve None para formatos no PEP 440.

    Args:
        value: Valor textual que se normaliza o transforma.

    Returns:
        Version o None.
    """
    if not value:
        return None
    try:
        return Version(value)
    except InvalidVersion:
        return None


def resolved_metadata(installer: ValidInstaller, is_latest: bool) -> dict[str, object]:
    """Materializa metadatos de procedencia, plataforma, versión, confianza y transporte para una
    resolución aceptada.

    Args:
        installer: Instalador validado que se ordena, materializa o comprueba.
        is_latest: Indica si el instalador es el principal de su plataforma.

    Returns:
        diccionario persistible de metadatos.
    """
    metadata: dict[str, object] = {
        "candidate_source": installer.candidate.source,
        "candidate_label": installer.candidate.label,
        "match_tokens": list(installer.candidate.match_tokens),
        "is_primary": is_latest,
        "is_latest": is_latest,
        "asset_kind": installer.candidate.asset_kind or "installer",
        "operating_system": installer.operating_system,
        "architecture": installer.architecture,
        "version_status": "latest" if is_latest else "previous",
        "validation_confidence": installer.result.confidence.value,
    }
    if installer.candidate.context:
        metadata["candidate_context"] = installer.candidate.context
    if installer.result.transport_security:
        metadata["transport_security"] = installer.result.transport_security
    if catalog_url_for_installer(installer) != (
        installer.result.final_url or installer.candidate.url
    ):
        metadata["validated_final_domain"] = installer.result.final_domain
    return metadata


def catalog_url_for_installer(installer: ValidInstaller) -> str:
    """Elige la URL estable para catálogo, conservando el enlace de SourceForge cuando la
    validación utilizó un espejo temporal.

    Args:
        installer: Instalador validado que se ordena, materializa o comprueba.

    Returns:
        URL persistible del instalador.
    """
    candidate = installer.candidate
    if (
        candidate.source == "playwright_data_release_url"
        and candidate.referer
        and is_sourceforge_download_url(candidate.referer)
    ):
        return candidate.referer
    if is_sourceforge_download_url(candidate.url):
        return candidate.url
    return installer.result.final_url or candidate.url


def is_catalog_publishable_installer(installer: ValidInstaller) -> bool:
    """Aplica el contrato final de publicación: validación binaria y transporte no atestado
    exclusivamente por Winstall.

    Args:
        installer: Instalador validado que se ordena, materializa o comprueba.

    Returns:
        True si el instalador puede persistirse en el catálogo.
    """
    return (
        installer.result.confidence == ValidationConfidence.VALIDATED
        and installer.result.transport_security
        not in {"https_winstall_edge_attested", "http_winstall_verified"}
    )
