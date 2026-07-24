from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Query
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.app_mapper import to_details, to_list_item
from app.core.config import Settings, get_settings
from app.core.time import utc_now
from app.core.url_protector import UrlProtector
from app.db.session import AsyncSessionLocal, get_session
from app.repositories.catalog import CatalogRepository
from app.repositories.pipeline import PipelineRepository
from app.schemas.apps import (
    AppDetails,
    AppSearchResponse,
    CatalogStatsResponse,
    LastScrapeRun,
)
from app.scraper.catalog_fetcher import CatalogFetcher, DescriptorWorker, enqueue_descriptor_for_app

router = APIRouter(prefix="/api")
PUBLIC_CATALOG_STATUSES = {"all", "available", "review", "missing"}


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
    normalized_status = status.strip().lower() if status else None
    if normalized_status and normalized_status not in PUBLIC_CATALOG_STATUSES:
        raise HTTPException(
            status_code=400,
            detail={"code": "invalid_catalog_status"},
        )
    catalog = _catalog(session, settings)
    apps, total = await catalog.search_apps(
        query=query,
        status=normalized_status,
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


@router.post("/internal/scraper/run-once", status_code=202)
async def run_scraper_once(background_tasks: BackgroundTasks) -> dict[str, bool]:
    background_tasks.add_task(_run_scrape_once_background)
    return {"accepted": True}


@router.post("/internal/descriptions/generate", status_code=202)
async def generate_description(
    request: GenerateDescriptionRequest,
    background_tasks: BackgroundTasks,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> dict[str, str | None]:
    catalog = _catalog(session, settings)
    app = await catalog.get_app_by_public_id(request.appId)
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
        raise HTTPException(status_code=409, detail={"code": "description_already_current"})
    await session.commit()
    background_tasks.add_task(_run_descriptor_once_background)
    return {
        "jobId": str(item.id),
        "status": item.status,
    }


def _catalog(session: AsyncSession, settings: Settings) -> CatalogRepository:
    return CatalogRepository(session, UrlProtector(settings.url_protection_secret))


async def _run_scrape_once_background() -> None:
    settings = get_settings()
    async with AsyncSessionLocal() as session:
        await CatalogFetcher(settings, session).scrape_once(recover_running=True)


async def _run_descriptor_once_background() -> None:
    settings = get_settings()
    await DescriptorWorker(settings).process_one()
