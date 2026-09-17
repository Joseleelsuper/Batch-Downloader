"""Construye el motor asíncrono y la factoría de sesiones del proceso con pre-ping, límites de
pool y reciclado.
Los workers deben crear sesiones propias; compartir esta factoría no permite compartir una
sesión entre tareas simultáneas.

See Also:
    app.core.config.Settings: Define destino y límites del pool.
    get_session: Cede una sesión independiente a cada dependencia HTTP.
"""
from collections.abc import AsyncIterator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core.config import get_settings

settings = get_settings()


engine = create_async_engine(
    settings.database_url,
    pool_pre_ping=True,
    pool_size=settings.database_pool_max,
    max_overflow=settings.database_max_overflow,
    pool_timeout=settings.database_pool_timeout_seconds,
    pool_recycle=settings.database_pool_recycle_seconds,
)

AsyncSessionLocal = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)



async def get_session() -> AsyncIterator[AsyncSession]:
    """Abre una sesión por dependencia y la cierra al terminar la petición; el caso de uso decide
    cuándo confirmar su transacción.

    Yields:
        sesión asíncrona con expire_on_commit desactivado.
    """
    async with AsyncSessionLocal() as session:
        yield session
