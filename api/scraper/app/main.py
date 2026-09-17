"""Compone la API pública e interna del scraper y convierte agotamiento del pool en una respuesta
HTTP temporalmente reintentable.
"""
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from sqlalchemy.exc import TimeoutError as SqlAlchemyTimeoutError

from app.api.internal_routes import internal_router
from app.api.linux_install_routes import router as linux_install_router
from app.api.routes import router
from app.core.config import get_settings
from app.core.logging import configure_logging

configure_logging()

settings = get_settings()


app = FastAPI(title=settings.app_name)



@app.exception_handler(SqlAlchemyTimeoutError)
async def database_capacity_exhausted(
    _request: Request,
    _exception: SqlAlchemyTimeoutError,
) -> JSONResponse:
    """Oculta detalles del pool y comunica saturación temporal para que el cliente pueda
    reintentar.

    Args:
        _request: Petición cuyo acceso al pool ha agotado la espera; no se expone su
            contenido.
        _exception: Timeout del pool SQLAlchemy; se ocultan sus detalles internos en HTTP.

    Returns:
        respuesta 503 con service_busy y Retry-After de un segundo.
    """
    return JSONResponse(
        status_code=503,
        content={"code": "service_busy", "message": "Capacidad temporal agotada."},
        headers={"Retry-After": "1"},
    )
app.include_router(router)
app.include_router(internal_router)
app.include_router(linux_install_router)
