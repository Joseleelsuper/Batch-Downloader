"""Configura el entorno de migraciones de `env`.
"""
import asyncio
from logging.config import fileConfig

from sqlalchemy import pool
from sqlalchemy.ext.asyncio import create_async_engine

from alembic import context
from app.core.config import get_settings
from app.db import models  # noqa: F401
from app.db.base import Base

config = context.config
"""Estado global asociado a `config`.
"""

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

settings = get_settings()
"""Estado global asociado a `settings`.
"""
target_metadata = Base.metadata
"""Estado global asociado a `target_metadata`.
"""


def run_migrations_offline() -> None:
    """Ejecuta la operación `migrations_offline`.
    """
    context.configure(
        url=settings.database_url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )

    with context.begin_transaction():
        context.run_migrations()


def do_run_migrations(connection) -> None:
    """Ejecuta la operación `do_run_migrations`.

    Args:
        connection (Any): Conexión de base de datos utilizada por la operación.
    """
    context.configure(connection=connection, target_metadata=target_metadata)

    with context.begin_transaction():
        context.run_migrations()


async def run_migrations_online() -> None:
    """Ejecuta la operación `migrations_online`.
    """
    connectable = create_async_engine(settings.database_url, poolclass=pool.NullPool)

    async with connectable.connect() as connection:
        await connection.run_sync(do_run_migrations)

    await connectable.dispose()


if context.is_offline_mode():
    run_migrations_offline()
else:
    asyncio.run(run_migrations_online())
