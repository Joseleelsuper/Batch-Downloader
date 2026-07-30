"""Implementa las responsabilidades del módulo `routes`.
"""
from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.app_mapper import to_details, to_list_item
from app.core.config import Settings, get_settings
from app.core.time import utc_now
from app.core.url_protector import UrlProtector
from app.db.session import AsyncSessionLocal, get_session
from app.repositories.catalog import CatalogRepository
from app.schemas.apps import (
    AppDetails,
    AppSearchResponse,
    CatalogStatsResponse,
    LastScrapeRun,
)
from app.scraper.catalog_fetcher import CatalogFetcher

router = APIRouter(prefix="/api")
"""Estado global asociado a `router`.
"""
PUBLIC_CATALOG_STATUSES = {"all", "available", "review", "missing"}
"""Constante que define `PUBLIC_CATALOG_STATUSES`.
"""
@router.get("/health")
async def health(settings: Settings = Depends(get_settings)) -> dict[str, str]:
    """Ejecuta la operación `health`.

    Args:
        settings (Settings): Configuración del servicio.

    Returns:
        dict[str, str]: Mapa con los datos producidos por la operación.
    """
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
    """Busca la operación `apps`.

    Args:
        query (str | None): Valor de `query` utilizado por la operación.
        status (str | None): Valor de `status` utilizado por la operación.
        sort (str): Valor de `sort` utilizado por la operación.
        page (int): Número de página solicitado.
        page_size (int): Número máximo de elementos incluidos en una página.
        session (AsyncSession): Sesión de base de datos utilizada por la operación.
        settings (Settings): Configuración del servicio.

    Returns:
        AppSearchResponse: Resultado producido por la operación.

    Throws:
        HTTPException: Si no puede completarse la operación bajo las condiciones requeridas.
    """
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
    """Obtiene la operación `catalog_stats`.

    Args:
        session (AsyncSession): Sesión de base de datos utilizada por la operación.
        settings (Settings): Configuración del servicio.

    Returns:
        CatalogStatsResponse: Resultado de `get_catalog_stats`.
    """
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
    """Obtiene la operación `app`.

    Args:
        app_id (str): Identificador de `app` utilizado por la operación.
        session (AsyncSession): Sesión de base de datos utilizada por la operación.
        settings (Settings): Configuración del servicio.

    Returns:
        AppDetails: Resultado de `get_app`.

    Throws:
        HTTPException: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    catalog = _catalog(session, settings)
    app = await catalog.get_app_by_public_id(app_id)
    if not app:
        raise HTTPException(status_code=404, detail={"code": "app_not_found", "status": "missing"})
    return to_details(app)


@router.post("/internal/scraper/run-once", status_code=202)
async def run_scraper_once(background_tasks: BackgroundTasks) -> dict[str, bool]:
    """Ejecuta la operación `scraper_once`.

    Args:
        background_tasks (BackgroundTasks): Valor de `background_tasks` utilizado por la operación.

    Returns:
        dict[str, bool]: Mapa con los datos producidos por la operación.
    """
    background_tasks.add_task(_run_scrape_once_background)
    return {"accepted": True}


def _catalog(session: AsyncSession, settings: Settings) -> CatalogRepository:
    """Ejecuta el paso interno `_catalog`.

    Args:
        session (AsyncSession): Sesión de base de datos utilizada por la operación.
        settings (Settings): Configuración del servicio.

    Returns:
        CatalogRepository: Resultado producido por la operación.
    """
    return CatalogRepository(session, UrlProtector(settings.url_protection_secret))


async def _run_scrape_once_background() -> None:
    """Ejecuta el paso interno `_run_scrape_once_background`.
    """
    settings = get_settings()
    async with AsyncSessionLocal() as session:
        await CatalogFetcher(settings, session).scrape_once(recover_running=True)
