"""Implementa las responsabilidades del módulo `app_mapper`.
"""
from app.db.enums import ResolutionStatus, ValidationStatus
from app.db.models import ResolvedSource, SoftwareApp
from app.schemas.apps import AppDetails, AppListItem, DownloadOption


def valid_resolved_sources(app: SoftwareApp) -> list[ResolvedSource]:
    """Ejecuta la operación `valid_resolved_sources`.

    Args:
        app (SoftwareApp): Aplicación sobre la que se realiza la operación.

    Returns:
        list[ResolvedSource]: Colección de elementos obtenidos por la operación.
    """
    candidates = [
        resolved
        for source in app.sources
        for resolved in source.resolved_sources
        if source.resolution_status
        in {ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value}
        and source.validation_status == ValidationStatus.VALID.value
        and resolved.catalog_downloadable is True
    ]
    latest_by_file: dict[
        tuple[str, str | None, str | None, str, str, str | None],
        ResolvedSource,
    ] = {}
    for resolved in candidates:
        source = resolved.source
        key = (
            resolved.final_domain,
            resolved.filename,
            resolved.extension,
            resolved.status,
            source.operating_system if source else "",
            resolved.version,
        )
        current = latest_by_file.get(key)
        if current is None or (resolved.checked_at, resolved.score) > (
            current.checked_at,
            current.score,
        ):
            latest_by_file[key] = resolved
    return sorted(latest_by_file.values(), key=resolved_sort_key)


def best_resolved_source(app: SoftwareApp) -> ResolvedSource | None:
    """Ejecuta la operación `best_resolved_source`.

    Args:
        app (SoftwareApp): Aplicación sobre la que se realiza la operación.

    Returns:
        ResolvedSource | None: Resultado producido por la operación.
    """
    candidates = valid_resolved_sources(app)
    if not candidates:
        return None
    return candidates[0]


def resolved_sort_key(item: ResolvedSource) -> tuple[int, int, int, int, int, object]:
    """Ejecuta la operación `resolved_sort_key`.

    Args:
        item (ResolvedSource): Valor de `item` utilizado por la operación.

    Returns:
        tuple[int, int, int, int, int, object]: Resultado producido por la operación.
    """
    status_priority = {ResolutionStatus.DIRECT.value: 0, ResolutionStatus.FALLBACK.value: 1}
    metadata = item.metadata_json or {}
    primary_rank = 0 if metadata.get("is_primary") else 1
    latest_rank = 0 if item.is_latest or metadata.get("is_latest") else 1
    release_rank = item.release_rank if item.release_rank is not None else 9999
    return (
        status_priority.get(item.status, 9),
        latest_rank,
        release_rank,
        primary_rank,
        -item.score,
        item.expires_at,
    )


def source_status(app: SoftwareApp) -> tuple[str, str]:
    """Ejecuta la operación `source_status`.

    Args:
        app (SoftwareApp): Aplicación sobre la que se realiza la operación.

    Returns:
        tuple[str, str]: Resultado producido por la operación.
    """
    review = next(
        (
            source
            for source in app.sources
            if source.resolution_status
            == ResolutionStatus.REQUIRES_MANUAL_REVIEW.value
        ),
        None,
    )
    if review is not None:
        return review.resolution_status, review.validation_status
    unavailable = next(
        (
            source
            for source in app.sources
            if source.resolution_status
            in {ResolutionStatus.MISSING.value, ResolutionStatus.BROKEN.value}
        ),
        None,
    )
    if unavailable is not None:
        return unavailable.resolution_status, unavailable.validation_status
    return ResolutionStatus.MISSING.value, ValidationStatus.UNCHECKED.value


def source_label(status: str) -> str:
    """Ejecuta la operación `source_label`.

    Args:
        status (str): Valor de `status` utilizado por la operación.

    Returns:
        str: Resultado producido por la operación.
    """
    if status == ResolutionStatus.DIRECT.value:
        return "Sitio oficial"
    if status == ResolutionStatus.FALLBACK.value:
        return "Fallback Winstall"
    if status == ResolutionStatus.REQUIRES_MANUAL_REVIEW.value:
        return "Revisión"
    return "No disponible"


def winstall_app_url(package_id: str) -> str:
    """Ejecuta la operación `winstall_app_url`.

    Args:
        package_id (str): Identificador de `package` utilizado por la operación.

    Returns:
        str: Resultado producido por la operación.
    """
    return f"https://winstall.app/apps/{package_id}"


