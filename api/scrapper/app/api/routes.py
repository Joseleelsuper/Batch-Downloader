from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import JSONResponse, RedirectResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.app_mapper import best_resolved_source, to_details, to_list_item
from app.core.config import Settings, get_settings
from app.core.time import utc_now
from app.core.url_protector import UrlProtector
from app.db.enums import ResolutionStatus
from app.db.session import get_session
from app.repositories.catalog import CatalogRepository
from app.schemas.apps import AppDetails, AppSearchResponse, ErrorResponse

router = APIRouter(prefix="/api")


@router.get("/health")
async def health(settings: Settings = Depends(get_settings)) -> dict[str, str]:
    return {"status": "ok", "service": settings.app_name}


@router.get("/apps", response_model=AppSearchResponse, response_model_by_alias=True)
async def search_apps(
    query: str | None = None,
    status: str | None = Query(default=None),
    page: int = Query(default=1, ge=1),
    page_size: int = Query(default=20, ge=1, le=100),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> AppSearchResponse:
    catalog = _catalog(session, settings)
    apps, total = await catalog.search_apps(query=query, status=status, page=page, page_size=page_size)
    return AppSearchResponse(
        data=[to_list_item(app) for app in apps],
        page=page,
        pageSize=page_size,
        total=total,
    )


@router.get("/apps/{app_id}", response_model=AppDetails, response_model_by_alias=True)
async def get_app(
    app_id: str,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> AppDetails:
    catalog = _catalog(session, settings)
    app = await catalog.get_app_by_public_id(app_id)
    if not app:
        raise HTTPException(status_code=404, detail={"code": "app_not_found", "status": "missing"})
    return to_details(app)


@router.get(
    "/apps/{app_id}/download",
    responses={409: {"model": ErrorResponse}, 404: {"model": ErrorResponse}},
)
async def download_app(
    app_id: str,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
):
    catalog = _catalog(session, settings)
    app = await catalog.get_app_by_public_id(app_id)
    if not app:
        return JSONResponse(
            status_code=404,
            content=ErrorResponse(
                code="app_not_found",
                status=ResolutionStatus.MISSING.value,
                message="La aplicacion no existe.",
            ).model_dump(),
        )

    resolved = best_resolved_source(app)
    if not resolved:
        status, _ = next(
            ((source.resolution_status, source.validation_status) for source in app.sources),
            (ResolutionStatus.MISSING.value, "unchecked"),
        )
        return JSONResponse(
            status_code=409,
            content=ErrorResponse(
                code="installer_unavailable",
                status=status,
                message="El instalador necesita revision manual."
                if status == ResolutionStatus.REQUIRES_MANUAL_REVIEW.value
                else "No hay un instalador disponible.",
            ).model_dump(),
        )

    if resolved.expires_at <= utc_now():
        return JSONResponse(
            status_code=409,
            content=ErrorResponse(
                code="installer_expired",
                status=ResolutionStatus.BROKEN.value,
                message="La URL del instalador ha expirado. Ejecuta el scraper de nuevo.",
            ).model_dump(),
        )

    url = catalog.reveal_url(resolved)
    if not url:
        return JSONResponse(
            status_code=409,
            content=ErrorResponse(
                code="installer_unavailable",
                status=ResolutionStatus.BROKEN.value,
                message="No se pudo descifrar la URL del instalador.",
            ).model_dump(),
        )
    return RedirectResponse(url=url, status_code=307)


def _catalog(session: AsyncSession, settings: Settings) -> CatalogRepository:
    return CatalogRepository(session, UrlProtector(settings.url_protection_secret))
