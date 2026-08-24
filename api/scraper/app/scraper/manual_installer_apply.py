"""Aplica inspecciones manuales ya validadas al catálogo persistente."""

from __future__ import annotations

import uuid
from urllib.parse import urlparse

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.json_safe import json_safe
from app.core.time import utc_now
from app.core.url_protector import UrlProtector
from app.db.enums import AppStatus, ResolutionStatus, ValidationStatus
from app.db.models import DownloadSource, ManualInstallerInspection, SoftwareApp
from app.repositories.catalog import CatalogRepository, ResolvedSourceCreate
from app.schemas.internal import ManualInstallerApplyRequest
from app.scraper.artifacts import ArtifactArchitecture
from app.scraper.candidates import registered_domain
from app.scraper.manual_installer import (
    ManualInstallerError,
    ManualInstallerInspectionRepository,
    ManualInstallerInspector,
    ValidatedManualInstaller,
    append_warning,
    clean_optional,
    description_provenance,
    reveal_manual_installer_inputs,
    reviewed_field_sources,
    same_installer_evidence,
    validate_icon,
)
from app.scraper.safe_http import SafeHttpError, has_sensitive_query, validate_public_https_url
from app.scraper.text import normalize_text
from app.scraper.validator import ValidationConfidence

ResolvedPlatformInstaller = tuple[ValidatedManualInstaller, str, str]


async def apply_manual_installer(
    session: AsyncSession,
    settings: Settings,
    app_id: uuid.UUID,
    inspection_id: uuid.UUID,
    request: ManualInstallerApplyRequest,
) -> tuple[SoftwareApp, list[uuid.UUID], list[str]]:
    """Aplica una inspección lista mediante una transacción optimista y repetible."""
    protector = UrlProtector(settings.url_protection_secret)
    repository = ManualInstallerInspectionRepository(session, protector, settings)
    inspection = await repository.get(app_id, inspection_id, for_update=True)
    if inspection is None:
        raise ManualInstallerError("inspection_not_found", 404)

    repeated = await _repeated_application(session, app_id, inspection)
    if repeated is not None:
        return repeated
    _require_ready_inspection(inspection)

    installer_inputs = reveal_manual_installer_inputs(inspection, protector)
    source_page_url = protector.reveal(inspection.source_page_url_encrypted)
    if not installer_inputs or not source_page_url:
        raise ManualInstallerError("inspection_url_unreadable", 409)
    if not request.name.strip():
        raise ManualInstallerError("name_required", 422)

    app_snapshot = await session.get(SoftwareApp, app_id)
    if app_snapshot is None:
        raise ManualInstallerError("app_not_found", 404)
    official_url, official_warning = await _review_official_url(
        request.official_url, app_snapshot.official_url
    )
    reviewed_icon_url = clean_optional(request.icon_url)
    validated_icon_url, icon_warning = await _review_icon(reviewed_icon_url, settings)
    validated_installers = await _validated_installers(
        settings,
        installer_inputs,
        source_page_url,
        inspection,
    )
    app = await _lock_current_app(session, app_id, request, inspection)
    platforms = _resolve_platforms(validated_installers, request.operating_system)

    _apply_reviewed_fields(app, request, official_url, reviewed_icon_url, validated_icon_url)
    _append_review_warnings(inspection, icon_warning, official_warning)
    _apply_long_description(app, request, inspection)
    _apply_manual_metadata(app, inspection)

    resolved_ids = await _persist_sources(
        session,
        protector,
        app,
        inspection,
        source_page_url,
        platforms,
    )
    _mark_applied(inspection, app, resolved_ids)
    await session.flush()
    return app, resolved_ids, list(inspection.warnings_json or [])


async def _repeated_application(
    session: AsyncSession,
    app_id: uuid.UUID,
    inspection: ManualInstallerInspection,
) -> tuple[SoftwareApp, list[uuid.UUID], list[str]] | None:
    """Devuelve el resultado persistido de una repetición idempotente."""
    if inspection.status != "applied" or inspection.source_ref is None:
        return None
    app = await session.get(SoftwareApp, app_id)
    if app is None:
        raise ManualInstallerError("app_not_found", 404)
    if (
        inspection.applied_app_version is None
        or app.version != inspection.applied_app_version
        or app.app_status != AppStatus.ACTIVE.value
        or app.catalog_status != "available"
    ):
        raise ManualInstallerError("app_changed_reinspect_required", 409)
    source_refs = [
        uuid.UUID(value)
        for value in (inspection.result_json or {}).get(
            "appliedSourceRefs", [str(inspection.source_ref)]
        )
    ]
    return app, source_refs, list(inspection.warnings_json or [])


def _require_ready_inspection(inspection: ManualInstallerInspection) -> None:
    """Rechaza inspecciones caducadas o que todavía no tienen evidencia aplicable."""
    if inspection.status == "expired":
        raise ManualInstallerError("inspection_expired", 409)
    if inspection.status != "ready" or not inspection.result_json:
        raise ManualInstallerError("inspection_not_ready", 409)