def app_origin_url(app: SoftwareApp) -> str | None:
    """Ejecuta la operación `app_origin_url`.

    Args:
        app (SoftwareApp): Aplicación sobre la que se realiza la operación.

    Returns:
        str | None: Resultado producido por la operación.
    """
    if not app.winstall_id.startswith("manual."):
        return winstall_app_url(app.winstall_id)
    source_page = next(
        (
            source.initial_url
            for source in app.sources
            if source.initial_url
            and (source.resolver_config or {}).get("source") == "admin_manual"
        ),
        None,
    )
    return source_page or app.official_url


def app_tags(app: SoftwareApp) -> list[str]:
    """Ejecuta la operación `app_tags`.

    Args:
        app (SoftwareApp): Aplicación sobre la que se realiza la operación.

    Returns:
        list[str]: Colección de elementos obtenidos por la operación.
    """
    return sorted({tag.tag for tag in app.tags}, key=str.casefold)


def to_list_item(app: SoftwareApp) -> AppListItem:
    """Ejecuta la operación `to_list_item`.

    Args:
        app (SoftwareApp): Aplicación sobre la que se realiza la operación.

    Returns:
        AppListItem: Colección de elementos obtenidos por la operación.
    """
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
        operatingSystems=list(app.operating_systems or []),
        iconUrl=app.icon_url,
        latestVersion=app.latest_version,
        sourceLabel=source_label(resolution_status),
        resolutionStatus=resolution_status,
        validationStatus=validation_status,
        downloadable=resolved is not None,
        updatedAt=app.updated_at,
    )


def to_details(app: SoftwareApp) -> AppDetails:
    """Ejecuta la operación `to_details`.

    Args:
        app (SoftwareApp): Aplicación sobre la que se realiza la operación.

    Returns:
        AppDetails: Resultado producido por la operación.
    """
    resolved_options = valid_resolved_sources(app)
    resolved = resolved_options[0] if resolved_options else None
    resolution_status, validation_status = source_status(app)
    notes = (
        "El instalador necesita revisión manual."
        if resolution_status == ResolutionStatus.REQUIRES_MANUAL_REVIEW.value
        else "No hay un instalador disponible."
    )
    if resolved:
        resolution_status = resolved.status
        validation_status = resolved.validation_status
        notes = (
            "Instalador obtenido directamente desde el sitio oficial."
            if resolved.status == ResolutionStatus.DIRECT.value
            else "Instalador obtenido desde el fallback de Winstall."
        )
    origin_url = app_origin_url(app)

    return AppDetails(
        id=str(app.id),
        slug=app.slug,
        packageId=app.winstall_id,
        name=app.name,
        publisher=app.publisher,
        description=app.description,
        longDescription=app.long_description,
        tags=app_tags(app),
        operatingSystems=list(app.operating_systems or []),
        iconUrl=app.icon_url,
        officialUrl=app.official_url,
        originUrl=origin_url,
        latestVersion=app.latest_version,
        installerFilename=resolved.filename if resolved else None,
        installerType=(
            resolved.extension.upper().lstrip(".")
            if resolved and resolved.extension
            else None
        ),
        contentType=resolved.content_type if resolved else None,
        sizeBytes=resolved.size_bytes if resolved else None,
        finalDomain=resolved.final_domain if resolved else None,
        score=resolved.score if resolved else None,
        resolutionStatus=resolution_status,
        validationStatus=validation_status,
        downloadable=resolved is not None,
        updatedAt=app.updated_at,
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
    """Ejecuta la operación `to_download_option`.

    Args:
        resolved (ResolvedSource): Valor de `resolved` utilizado por la operación.
        is_primary (bool): Valor de `is_primary` utilizado por la operación.

    Returns:
        DownloadOption: Resultado producido por la operación.
    """
    source = resolved.source
    return DownloadOption(
        id=str(resolved.id),
        filename=resolved.filename,
        extension=resolved.extension,
        operatingSystem=source.operating_system if source else "windows",
        architecture=source.architecture if source else "UNKNOWN",
        version=resolved.version,
        isLatest=bool(resolved.is_latest),
        versionStatus=resolved.version_status,
        sourceLabel=source_label(resolved.status),
        score=resolved.score,
        finalDomain=resolved.final_domain,
        isPrimary=is_primary,
    )
