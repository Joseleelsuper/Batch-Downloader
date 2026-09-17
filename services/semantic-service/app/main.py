"""Aplicacion HTTP del servicio semantico."""
from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from psycopg_pool import PoolTimeout

from app.health_router import router as health_router
from app.http_context import lifespan
from app.search_router import router as search_router

app = FastAPI(title="Batch Downloader Semantic Service", lifespan=lifespan)
app.include_router(health_router)
app.include_router(search_router)


@app.exception_handler(PoolTimeout)
async def database_capacity_exhausted(
    _request: Request,
    _exception: PoolTimeout,
) -> JSONResponse:
    return JSONResponse(
        status_code=503,
        content={"code": "service_busy", "message": "Capacidad temporal agotada."},
        headers={"Retry-After": "1"},
    )
