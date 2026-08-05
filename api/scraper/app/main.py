"""Configura el punto de entrada del scraper.
"""
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy.exc import TimeoutError as SqlAlchemyTimeoutError

from app.api.internal_routes import internal_router
from app.api.routes import router
from app.core.config import get_settings
from app.core.logging import configure_logging

configure_logging()

settings = get_settings()
"""Estado global asociado a `settings`.
"""

app = FastAPI(title=settings.app_name)
"""Estado global asociado a `app`.
"""


@app.exception_handler(SqlAlchemyTimeoutError)
async def database_capacity_exhausted(
    _request: Request,
    _exception: SqlAlchemyTimeoutError,
) -> JSONResponse:
    """Devuelve una saturación temporal en vez de acumular esperas en MySQL."""
    return JSONResponse(
        status_code=503,
        content={"code": "service_busy", "message": "Capacidad temporal agotada."},
        headers={"Retry-After": "1"},
    )
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["*"],
)
app.include_router(router)
app.include_router(internal_router)
