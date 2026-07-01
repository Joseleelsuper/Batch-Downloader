from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Query
from fastapi.responses import JSONResponse, RedirectResponse
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.app_mapper import best_resolved_source, to_details, to_list_item
from app.core.config import Settings, get_settings
from app.core.time import utc_now
from app.core.url_protector import UrlProtector
from app.db.enums import LongDescriptionStatus, ResolutionStatus
from app.db.session import AsyncSessionLocal, get_session
from app.repositories.catalog import CatalogRepository
from app.repositories.logs import ResolverLogRepository
from app.schemas.apps import (
    AppDetails,
    AppSearchResponse,
    CatalogStatsResponse,
    LastScrapeRun,
    ErrorResponse,
)
from app.scraper.catalog_fetcher import CatalogFetcher
from app.scraper.description_enricher import (
    AppDescriptionLLMClient,
    LLMGenerationError,
    description_evidence,
    description_input_hash,
    fetch_safe_page_metadata,
)

router = APIRouter(prefix="/api")


class GenerateDescriptionRequest(BaseModel):
    appId: str


@router.get("/health")
async def health(settings: Settings = Depends(get_settings)) -> dict[str, str]:
    return {"status": "ok", "service": settings.app_name}


@router.get("/apps", response_model=AppSearchResponse, response_model_by_alias=True)
async def search_apps(
    query: str | None = None,
    status: str | None = Query(default=None),
    sort: str = Query(default="name", pattern="^(name|updated)$"),
    page: int = Query(default=1, ge=1),
    page_size: int = Query(default=20, ge=1, le=100),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> AppSearchResponse:
    catalog = _catalog(session, settings)
    apps, total = await catalog.search_apps(
        query=query,
        status=status,
        page=page,
        page_size=page_size,
        sort=sort,
    )
    return AppSearchResponse(
        data=[to_list_item(app) for app in apps],
        page=page,
        pageSize=page_size,
        total=total,
    )


@router.get("/apps/stats", response_model=CatalogStatsResponse, response_model_by_alias=True)
async def get_catalog_stats(
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> CatalogStatsResponse:
    catalog = _catalog(session, settings)
    stats = await catalog.catalog_stats()
    last_run = stats["last_run"]
    return CatalogStatsResponse(
        total=stats["total"],
        filters=stats["filters"],
        lastScrape=LastScrapeRun.model_validate(last_run, from_attributes=True)
        if last_run
        else None,
        generatedAt=utc_now(),
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


@router.post("/internal/scraper/run-once", status_code=202)
async def run_scraper_once(background_tasks: BackgroundTasks) -> dict[str, bool]:
    background_tasks.add_task(_run_scrape_once_background)
    return {"accepted": True}


@router.post("/internal/descriptions/generate")
async def generate_description(
    request: GenerateDescriptionRequest,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> dict[str, str | None]:
    catalog = _catalog(session, settings)
    app = await catalog.get_app_by_public_id(request.appId)
    if not app:
        raise HTTPException(status_code=404, detail={"code": "app_not_found"})

    input_hash = description_input_hash(app)
    metadata = await fetch_safe_page_metadata(
        app.official_url,
        timeout=settings.request_timeout_seconds,
    )
    llm = AppDescriptionLLMClient(settings)
    logs = ResolverLogRepository(session)
    if not llm.has_provider():
        await catalog.mark_long_description_failed(
            app.id,
            input_hash,
            "llm_provider_not_configured",
        )
        await logs.add(
            phase="description",
            status=LongDescriptionStatus.FAILED.value,
            message="llm_provider_not_configured",
            safe_metadata={"input_hash": input_hash},
        )
        await session.commit()
        raise HTTPException(status_code=409, detail={"code": "llm_provider_not_configured"})

    try:
        generated = await llm.generate(description_evidence(app, metadata))
    except LLMGenerationError as exc:
        await catalog.mark_long_description_failed(
            app.id,
            input_hash,
            exc.reason,
            source=exc.provider,
            model=exc.model,
        )
        await logs.add(
            phase="description",
            status=LongDescriptionStatus.FAILED.value,
            message=exc.reason,
            safe_metadata={"input_hash": input_hash, "provider": exc.provider, "model": exc.model},
        )
        await session.commit()
        raise HTTPException(status_code=409, detail={"code": exc.reason})

    await catalog.save_long_description(
        software_app_id=app.id,
        description=generated.description,
        language=generated.language,
        source=generated.provider,
        model=generated.model,
        input_hash=input_hash,
    )
    await logs.add(
        phase="description",
        status=LongDescriptionStatus.COMPLETED.value,
        safe_metadata={
            "input_hash": input_hash,
            "provider": generated.provider,
            "model": generated.model,
        },
    )
    await session.commit()
    return {
        "longDescription": generated.description,
        "language": generated.language,
        "provider": generated.provider,
        "model": generated.model,
    }


def _catalog(session: AsyncSession, settings: Settings) -> CatalogRepository:
    return CatalogRepository(session, UrlProtector(settings.url_protection_secret))


async def _run_scrape_once_background() -> None:
    settings = get_settings()
    async with AsyncSessionLocal() as session:
        await CatalogFetcher(settings, session).scrape_once(recover_running=True)
