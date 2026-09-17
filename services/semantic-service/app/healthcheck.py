"""Sonda de contenedor para base de datos, modelo local y latido del indexador."""
from __future__ import annotations

import argparse
import tempfile
from pathlib import Path

from psycopg import connect

from app.config import get_settings


def database_ready() -> bool:
    settings = get_settings()
    try:
        with connect(settings.postgres_dsn, connect_timeout=3) as connection:
            return connection.execute("SELECT 1").fetchone() == (1,)
    except Exception:
        return False


def directory_writable(path: str) -> bool:
    """Comprueba que un directorio permite crear y cerrar un temporal."""
    try:
        directory = Path(path)
        directory.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=directory):
            return True
    except OSError:
        return False


def worker_ready(max_age_seconds: float, failure_threshold: int) -> bool:
    settings = get_settings()
    try:
        with connect(settings.postgres_dsn, connect_timeout=3) as connection:
            row = connection.execute(
                """
                SELECT EXTRACT(EPOCH FROM (now() - heartbeat_at)), consecutive_failures
                FROM semantic_worker_heartbeats WHERE role = 'indexer'
                """
            ).fetchone()
        return bool(
            row is not None
            and float(row[0]) <= max_age_seconds
            and int(row[1]) < failure_threshold
        )
    except Exception:
        return False


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--writable", action="append", default=[])
    parser.add_argument("--worker", choices=("indexer",))
    parser.add_argument("--max-age", type=float)
    parser.add_argument("--failure-threshold", type=int)
    arguments = parser.parse_args()
    settings = get_settings()
    ready = database_ready() and all(directory_writable(path) for path in arguments.writable)
    if ready and arguments.worker:
        ready = worker_ready(
            arguments.max_age or settings.worker_heartbeat_stale_seconds,
            arguments.failure_threshold or settings.worker_failure_threshold,
        )
    raise SystemExit(0 if ready else 1)


if __name__ == "__main__":
    main()
