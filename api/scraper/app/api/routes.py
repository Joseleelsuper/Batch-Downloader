"""Expone exclusivamente los healthchecks públicos del scraper."""

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from sqlalchemy import text

from app.core.config import Settings, get_settings
from app.db.session import AsyncSessionLocal
from app.repositories.heartbeat import WorkerHeartbeatRepository

router = APIRouter(prefix="/api")
"""Estado global asociado a `router`.
"""
@router.get("/health")
async def health(settings: Settings = Depends(get_settings)) -> dict[str, object]:
    """Ejecuta la operación `health`.

    Args:
        settings (Settings): Configuración del servicio.

    Returns:
        dict[str, object]: Mapa con los datos producidos por la operación.
    """
    try:
        async with AsyncSessionLocal() as session:
            scheduler = await WorkerHeartbeatRepository(session).status(
                "scheduler",
                max_age_seconds=settings.worker_heartbeat_stale_seconds,
                failure_threshold=settings.worker_failure_threshold,
            )
    except Exception:
        scheduler = None
    return {
        "status": "ok" if scheduler and scheduler.healthy else "degraded",
        "service": settings.app_name,
        "workers": {"scheduler": scheduler.as_dict()} if scheduler else {},
    }


@router.get("/health/live")
async def health_live(settings: Settings = Depends(get_settings)) -> dict[str, str]:
    """Confirma que el proceso HTTP y su bucle de eventos siguen respondiendo."""
    return {"status": "ok", "service": settings.app_name}


async def database_ready() -> bool:
    """Comprueba MySQL con una consulta real y una conexión del pool de la API."""
    try:
        async with AsyncSessionLocal() as session:
            await session.execute(text("SELECT 1"))
        return True
    except Exception:
        return False


@router.get("/health/ready")
async def health_ready(settings: Settings = Depends(get_settings)) -> JSONResponse:
    """Indica si la API puede atender operaciones respaldadas por MySQL."""
    ready = await database_ready()
    return JSONResponse(
        status_code=200 if ready else 503,
        content={
            "status": "ok" if ready else "degraded",
            "service": settings.app_name,
            "database": ready,
        },
    )
