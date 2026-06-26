from app.core.time import utc_now
from app.db.enums import ResolutionStatus, ValidationStatus
from app.db.models import ResolvedSource, SoftwareApp
from app.schemas.apps import AppDetails, AppListItem


def best_resolved_source(app: SoftwareApp) -> ResolvedSource | None:
    candidates = [
        resolved
        for source in app.sources
        for resolved in source.resolved_sources
        if resolved.validation_status == ValidationStatus.VALID.value and resolved.expires_at > utc_now()
    ]
    if not candidates:
        return None
    status_priority = {ResolutionStatus.DIRECT.value: 0, ResolutionStatus.FALLBACK.value: 1}
    return sorted(
        candidates,
        key=lambda item: (status_priority.get(item.status, 9), -item.score, item.expires_at),
    )[0]


def source_status(app: SoftwareApp) -> tuple[str, str]:
    source = app.sources[0] if app.sources else None
    if not source:
        return ResolutionStatus.MISSING.value, ValidationStatus.UNCHECKED.value
    return source.resolution_status, source.validation_status


def source_label(status: str) -> str:
    return "Sitio oficial" if status == ResolutionStatus.DIRECT.value else "Fallback Winstall"


def to_list_item(app: SoftwareApp) -> AppListItem:
    resolved = best_resolved_source(app)
    resolution_status, validation_status = source_status(app)
    if resolved:
        resolution_status = resolved.status
        validation_status = resolved.validation_status
    return AppListItem(
        id=app.slug,
        packageId=app.winstall_id,
        name=app.name,
        publisher=app.publisher,
        description=app.description,
        iconUrl=app.icon_url,
        latestVersion=app.latest_version,
        sourceLabel=source_label(resolution_status),
        resolutionStatus=resolution_status,
        validationStatus=validation_status,
        downloadable=resolved is not None,
        updatedAt=app.updated_at,
    )


def to_details(app: SoftwareApp) -> AppDetails:
    resolved = best_resolved_source(app)
    resolution_status, validation_status = source_status(app)
    notes = "El instalador necesita revision manual."
    if resolved:
        resolution_status = resolved.status
        validation_status = resolved.validation_status
        notes = (
            "Instalador obtenido directamente desde el sitio oficial."
            if resolved.status == ResolutionStatus.DIRECT.value
            else "Instalador obtenido desde el fallback de Winstall."
        )

    return AppDetails(
        id=app.slug,
        packageId=app.winstall_id,
        name=app.name,
        publisher=app.publisher,
        description=app.description,
        iconUrl=app.icon_url,
        officialUrl=app.official_url,
        latestVersion=app.latest_version,
        installerFilename=resolved.filename if resolved else None,
        installerType=resolved.extension.upper().lstrip(".") if resolved and resolved.extension else None,
        contentType=resolved.content_type if resolved else None,
        sizeBytes=resolved.size_bytes if resolved else None,
        finalDomain=resolved.final_domain if resolved else None,
        score=resolved.score if resolved else None,
        resolutionStatus=resolution_status,
        validationStatus=validation_status,
        sourceLabel=source_label(resolution_status),
        checkedAt=resolved.checked_at if resolved else None,
        expiresAt=resolved.expires_at if resolved else None,
        notes=notes,
    )
