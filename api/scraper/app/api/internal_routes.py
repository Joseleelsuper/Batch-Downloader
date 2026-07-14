from __future__ import annotations

import secrets
from typing import Annotated
from urllib.parse import urlparse

from fastapi import APIRouter, Depends, Header, HTTPException
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
from app.schemas.internal import ContentEnqueueResult, InternalSourceResolution
from app.scraper.candidates import InstallerCandidate
from app.scraper.catalog_fetcher import enqueue_descriptor_for_app
from app.scraper.validator import DownloadValidator, ValidationConfidence

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
    "/sources/{source_ref}/resolution",
    response_model=InternalSourceResolution,
    response_model_by_alias=True,
    responses={401: {}, 404: {}, 409: {"model": InternalSourceResolution}},
)
async def get_source_resolution(
    source_ref: str,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> InternalSourceResolution | JSONResponse:
    catalog = CatalogRepository(session, UrlProtector(settings.url_protection_secret))
    resolved = await catalog.get_resolved_source_by_ref(source_ref)
    if resolved is None:
        raise HTTPException(status_code=404, detail={"code": "source_not_found"})

    metadata = resolved.metadata_json or {}
    trust_status = source_trust_status(
        validation_status=resolved.validation_status,
        resolution_status=resolved.status,
        expires_at=resolved.expires_at,
        metadata=metadata,
        now=utc_now(),
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
        url = None
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
        trustStatus=trust_status.value,
    )
    if trust_status != SourceTrustStatus.VERIFIED:
        return JSONResponse(
            status_code=409,
            content=response.model_dump(by_alias=True, mode="json"),
        )
    return response


def _can_revalidate_expired(resolved: ResolvedSource, metadata: dict) -> bool:
    confidence = str(metadata.get("validation_confidence") or "").lower()
    return (
        resolved.validation_status == ValidationStatus.VALID.value
        and resolved.status
        in {ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value}
        and resolved.expires_at <= utc_now()
        and confidence in {"", "validated", "verified"}
        and metadata.get("transport_security") != "https_winstall_edge_attested"
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
    protected_url = catalog.reveal_url(resolved)
    if protected_url is None:
        return None
    metadata = dict(resolved.metadata_json or {})
    candidate = InstallerCandidate(
        url=protected_url,
        source=str(metadata.get("candidate_source") or "internal_revalidation"),
        label=str(metadata.get("candidate_label") or "") or None,
        asset_kind=str(metadata.get("asset_kind") or "") or None,
        referer=resolved.source.initial_url,
    )
    result = await DownloadValidator(settings).validate(candidate)
    now = utc_now()
    resolved.checked_at = now
    final_url = result.final_url or protected_url
    if (
        not result.ok
        or result.confidence != ValidationConfidence.VALIDATED
        or urlparse(final_url).scheme != "https"
    ):
        resolved.validation_status = ValidationStatus.EXPIRED.value
        resolved.expires_at = now
        metadata["last_revalidation_error"] = (
            result.reason
            or ("source_not_https" if urlparse(final_url).scheme != "https" else None)
            or "source_not_verified"
        )
        resolved.metadata_json = metadata
        await session.commit()
        return None

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