async def _review_official_url(
    requested_url: str | None,
    current_url: str | None,
) -> tuple[str | None, str | None]:
    """Valida un cambio de web oficial y conserva el valor previo si no es seguro."""
    reviewed = clean_optional(requested_url)
    current = clean_optional(current_url)
    if not reviewed or reviewed == current:
        return reviewed, None
    try:
        reviewed = await validate_public_https_url(reviewed)
        if has_sensitive_query(reviewed):
            raise SafeHttpError("query_credentials_forbidden")
    except SafeHttpError as exc:
        return current, f"official_url:{exc.code}"
    return reviewed, None


async def _review_icon(
    reviewed_icon_url: str | None,
    settings: Settings,
) -> tuple[str | None, str | None]:
    """Valida el icono opcional sin mezclarlo con la transacción del catálogo."""
    if not reviewed_icon_url:
        return None, None
    return await validate_icon(reviewed_icon_url, settings)


async def _validated_installers(
    settings: Settings,
    installer_inputs: list[tuple[str | None, str]],
    source_page_url: str,
    inspection: ManualInstallerInspection,
) -> list[ValidatedManualInstaller]:
    """Revalida cada instalador y compara su evidencia con la inspección capturada."""
    inspector = ManualInstallerInspector(settings)
    validated = [
        await inspector.validate_installer(url, source_page_url, operating_system)
        for operating_system, url in installer_inputs
    ]
    technical = (inspection.result_json or {}).get("installers") or [
        (inspection.result_json or {}).get("installer") or {}
    ]
    evidence_matches = len(technical) == len(validated) and all(
        same_installer_evidence(expected, actual)
        for expected, actual in zip(technical, validated, strict=True)
    )
    if not evidence_matches:
        raise ManualInstallerError("installer_changed_reinspect_required", 409)
    return validated


async def _lock_current_app(
    session: AsyncSession,
    app_id: uuid.UUID,
    request: ManualInstallerApplyRequest,
    inspection: ManualInstallerInspection,
) -> SoftwareApp:
    """Bloquea la aplicación y comprueba su versión tras las validaciones de red."""
    app = await session.scalar(
        select(SoftwareApp).where(SoftwareApp.id == app_id).with_for_update()
    )
    if app is None:
        raise ManualInstallerError("app_not_found", 404)
    if (
        app.version != request.expected_app_version
        or app.version != inspection.captured_app_version
    ):
        raise ManualInstallerError("app_version_conflict", 409)
    if app.app_status != AppStatus.ACTIVE.value or app.catalog_status not in {
        "review",
        "missing",
    }:
        raise ManualInstallerError("app_no_longer_unresolved", 409)
    return app


def _resolve_platforms(
    installers: list[ValidatedManualInstaller],
    requested_operating_system: str | None,
) -> list[ResolvedPlatformInstaller]:
    """Completa plataforma y arquitectura para cada instalador validado."""
    resolved: list[ResolvedPlatformInstaller] = []
    for validated in installers:
        operating_system = validated.operating_system
        if operating_system is None and len(installers) == 1:
            operating_system = requested_operating_system
        if operating_system is None:
            raise ManualInstallerError("operating_system_required", 422)
        architecture = (
            "UNKNOWN"
            if validated.architecture == ArtifactArchitecture.UNKNOWN.value
            else validated.architecture
        )
        resolved.append((validated, operating_system, architecture))
    return resolved


def _apply_reviewed_fields(
    app: SoftwareApp,
    request: ManualInstallerApplyRequest,
    official_url: str | None,
    reviewed_icon_url: str | None,
    validated_icon_url: str | None,
) -> None:
    """Actualiza exclusivamente los campos editables revisados por administración."""
    app.name = request.name.strip()
    app.normalized_name = normalize_text(app.name)
    app.publisher = clean_optional(request.publisher)
    app.official_url = official_url
    app.latest_version = clean_optional(request.latest_version)
    app.description = clean_optional(request.description)
    if validated_icon_url:
        app.icon_url = validated_icon_url
    elif reviewed_icon_url is None:
        app.icon_url = None
    app.updated_at = utc_now()
    app.version += 1


def _append_review_warnings(
    inspection: ManualInstallerInspection,
    icon_warning: str | None,
    official_warning: str | None,
) -> None:
    """Acumula avisos seguros producidos durante la segunda validación."""
    for warning in (icon_warning, official_warning):
        if warning:
            inspection.warnings_json = append_warning(inspection.warnings_json, warning)


def _apply_long_description(
    app: SoftwareApp,
    request: ManualInstallerApplyRequest,
    inspection: ManualInstallerInspection,
) -> None:
    """Aplica la descripción larga y su procedencia sin sobrescribir contenido intacto."""
    result = inspection.result_json or {}
    reviewed = clean_optional(request.long_description)
    suggestion = ((result.get("suggestions") or {}).get("longDescription") or {})
    suggested = clean_optional(suggestion.get("value"))
    source = suggestion.get("source")
    preserve_current = source == "current" and reviewed == clean_optional(app.long_description)
    if preserve_current:
        return
    app.long_description = reviewed
    app.long_description_language = "es" if reviewed else None
    app.long_description_error = None
    app.long_description_generated_at = utc_now() if reviewed else None
    app.long_description_status, app.long_description_source, app.long_description_model = (
        description_provenance(
            reviewed,
            suggested if source == "generated_ai" else None,
            result.get("ai") or {},
        )
    )
    app.long_description_input_hash = None


