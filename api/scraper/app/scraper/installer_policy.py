"""Políticas puras para descubrir, validar y ordenar instaladores.

Este módulo no accede a red ni a base de datos. Centraliza las decisiones que
comparten el pipeline principal, el descubrimiento web, las rutas internas y el
worker de enriquecimiento para que puedan probarse sin construir sus orquestadores.
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
    """Instalador aceptado junto con los metadatos derivados de su validación."""

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
    """Convierte las descargas conservadas de Winstall en candidatos validables."""
    candidates: list[InstallerCandidate] = []
    winstall_referer = payload.get("winstall_url")

    # El detalle de la API contiene la asociación autoritativa URL-versión. Se
    # materializa primero para que los enlaces duplicados extraídos de la página
    # no sustituyan ese contexto por la cadena genérica ``winstall_api``.
    declared: dict[str, tuple[str | None, str | None]] = {}
    for version in app.versions:
        for url in version.installers:
            current = declared.get(url)
            if current is None or version_label_is_preferred(
                current[0],
                version.version,
                getattr(app, "latest_version", None),
            ):
                declared[url] = (version.version, version.installer_type)
    for url, (version, installer_type) in declared.items():
        candidates.append(
            InstallerCandidate(
                url=url,
                source="winstall_api",
                label=f"{app.name} {installer_type or ''}".strip(),
                context=version,
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
    """Elige el contexto más reciente sin depender del orden del proveedor."""
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
    """Devuelve endpoints oficiales conocidos para una aplicación de Winstall."""
    return known_official_candidates_for_package(
        app.package_id,
        getattr(app, "latest_version", None),
    )


def known_official_candidates_for_package(
    package_id: str,
    latest_version: str | None = None,
) -> list[InstallerCandidate]:
    """Devuelve endpoints oficiales conocidos a partir del identificador de paquete."""
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
    """Indica si los endpoints conocidos sustituyen toda exploración heurística."""
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
    """Indica si la evidencia de Winstall es más segura que explorar la web oficial."""
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
    """Decide si se debe explorar la web oficial para localizar instaladores."""
    if known_official_candidates(app):
        return True
    return bool(use_official and official_url and not use_winstall_fallback_only(app, fallback))


def normalized_123pan_version(value: str) -> str:
    """Normaliza la versión usada por el endpoint oficial de 123pan."""
    parts = value.strip().removeprefix("v").split(".")
    if len(parts) >= 3 and all(part.isdigit() for part in parts[:3]):
        return ".".join(parts[:3])
    return value.strip().removeprefix("v")


def is_download_landing_page(
    candidate: InstallerCandidate,
    official_url: str,
    official_domain: str | None,
) -> bool:
    """Reconoce una página oficial intermedia orientada a descarga."""
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
    """Indica si el candidato puede conducir directamente a un instalador."""
    parsed = urlparse(candidate.url)
    if parsed.scheme not in {"http", "https"}:
        return False
    return bool(candidate.extension) or candidate.asset_kind in {
        "installer",
        "release_zip",
        "winstall_download",
    }


def github_collection_timeout_seconds(settings: Settings) -> float:
    """Acota el tiempo de exploración adicional de GitHub."""
    return max(5.0, min(15.0, settings.request_timeout_seconds + 2.0))


def winstall_parent_index_url(url: str) -> str | None:
    """Obtiene el índice padre de un binario de Winstall cuando es navegable."""
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
    """Elimina candidatos repetidos conservando el primero de cada URL."""
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
    """Expande, puntúa y ordena candidatos descargables."""
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
    """Deduplica instaladores por recurso estable, sistema y arquitectura."""
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
    """Extrae la versión con prioridad para la evidencia validada."""
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
    """Exige que algún binario validado corresponda a la versión anunciada."""
    if not latest_version or not latest_version.strip():
        return False
    expected = parse_version(latest_version)
    for installer in installers:
        if not installer.version:
            continue
        actual = parse_version(installer.version)
        if expected is not None and actual is not None:
            if expected == actual:
                return True
            continue
        if normalized_version_label(latest_version) == normalized_version_label(installer.version):
            return True
    return False


def versions_equal(first: str | None, second: str | None) -> bool:
    """Compara etiquetas de versión conservando un fallback textual estricto."""
    if not first or not second:
        return False
    first_version = parse_version(first)
    second_version = parse_version(second)
    if first_version is not None and second_version is not None:
        return first_version == second_version
    return normalized_version_label(first) == normalized_version_label(second)


def installer_app_compatibility_reason(
    app: WinstallApp,
    installer: ValidInstaller,
) -> str | None:
    """Descarta binarios válidos que pertenecen a otro producto o rama.

    La validación HTTP prueba que existe un binario, no que sea el binario de la
    aplicación. Se confía en la relación URL-versión declarada por Winstall y,
    para candidatos descubiertos en la web oficial, se exige concordancia de
    versión e identidad antes de publicarlos.
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

    if installer.version and declared_versions and not any(
        versions_equal(installer.version, version) for version in declared_versions
    ):
        return "version_not_declared_for_app"

    identity_tokens = app_identity_tokens(app)
    if not identity_tokens:
        return None
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
    if any(token.replace(" ", "") in identity_text for token in identity_tokens):
        return None
    if installer.candidate.asset_kind == "winstall_download" and not installer.version:
        # Los endpoints opacos de la API pueden no revelar nombre ni versión; la
        # asociación explícita del proveedor sigue siendo evidencia de identidad.
        return None
    return "product_identity_mismatch"


def app_identity_tokens(app: WinstallApp) -> tuple[str, ...]:
    """Extrae nombres de producto fuertes, excluyendo editor y calificadores."""
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
    """Normaliza una URL estable permitiendo que HTTP se actualice a HTTPS."""
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
    """Normaliza prefijos decorativos sin confundir versiones distintas."""
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
    """Ordena versiones dentro de cada combinación de sistema y arquitectura."""
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
    """Infiere el sistema con prioridad para los datos del recurso validado."""
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
    """Reconoce un ZIP Windows respaldado por el contexto de Winstall."""
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
    """Construye la clave estable para ordenar versiones y resolución."""
    version = parse_version(installer.version)
    return (
        1 if versions_equal(installer.version, latest_version) else 0,
        1 if version is not None else 0,
        version or Version("0"),
        1 if installer.status == ResolutionStatus.DIRECT else 0,
        installer.candidate.score,
    )


def parse_version(value: str | None) -> Version | None:
    """Analiza una versión tolerando etiquetas no compatibles con PEP 440."""
    if not value:
        return None
    try:
        return Version(value)
    except InvalidVersion:
        return None


def resolved_metadata(installer: ValidInstaller, is_latest: bool) -> dict[str, object]:
    """Construye los metadatos materializados de un instalador resuelto."""
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
    if installer.result.transport_security:
        metadata["transport_security"] = installer.result.transport_security
    if catalog_url_for_installer(installer) != (
        installer.result.final_url or installer.candidate.url
    ):
        metadata["validated_final_domain"] = installer.result.final_domain
    return metadata


def catalog_url_for_installer(installer: ValidInstaller) -> str:
    """Devuelve una URL estable aunque la validación use un token temporal."""
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
    """Aplica el contrato de publicación del catálogo antes de persistir."""
    return (
        installer.result.confidence == ValidationConfidence.VALIDATED
        and installer.result.transport_security
        not in {"https_winstall_edge_attested", "http_winstall_verified"}
    )
