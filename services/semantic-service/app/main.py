"""Compone routers de salud, búsqueda y administración con un único ciclo de vida del pool y
caché local.
"""
from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from psycopg_pool import PoolTimeout

from app.admin_router import router as admin_router
from app.health_router import router as health_router
from app.http_context import lifespan
from app.search_router import router as search_router

app = FastAPI(title="Batch Downloader Semantic Service", lifespan=lifespan)
app.include_router(health_router)
app.include_router(search_router)
app.include_router(admin_router)


@app.exception_handler(PoolTimeout)
async def database_capacity_exhausted(
    _request: Request,
    _exception: PoolTimeout,
) -> JSONResponse:
    """Convierte el agotamiento de conexiones en un fallo temporal con Retry-After de un segundo.

    Args:
        _request: Petición cuyo acceso al pool agotó el tiempo de espera.
        _exception: Excepción de capacidad capturada; su contenido no se expone en el cuerpo
            HTTP.

    Returns:
        503 service_busy con mensaje seguro, sin detalles de la conexión.
    """
    return JSONResponse(
        status_code=503,
        content={"code": "service_busy", "message": "Capacidad temporal agotada."},
        headers={"Retry-After": "1"},
    )