def _apply_manual_metadata(
    app: SoftwareApp,
    inspection: ManualInstallerInspection,
) -> None:
    """Registra la procedencia de los campos revisados en metadatos no sensibles."""
    result = inspection.result_json or {}
    metadata = dict(app.metadata_json or {})
    metadata["manual_installer"] = {
        "inspection_id": str(inspection.id),
        "applied_at": utc_now().isoformat(),
        "field_sources": reviewed_field_sources(
            result.get("suggestions") or {},
            {
                "name": app.name,
                "publisher": app.publisher,
                "officialUrl": app.official_url,
                "latestVersion": app.latest_version,
                "description": app.description,
                "longDescription": app.long_description,
                "iconUrl": app.icon_url,
            },
        ),
    }
    app.metadata_json = json_safe(metadata)


async def _persist_sources(
    session: AsyncSession,
    protector: UrlProtector,
    app: SoftwareApp,
    inspection: ManualInstallerInspection,
    source_page_url: str,
    installers: list[ResolvedPlatformInstaller],
) -> list[uuid.UUID]:
    """Crea o actualiza fuentes y resueltos validados para todas las plataformas."""
    catalog = CatalogRepository(session, protector)
    source_ids: set[uuid.UUID] = set()
    resolved_ids: list[uuid.UUID] = []
    for index, (validated, operating_system, architecture) in enumerate(installers):
        source = await catalog.source_for_platform(app.id, operating_system, architecture)
        if source is None:
            source = DownloadSource(
                software_app_id=app.id,
                operating_system=operating_system,
                architecture=architecture,
                initial_url=source_page_url,
                resolver_type="manual_http",
                resolver_config={"source": "admin_manual"},
                resolution_status=ResolutionStatus.MISSING.value,
                validation_status=ValidationStatus.UNCHECKED.value,
            )
            session.add(source)
            await session.flush()
        else:
            source.initial_url = source_page_url
            source.resolver_type = "manual_http"
            source.resolver_config = {"source": "admin_manual"}
            source.updated_at = utc_now()
        source_ids.add(source.id)
        resolved_ids.append(
            await _persist_resolved_source(
                session,
                catalog,
                app,
                inspection,
                source,
                validated,
                operating_system,
                architecture,
                is_primary=index == 0,
            )
        )

    await catalog.refresh_source_statuses(source_ids)
    await catalog.refresh_operating_systems(app.id)
    await session.refresh(app, attribute_names=["version", "app_status", "catalog_status"])
    return resolved_ids


async def _persist_resolved_source(
    session: AsyncSession,
    catalog: CatalogRepository,
    app: SoftwareApp,
    inspection: ManualInstallerInspection,
    source: DownloadSource,
    validated: ValidatedManualInstaller,
    operating_system: str,
    architecture: str,
    *,
    is_primary: bool,
) -> uuid.UUID:
    """Reemplaza la versión vigente de una fuente por el artefacto revalidado."""
    await catalog.expire_valid_resolved_sources(source.id)
    resolved = await catalog.save_resolved_source(
        ResolvedSourceCreate(
            source_id=source.id,
            url=validated.final_url,
            final_domain=validated.result.final_domain
            or registered_domain(validated.final_url)
            or urlparse(validated.final_url).hostname
            or "unknown",
            filename=validated.result.filename,
            extension=validated.result.extension,
            content_type=validated.result.content_type,
            size_bytes=validated.result.size_bytes,
            version=validated.version or app.latest_version,
            release_rank=0,
            is_latest=True,
            version_status="latest",
            score=100,
            status=ResolutionStatus.DIRECT,
            validation_status=ValidationStatus.VALID,
            metadata={
                "candidate_source": "admin_manual",
                "inspection_id": str(inspection.id),
                "validation_confidence": ValidationConfidence.VALIDATED.value,
                "operating_system": operating_system,
                "architecture": architecture,
                "is_primary": is_primary,
                "is_latest": True,
            },
        )
    )
    await session.flush()
    return resolved.id


def _mark_applied(
    inspection: ManualInstallerInspection,
    app: SoftwareApp,
    resolved_ids: list[uuid.UUID],
) -> None:
    """Cierra la inspección con las referencias persistidas."""
    inspection.status = "applied"
    inspection.phase = "applied"
    inspection.applied_at = utc_now()
    inspection.applied_app_version = app.version
    inspection.source_ref = resolved_ids[0]
    inspection.result_json = {
        **(inspection.result_json or {}),
        "appliedSourceRefs": [str(source_ref) for source_ref in resolved_ids],
    }
    inspection.updated_at = utc_now()
