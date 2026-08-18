"""Comprueba procesos semánticos que no exponen un servidor HTTP."""

from __future__ import annotations

import argparse
import tempfile
from pathlib import Path

from psycopg import connect

from app.config import get_settings


def database_ready() -> bool:
    """Comprueba PostgreSQL con una conexión breve fuera del pool del proceso."""
    settings = get_settings()
    try:
        with connect(settings.postgres_dsn, connect_timeout=3) as connection:
            return connection.execute("SELECT 1").fetchone() == (1,)
    except Exception:
        return False


def directory_writable(path: str) -> bool:
    """Comprueba mediante un temporal que un directorio de trabajo es escribible."""
    directory = Path(path)
    try:
        directory.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=directory):
            return True
    except OSError:
        return False


def main() -> None:
    """Finaliza con error si la base o un directorio requerido no están listos."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--writable", action="append", default=[])
    arguments = parser.parse_args()
    ready = database_ready() and all(
        directory_writable(path) for path in arguments.writable
    )
    raise SystemExit(0 if ready else 1)


if __name__ == "__main__":
    main()
