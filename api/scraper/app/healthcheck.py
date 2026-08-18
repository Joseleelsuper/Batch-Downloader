"""Ejecuta comprobaciones internas para procesos del scraper sin servidor HTTP."""

from __future__ import annotations

import asyncio

import aiomysql

from app.core.config import get_settings


async def database_ready() -> bool:
    """Comprueba que MySQL acepta autenticación y una consulta del scraper."""
    settings = get_settings()
    connection = None
    try:
        connection = await aiomysql.connect(
            host=settings.database_host,
            port=settings.database_port,
            user=settings.database_username,
            password=settings.database_password.get_secret_value(),
            db=settings.database_name,
            connect_timeout=3,
        )
        async with connection.cursor() as cursor:
            await cursor.execute("SELECT 1")
            return (await cursor.fetchone()) == (1,)
    except Exception:
        return False
    finally:
        if connection is not None:
            connection.close()


def main() -> None:
    """Termina con código distinto de cero cuando MySQL no está listo."""
    raise SystemExit(0 if asyncio.run(database_ready()) else 1)


if __name__ == "__main__":
    main()
