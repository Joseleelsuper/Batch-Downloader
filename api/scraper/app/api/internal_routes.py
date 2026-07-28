from __future__ import annotations

import secrets
from datetime import datetime
from typing import Annotated
from urllib.parse import urlparse
from uuid import UUID

import httpx
from fastapi import APIRouter, BackgroundTasks, Depends, Header, HTTPException, Query
from fastapi.responses import JSONResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings, get_settings
from app.core.time import utc_after, utc_now
from app.core.url_protector import UrlProtector
from app.db.enums import ResolutionStatus, ValidationStatus
from app.db.models import ResolvedSource
from app.db.session import get_session
from app.domain.source_resolution import SourceTrustStatus, source_trust_status
from app.repositories.catalog import CatalogRepository
from app.repositories.pipeline import (
    QUEUE_SO_FILTER_DESCRIPTOR,
    PipelineRepository,
)
from app.schemas.internal import (
    ContentEnqueueResult,
    GenerateDescriptionRequest,
    GenerateDescriptionResult,
    InternalSourceResolution,
    ManualInstallerApplyRequest,
    ManualInstallerApplyResult,
    ManualInstallerInspectionRequest,
    ManualInstallerInspectionView,
    SemanticDocument,
    SemanticDocumentPage,
    WebsiteAppDiscoveryApplyRequest,
    WebsiteAppDiscoveryApplyResult,
    WebsiteAppDiscoveryRequest,
    WebsiteAppDiscoveryView,
)
from app.scraper.candidates import InstallerCandidate
from app.scraper.catalog_fetcher import DescriptorWorker, enqueue_descriptor_for_app
from app.scraper.description_enricher import (
    build_embedding_metadata,
    build_embedding_text,
    embedding_content_hash,
)
from app.scraper.manual_installer import (
    ManualInstallerError,
    ManualInstallerInspectionRepository,
    ManualInstallerTransientError,
    apply_manual_installer,
    inspection_view,
)
from app.scraper.safe_http import SafeHttpError
from app.scraper.validator import DownloadValidator, ValidationConfidence, ValidationResult
from app.scraper.website_discovery import (
    WebsiteAppDiscoveryRepository,
    WebsiteDiscoveryError,
    WebsiteDiscoveryTransientError,
    apply_website_app_discovery,
    website_discovery_view,
)

INTERNAL_SERVICE_TOKEN_HEADER = "X-Internal-Service-Token"

internal_router = APIRouter(prefix="/internal/v1")


async def require_internal_service_token(
    settings: Annotated[Settings, Depends(get_settings)],
    provided_token: Annotated[
        str | None,
        Header(alias=INTERNAL_SERVICE_TOKEN_HEADER),
    ] = None,
) -> None:
    expected_token = settings.internal_service_token.get_secret_value()
    token_matches = secrets.compare_digest(provided_token or "", expected_token)
    if not expected_token or not token_matches:
        raise HTTPException(status_code=401, detail={"code": "invalid_internal_token"})


@internal_router.get(
    "/semantic/documents",
    response_model=SemanticDocumentPage,
    response_model_by_alias=True,
    responses={401: {}},
)
async def semantic_documents(
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
    after_app_id: Annotated[
        UUID | None,
        Query(alias="afterAppId"),
    ] = None,
    limit: Annotated[int, Query(ge=1, le=500)] = 500,
) -> SemanticDocumentPage:
    catalog = CatalogRepository(session, UrlProtector(settings.url_protection_secret))
    apps, next_after = await catalog.semantic_documents(
        after_app_id=after_app_id,
        limit=limit,
    )
    return SemanticDocumentPage(
        documents=[
            SemanticDocument(
                appId=str(software_app.id),
                contentHash=embedding_content_hash(software_app),
                content=build_embedding_text(software_app),
                metadata=build_embedding_metadata(software_app),
            )
            for software_app in apps
        ],
        nextAfterAppId=next_after,
    )


