from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
from typing import TypeVar

from psycopg import Connection
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from app.config import Settings

T = TypeVar("T")


class Database:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.pool = ConnectionPool(
            conninfo=settings.postgres_dsn,
            min_size=1,
            max_size=8,
            open=False,
            kwargs={"row_factory": dict_row},
        )

    def open(self) -> None:
        self.pool.open(wait=True, timeout=60)

    def close(self) -> None:
        self.pool.close()

    def migrate(self) -> None:
        migration_dir = Path(__file__).resolve().parents[1] / "migrations"
        with self.pool.connection() as connection:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS semantic_schema_migrations (
                    version TEXT PRIMARY KEY,
                    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """
            )
            applied = {
                row["version"]
                for row in connection.execute(
                    "SELECT version FROM semantic_schema_migrations"
                ).fetchall()
            }
            for migration in sorted(migration_dir.glob("*.sql")):
                if migration.stem in applied:
                    continue
                connection.execute(migration.read_text(encoding="utf-8"))
                connection.execute(
                    "INSERT INTO semantic_schema_migrations(version) VALUES (%s)",
                    (migration.stem,),
                )
            connection.commit()

    def run(self, callback: Callable[[Connection], T]) -> T:
        with self.pool.connection() as connection:
            result = callback(connection)
            connection.commit()
            return result

    def healthy(self) -> bool:
        try:
            return bool(self.run(lambda connection: connection.execute("SELECT 1").fetchone()))
        except Exception:
            return False
