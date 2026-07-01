from app.core.time import utc_now
from app.db.enums import ResolutionStatus, ValidationStatus
from app.db.models import ResolvedSource, SoftwareApp
from app.schemas.apps import AppDetails, AppListItem, DownloadOption


def valid_resolved_sources(app: SoftwareApp) -> list[ResolvedSource]:
    candidates = [
        resolved
        for source in app.sources
        for resolved in source.resolved_sources
        if resolved.validation_status == ValidationStatus.VALID.value and resolved.expires_at > utc_now()
    ]
    latest_by_file: dict[tuple[str, str | None, str | None, str], ResolvedSource] = {}
    for resolved in candidates:
        key = (resolved.final_domain, resolved.filename, resolved.extension, resolved.status)
        current = latest_by_file.get(key)
        if current is None or (resolved.checked_at, resolved.score) > (
            current.checked_at,
            current.score,
        ):
            latest_by_file[key] = resolved
    return sorted(latest_by_file.values(), key=resolved_sort_key)


def best_resolved_source(app: SoftwareApp) -> ResolvedSource | None:
    candidates = valid_resolved_sources(app)
    if not candidates:
        return None
    return candidates[0]


def resolved_sort_key(item: ResolvedSource) -> tuple[int, int, int, object]:
    status_priority = {ResolutionStatus.DIRECT.value: 0, ResolutionStatus.FALLBACK.value: 1}
    metadata = item.metadata_json or {}
    primary_rank = 0 if metadata.get("is_primary") else 1
    return (status_priority.get(item.status, 9), primary_rank, -item.score, item.expires_at)


def source_status(app: SoftwareApp) -> tuple[str, str]:
    source = app.sources[0] if app.sources else None
    if not source:
        return ResolutionStatus.MISSING.value, ValidationStatus.UNCHECKED.value
    return source.resolution_status, source.validation_status


def source_label(status: str) -> str:
    return "Sitio oficial" if status == ResolutionStatus.DIRECT.value else "Fallback Winstall"


def winstall_app_url(package_id: str) -> str:
    return f"https://winstall.app/apps/{package_id}"


def app_tags(app: SoftwareApp) -> list[str]:
    return sorted({tag.tag for tag in app.tags}, key=str.casefold)


def to_list_item(app: SoftwareApp) -> AppListItem:
    resolved = best_resolved_source(app)
    resolution_status, validation_status = source_status(app)
    if resolved:
        resolution_status = resolved.status
        validation_status = resolved.validation_status
    return AppListItem(
        id=str(app.id),
        slug=app.slug,
        packageId=app.winstall_id,
        name=app.name,
        publisher=app.publisher,
        description=app.description,
        longDescription=app.long_description,
        tags=app_tags(app),
        iconUrl=app.icon_url,
        latestVersion=app.latest_version,
        sourceLabel=source_label(resolution_status),
        resolutionStatus=resolution_status,
        validationStatus=validation_status,
        downloadable=resolved is not None,
        updatedAt=app.updated_at,
    )


def to_details(app: SoftwareApp) -> AppDetails:
    resolved_options = valid_resolved_sources(app)
    resolved = resolved_options[0] if resolved_options else None
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
    origin_url = (
        app.official_url
        if resolution_status == ResolutionStatus.DIRECT.value and app.official_url
        else winstall_app_url(app.winstall_id)
    )

    return AppDetails(
        id=str(app.id),
        slug=app.slug,
        packageId=app.winstall_id,
        name=app.name,
        publisher=app.publisher,
        description=app.description,
        longDescription=app.long_description,
        tags=app_tags(app),
        iconUrl=app.icon_url,
        officialUrl=app.official_url,
        originUrl=origin_url,
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
        downloadOptions=[
            to_download_option(option, is_primary=resolved is not None and option.id == resolved.id)
            for option in resolved_options
        ],
        notes=notes,
    )


def to_download_option(resolved: ResolvedSource, is_primary: bool) -> DownloadOption:
    return DownloadOption(
        id=str(resolved.id),
        filename=resolved.filename,
        extension=resolved.extension,
        sourceLabel=source_label(resolved.status),
        score=resolved.score,
        finalDomain=resolved.final_domain,
        isPrimary=is_primary,
    )
