from __future__ import annotations

import secrets
from typing import Annotated

from fastapi import APIRouter, Depends, Header, HTTPException
from fastapi.responses import JSONResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings, get_settings
from app.core.time import utc_now
from app.core.url_protector import UrlProtector
from app.db.session import get_session
from app.domain.source_resolution import SourceTrustStatus, source_trust_status
from app.repositories.catalog import CatalogRepository
from app.schemas.internal import InternalSourceResolution

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
    if trust_status == SourceTrustStatus.VERIFIED and url is None:
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
        trustStatus=trust_status.value,
    )
    if trust_status != SourceTrustStatus.VERIFIED:
        return JSONResponse(
            status_code=409,
            content=response.model_dump(by_alias=True, mode="json"),
        )
    return response
