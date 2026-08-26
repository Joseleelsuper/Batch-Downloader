"""Implementa las responsabilidades del módulo `session`.
"""
from collections.abc import AsyncIterator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core.config import get_settings

settings = get_settings()
"""Estado global asociado a `settings`.
"""

engine = create_async_engine(
    settings.database_url,
    pool_pre_ping=True,
    pool_size=settings.database_pool_max,
    max_overflow=settings.database_max_overflow,
    pool_timeout=settings.database_pool_timeout_seconds,
    pool_recycle=settings.database_pool_recycle_seconds,
)
"""Estado global asociado a `engine`.
"""
AsyncSessionLocal = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)
"""Estado global asociado a `AsyncSessionLocal`.
"""


async def get_session() -> AsyncIterator[AsyncSession]:
    """Obtiene la operación `session`.

    Yields:
        AsyncIterator[AsyncSession]: Elemento producido por la operación.
    """
    async with AsyncSessionLocal() as session:
        yield session
