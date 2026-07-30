"""Implementa las responsabilidades del módulo `session`.
"""
from collections.abc import AsyncIterator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core.config import get_settings

settings = get_settings()
"""Estado global asociado a `settings`.
"""

engine = create_async_engine(settings.database_url, pool_pre_ping=True)
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