@internal_router.get(
    "/sources/{source_ref}/resolution",
    response_model=InternalSourceResolution,
    response_model_by_alias=True,
    responses={
        401: {},
        404: {},
        409: {"model": InternalSourceResolution},
        503: {},
    },
)
async def get_source_resolution(
    source_ref: str,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> InternalSourceResolution | JSONResponse:
    catalog = CatalogRepository(session, UrlProtector(settings.url_protection_secret))
    # Lock both the candidate and its parent source. A sourceRef can spend time
    # queued; the parent may have become broken/review/invalid in the meantime.
    resolved = await catalog.get_resolved_source_by_ref_for_update(source_ref)
    if resolved is None:
        raise HTTPException(status_code=404, detail={"code": "source_not_found"})

    metadata = resolved.metadata_json or {}
    trust_status = (
        source_trust_status(
            validation_status=resolved.validation_status,
            resolution_status=resolved.status,
            expires_at=resolved.expires_at,
            metadata=metadata,
            now=utc_now(),
        )
        if _parent_source_is_available(resolved)
        else SourceTrustStatus.UNRESOLVED
    )
    url = catalog.reveal_url(resolved) if trust_status == SourceTrustStatus.VERIFIED else None
    if trust_status == SourceTrustStatus.UNRESOLVED and _can_revalidate_expired(resolved, metadata):
        url = await _revalidate_expired_source(resolved, catalog, settings, session)
        if url is not None:
            metadata = resolved.metadata_json or {}
            trust_status = SourceTrustStatus.VERIFIED
    if trust_status == SourceTrustStatus.VERIFIED and (
        url is None or urlparse(url).scheme != "https"
    ):
        url = await _invalidate_unusable_source(resolved, catalog, session)
        if url is None:
            metadata = resolved.metadata_json or {}
            trust_status = SourceTrustStatus.UNRESOLVED
    sha256 = metadata.get("sha256") or metadata.get("expected_sha256")
    expected_sha256 = (
        sha256.lower()
        if isinstance(sha256, str)
        and len(sha256) == 64
        and all(character in "0123456789abcdefABCDEF" for character in sha256)
        else None
    )
    response = InternalSourceResolution(
        sourceRef=str(resolved.id),
        appId=str(resolved.source.software_app_id),
        url=url,
        expectedFilename=resolved.filename,
        expectedSizeBytes=resolved.size_bytes,
        expectedSha256=expected_sha256,
        expectedMime=resolved.content_type,
        operatingSystem=resolved.source.operating_system,
        architecture=resolved.source.architecture,
        trustStatus=trust_status,
    )
    if trust_status != SourceTrustStatus.VERIFIED:
        await session.commit()
        return JSONResponse(
            status_code=409,
            content=response.model_dump(by_alias=True, mode="json"),
        )
    await session.commit()
    return response


def _can_revalidate_expired(resolved: ResolvedSource, metadata: dict) -> bool:
    confidence = str(metadata.get("validation_confidence") or "").lower()
    return (
        _parent_source_is_available(resolved)
        and resolved.validation_status == ValidationStatus.VALID.value
        and resolved.status
        in {ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value}
        and resolved.expires_at <= utc_now()
        and confidence in {"", "validated", "verified"}
        and metadata.get("transport_security")
        not in {"https_winstall_edge_attested", "http_winstall_verified"}
    )


def _parent_source_is_available(resolved: ResolvedSource) -> bool:
    source = resolved.source
    return (
        source is not None
        and source.catalog_available is True
        and source.resolution_status
        in {ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value}
        and source.validation_status == ValidationStatus.VALID.value
    )


async def _revalidate_expired_source(
    resolved: ResolvedSource,
    catalog: CatalogRepository,
    settings: Settings,
    session: AsyncSession,
) -> str | None:
    """Refresh a stale URL immediately before it crosses the service boundary.

    Core may queue a recently validated candidate so a slow catalog crawl does not
    make every bundle unavailable. The encrypted URL remains private until this
    validation succeeds; the download worker performs its own final network and
    binary validation afterwards.
    """
    # A current read under a row lock prevents duplicate network validation.
    # ``populate_existing`` in the repository also observes a renewal or a
    # terminal invalidation committed while this request was waiting.
    locked = await catalog.get_resolved_source_by_ref_for_update(str(resolved.id))
    if locked is None:
        await session.commit()
        return None
    resolved = locked
    metadata = dict(resolved.metadata_json or {})
    if not _parent_source_is_available(resolved):
        await session.commit()
        return None
    trust_status = source_trust_status(
        validation_status=resolved.validation_status,
        resolution_status=resolved.status,
        expires_at=resolved.expires_at,
        metadata=metadata,
        now=utc_now(),
    )
    if trust_status == SourceTrustStatus.VERIFIED:
        url = catalog.reveal_url(resolved)
        await session.commit()
        return url
    if not _can_revalidate_expired(resolved, metadata):
        await session.commit()
        return None

    protected_url = catalog.reveal_url(resolved)
    if protected_url is None:
        await _expire_terminal_candidate(
            resolved,
            metadata,
            "source_url_unreadable",
            session,
        )
        return None
    candidate = InstallerCandidate(
        url=protected_url,
        source=str(metadata.get("candidate_source") or "internal_revalidation"),
        label=str(metadata.get("candidate_label") or "") or None,
        asset_kind=str(metadata.get("asset_kind") or "") or None,
        referer=resolved.source.initial_url,
    )
    try:
        result = await DownloadValidator(settings).validate(candidate)
    except httpx.RequestError as exc:
        await session.commit()
        raise HTTPException(
            status_code=503,
            detail={"code": "source_revalidation_transient"},
        ) from exc
    now = utc_now()
    final_url = result.final_url or protected_url
    if (
        not result.ok
        or result.confidence != ValidationConfidence.VALIDATED
        or urlparse(final_url).scheme != "https"
    ):
        reason = (
            result.reason
            or ("source_not_https" if urlparse(final_url).scheme != "https" else None)
            or "source_not_verified"
        )
        if _is_transient_revalidation_failure(result, reason):
            await session.commit()
            raise HTTPException(
                status_code=503,
                detail={"code": "source_revalidation_transient"},
            )
        await _expire_terminal_candidate(resolved, metadata, reason, session, now=now)
        return None

    resolved.checked_at = now
    resolved.resolved_url_encrypted = catalog.url_protector.protect(final_url)
    resolved.final_domain = result.final_domain or resolved.final_domain
    resolved.filename = result.filename or resolved.filename
    resolved.extension = result.extension or resolved.extension
    resolved.content_type = result.content_type or resolved.content_type
    resolved.size_bytes = (
        result.size_bytes if result.size_bytes is not None else resolved.size_bytes
    )
    resolved.validation_status = ValidationStatus.VALID.value
    resolved.expires_at = utc_after(hours=24)
    resolved.source.resolution_status = resolved.status
    resolved.source.validation_status = ValidationStatus.VALID.value
    metadata["validation_confidence"] = ValidationConfidence.VALIDATED.value
    metadata.pop("last_revalidation_error", None)
    if result.transport_security:
        metadata["transport_security"] = result.transport_security
    else:
        metadata.pop("transport_security", None)
    resolved.metadata_json = metadata
    await session.commit()
    return final_url


async def _invalidate_unusable_source(
    resolved: ResolvedSource,
    catalog: CatalogRepository,
    session: AsyncSession,
) -> str | None:
    locked = await catalog.get_resolved_source_by_ref_for_update(str(resolved.id))
    if locked is None:
        await session.commit()
        return None
    url = catalog.reveal_url(locked)
    if url is not None and urlparse(url).scheme == "https":
        await session.commit()
        return url
    await _expire_terminal_candidate(
        locked,
        dict(locked.metadata_json or {}),
        "source_url_unreadable" if url is None else "source_not_https",
        session,
    )
    return None


async def _expire_terminal_candidate(
    resolved: ResolvedSource,
    metadata: dict,
    reason: str,
    session: AsyncSession,
    *,
    now: datetime | None = None,
) -> None:
    invalidated_at = now or utc_now()
    resolved.checked_at = invalidated_at
    resolved.validation_status = ValidationStatus.EXPIRED.value
    resolved.expires_at = invalidated_at
    metadata["last_revalidation_error"] = reason
    resolved.metadata_json = metadata
    await session.commit()


def _is_transient_revalidation_failure(
    result: ValidationResult,
    reason: str,
) -> bool:
    if result.ok and result.confidence == ValidationConfidence.ATTESTED:
        return True
    if reason in {"no_response", "source_not_verified"}:
        return True
    if not reason.startswith("http_"):
        return False
    try:
        status_code = int(reason.removeprefix("http_"))
    except ValueError:
        return False
    return status_code in {408, 425, 429} or status_code >= 500


@internal_router.post(
    "/content/descriptions/enqueue-missing",
    status_code=202,
    response_model=ContentEnqueueResult,
    response_model_by_alias=True,
)
async def enqueue_missing_descriptions(
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> ContentEnqueueResult:
    catalog = CatalogRepository(session, UrlProtector(settings.url_protection_secret))
    pipeline = PipelineRepository(session)
    matched = 0
    enqueued = 0
    already_active = 0
    for app in await catalog.apps_missing_long_descriptions():
        matched += 1
        if await pipeline.has_active_item(QUEUE_SO_FILTER_DESCRIPTOR, app.winstall_id):
            already_active += 1
            continue
        item = await enqueue_descriptor_for_app(
            catalog,
            pipeline,
            None,
            app,
            force=True,
            priority=100,
        )
        if item:
            enqueued += 1
    await session.commit()
    return ContentEnqueueResult(
        matched=matched,
        enqueued=enqueued,
        alreadyActive=already_active,
    )


@internal_router.post(
    "/content/descriptions/generate",
    status_code=202,
    response_model=GenerateDescriptionResult,
    response_model_by_alias=True,
    responses={401: {}, 404: {}, 409: {}},
)
async def generate_description(
    request: GenerateDescriptionRequest,
    background_tasks: BackgroundTasks,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> GenerateDescriptionResult:
    catalog = CatalogRepository(session, UrlProtector(settings.url_protection_secret))
    app = await catalog.get_app_by_public_id(request.app_id)
    if not app:
        raise HTTPException(status_code=404, detail={"code": "app_not_found"})
    item = await enqueue_descriptor_for_app(
        catalog,
        PipelineRepository(session),
        None,
        app,
        force=True,
        priority=100,
    )
    if not item:
        raise HTTPException(
            status_code=409,
            detail={"code": "description_already_current"},
        )
    await session.commit()
    background_tasks.add_task(_run_descriptor_once_background)
    return GenerateDescriptionResult(jobId=str(item.id), status=item.status)


@internal_router.post(
    "/admin/apps/{app_id}/manual-installer-inspections",
    status_code=202,
    response_model=ManualInstallerInspectionView,
    response_model_by_alias=True,
    responses={401: {}, 404: {}, 409: {}, 422: {}},
)
async def create_manual_installer_inspection(
    app_id: UUID,
    request: ManualInstallerInspectionRequest,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> ManualInstallerInspectionView:
    repository = ManualInstallerInspectionRepository(
        session,
        UrlProtector(settings.url_protection_secret),
        settings,
    )
    try:
        inspection, _created = await repository.create_or_reuse(
            app_id,
            request.installer_url,
            request.source_page_url,
            request.installer_urls.model_dump(),
        )
    except (ManualInstallerError, SafeHttpError) as exc:
        raise_manual_installer_http_error(exc)
    await session.commit()
    return ManualInstallerInspectionView.model_validate(inspection_view(inspection))


@internal_router.get(
    "/admin/apps/{app_id}/manual-installer-inspections/current",
    response_model=ManualInstallerInspectionView,
    response_model_by_alias=True,
    responses={401: {}, 404: {}},
)
async def current_manual_installer_inspection(
    app_id: UUID,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> ManualInstallerInspectionView:
    repository = ManualInstallerInspectionRepository(
        session,
        UrlProtector(settings.url_protection_secret),
        settings,
    )
    inspection = await repository.current(app_id)
    if inspection is None:
        raise HTTPException(status_code=404, detail={"code": "inspection_not_found"})
    await session.commit()
    return ManualInstallerInspectionView.model_validate(inspection_view(inspection))


@internal_router.get(
    "/admin/apps/{app_id}/manual-installer-inspections/{inspection_id}",
    response_model=ManualInstallerInspectionView,
    response_model_by_alias=True,
    responses={401: {}, 404: {}},
)
async def get_manual_installer_inspection(
    app_id: UUID,
    inspection_id: UUID,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> ManualInstallerInspectionView:
    repository = ManualInstallerInspectionRepository(
        session,
        UrlProtector(settings.url_protection_secret),
        settings,
    )
    inspection = await repository.get(app_id, inspection_id)
    if inspection is None:
        raise HTTPException(status_code=404, detail={"code": "inspection_not_found"})
    await session.commit()
    return ManualInstallerInspectionView.model_validate(inspection_view(inspection))


@internal_router.post(
    "/admin/apps/{app_id}/manual-installer-inspections/{inspection_id}/apply",
    response_model=ManualInstallerApplyResult,
    response_model_by_alias=True,
    responses={401: {}, 404: {}, 409: {}, 422: {}, 503: {}},
)
async def apply_manual_installer_inspection(
    app_id: UUID,
    inspection_id: UUID,
    request: ManualInstallerApplyRequest,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> ManualInstallerApplyResult:
    try:
        app, source_refs, warnings = await apply_manual_installer(
            session,
            settings,
            app_id,
            inspection_id,
            request,
        )
    except (ManualInstallerError, ManualInstallerTransientError, SafeHttpError) as exc:
        raise_manual_installer_http_error(exc)
    await session.commit()
    return ManualInstallerApplyResult(
        appId=str(app.id),
        sourceRef=str(source_refs[0]),
        sourceRefs=[str(source_ref) for source_ref in source_refs],
        appVersion=app.version,
        catalogStatus="available",
        warnings=warnings,
    )


@internal_router.post(
    "/admin/app-discoveries",
    status_code=202,
    response_model=WebsiteAppDiscoveryView,
    response_model_by_alias=True,
    responses={401: {}, 409: {}, 422: {}},
)
async def create_website_app_discovery(
    request: WebsiteAppDiscoveryRequest,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> WebsiteAppDiscoveryView:
    repository = WebsiteAppDiscoveryRepository(
        session,
        UrlProtector(settings.url_protection_secret),
        settings,
    )
    try:
        discovery, _created = await repository.create_or_reuse(
            request.official_url,
            request.installer_urls.model_dump(),
        )
    except (WebsiteDiscoveryError, SafeHttpError) as exc:
        raise_website_discovery_http_error(exc)
    await session.commit()
    return WebsiteAppDiscoveryView.model_validate(
        website_discovery_view(discovery)
    )


@internal_router.get(
    "/admin/app-discoveries/{discovery_id}",
    response_model=WebsiteAppDiscoveryView,
    response_model_by_alias=True,
    responses={401: {}, 404: {}},
)
async def get_website_app_discovery(
    discovery_id: UUID,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> WebsiteAppDiscoveryView:
    repository = WebsiteAppDiscoveryRepository(
        session,
        UrlProtector(settings.url_protection_secret),
        settings,
    )
    discovery = await repository.get(discovery_id)
    if discovery is None:
        raise HTTPException(
            status_code=404,
            detail={"code": "website_discovery_not_found"},
        )
    await session.commit()
    return WebsiteAppDiscoveryView.model_validate(
        website_discovery_view(discovery)
    )


@internal_router.post(
    "/admin/app-discoveries/{discovery_id}/apply",
    response_model=WebsiteAppDiscoveryApplyResult,
    response_model_by_alias=True,
    responses={401: {}, 404: {}, 409: {}, 422: {}, 503: {}},
)
async def apply_website_app_discovery_route(
    discovery_id: UUID,
    request: WebsiteAppDiscoveryApplyRequest,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> WebsiteAppDiscoveryApplyResult:
    try:
        app, installer_count, warnings = await apply_website_app_discovery(
            session,
            settings,
            discovery_id,
            request,
        )
    except (
        WebsiteDiscoveryError,
        WebsiteDiscoveryTransientError,
        SafeHttpError,
    ) as exc:
        raise_website_discovery_http_error(exc)
    await session.commit()
    return WebsiteAppDiscoveryApplyResult(
        appId=str(app.id),
        appVersion=app.version,
        catalogStatus=app.catalog_status or "missing",
        installerCount=installer_count,
        warnings=warnings,
    )


def raise_manual_installer_http_error(
    error: ManualInstallerError | ManualInstallerTransientError | SafeHttpError,
) -> None:
    if isinstance(error, ManualInstallerError):
        status_code = error.status_code
        code = error.code
    elif isinstance(error, ManualInstallerTransientError):
        status_code = 503
        code = error.code
    else:
        status_code = 503 if error.transient else 422
        code = error.code
    raise HTTPException(status_code=status_code, detail={"code": code}) from error


def raise_website_discovery_http_error(
    error: WebsiteDiscoveryError | WebsiteDiscoveryTransientError | SafeHttpError,
) -> None:
    if isinstance(error, WebsiteDiscoveryError):
        status_code = error.status_code
        code = error.code
    elif isinstance(error, WebsiteDiscoveryTransientError):
        status_code = 503
        code = error.code
    else:
        status_code = 503 if error.transient else 422
        code = error.code
    raise HTTPException(status_code=status_code, detail={"code": code}) from error


async def _run_descriptor_once_background() -> None:
    await DescriptorWorker(get_settings()).process_one()
