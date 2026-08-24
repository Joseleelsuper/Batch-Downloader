"""Ejecuta comprobaciones internas para procesos del scraper sin servidor HTTP."""

from __future__ import annotations

import argparse
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


async def worker_ready(
    role: str,
    max_age_seconds: float,
    failure_threshold: int,
) -> bool:
    """Comprueba antigüedad y fallos persistentes del scheduler."""
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
            await cursor.execute(
                """
                SELECT
                    TIMESTAMPDIFF(MICROSECOND, heartbeat_at, UTC_TIMESTAMP(6)) / 1000000,
                    consecutive_failures
                FROM scraper_worker_heartbeats
                WHERE role = %s
                """,
                (role.strip().lower().replace("_", "-"),),
            )
            row = await cursor.fetchone()
            return bool(
                row is not None
                and max(0.0, float(row[0])) <= max_age_seconds
                and int(row[1]) < failure_threshold
            )
    except Exception:
        return False
    finally:
        if connection is not None:
            connection.close()


def main() -> None:
    """Termina con código distinto de cero cuando MySQL no está listo."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker", choices=("scheduler",))
    parser.add_argument("--max-age", type=float)
    parser.add_argument("--failure-threshold", type=int)
    arguments = parser.parse_args()
    settings = get_settings()

    async def ready() -> bool:
        database = await database_ready()
        if not database or not arguments.worker:
            return database
        return await worker_ready(
            arguments.worker,
            arguments.max_age or settings.worker_heartbeat_stale_seconds,
            arguments.failure_threshold or settings.worker_failure_threshold,
        )

    raise SystemExit(0 if asyncio.run(ready()) else 1)


if __name__ == "__main__":
    main()
