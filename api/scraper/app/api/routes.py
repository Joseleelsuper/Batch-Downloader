"""Expone salud general, vida del proceso y disponibilidad de base de datos con responsabilidades
separadas.
"""

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from sqlalchemy import text

from app.core.config import Settings, get_settings
from app.db.session import AsyncSessionLocal
from app.repositories.heartbeat import WorkerHeartbeatRepository

router = APIRouter(prefix="/api")

@router.get("/health")
async def health(settings: Settings = Depends(get_settings)) -> dict[str, object]:
    """Consulta el latido persistido del scheduler y presenta salud degradada si falta, está
    obsoleto, acumula fallos o falla la consulta.

    Args:
        settings: Configuración validada de salud, límites y credenciales internas del
            servicio.

    Returns:
        estado textual, nombre de servicio y diagnóstico del scheduler disponible; esta ruta
            mantiene respuesta HTTP 200.
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
    """Confirma que el proceso HTTP puede responder sin consultar base de datos ni workers.

    Args:
        settings: Configuración validada de salud, límites y credenciales internas del
            servicio.

    Returns:
        estado ok y nombre del servicio.
    """
    return {"status": "ok", "service": settings.app_name}


async def database_ready() -> bool:
    """Abre una sesión independiente y comprueba SELECT 1, cerrándola tanto en éxito como en
    fallo.

    Returns:
        True si la base responde y False ante cualquier excepción.
    """
    try:
        async with AsyncSessionLocal() as session:
            await session.execute(text("SELECT 1"))
        return True
    except Exception:
        return False


@router.get("/health/ready")
async def health_ready(settings: Settings = Depends(get_settings)) -> JSONResponse:
    """Traduce disponibilidad de base de datos a un estado HTTP apropiado para readiness.

    Args:
        settings: Configuración validada de salud, límites y credenciales internas del
            servicio.

    Returns:
        respuesta 200 si la base está disponible o 503 con estado degraded en caso contrario.
    """
    ready = await database_ready()
    return JSONResponse(
        status_code=200 if ready else 503,
        content={
            "status": "ok" if ready else "degraded",
            "service": settings.app_name,
            "database": ready,
        },
    )
