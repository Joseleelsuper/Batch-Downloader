"""Configura el punto de entrada del scraper.
"""
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

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
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["*"],
)
app.include_router(router)
app.include_router(internal_router)
