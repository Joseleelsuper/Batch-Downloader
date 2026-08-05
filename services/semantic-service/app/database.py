"""Implementa las responsabilidades del módulo `database`.
"""
from __future__ import annotations

from collections.abc import Callable, Iterator
from contextlib import contextmanager
from pathlib import Path
from threading import RLock
from typing import TypeVar

from psycopg import Connection
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from app.config import Settings

T = TypeVar("T")
"""Constante que define `T`.
"""


class Database:
    """Representa el componente `Database`.
    """
    def __init__(self, settings: Settings) -> None:
        """Inicializa una instancia de `Database`.

        Args:
            settings (Settings): Configuración del servicio.
        """
        self.settings = settings
        """Estado de instancia asociado a `settings`.
        """
        minimum, maximum = settings.database_pool_limits
        if minimum > maximum:
            raise ValueError("semantic_database_pool_min_exceeds_max")
        self.pool = ConnectionPool(
            conninfo=settings.postgres_dsn,
            min_size=minimum,
            max_size=maximum,
            timeout=settings.db_pool_timeout_seconds,
            max_lifetime=settings.db_pool_max_lifetime_seconds,
            open=False,
            kwargs={"row_factory": dict_row},
        )
        """Estado de instancia asociado a `pool`.
        """
        self._pinned_connection: Connection | None = None
        """Conexión reutilizada durante una operación de fondo exclusiva."""
        self._pinned_lock = RLock()
        """Serializa el uso de la conexión compartida, incluido el heartbeat."""

    def open(self) -> None:
        """Ejecuta `open` dentro de `Database`.
        """
        self.pool.open(wait=True, timeout=60)

    def close(self) -> None:
        """Ejecuta `close` dentro de `Database`.
        """
        self.pool.close()

    def migrate(self) -> None:
        """Ejecuta `migrate` dentro de `Database`.
        """
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
        """Ejecuta `run` dentro de `Database`.

        Args:
            callback (Callable[[Connection], T]): Valor de `callback` utilizado por la operación.

        Returns:
            T: Resultado producido por la operación.
        """
        with self._pinned_lock:
            pinned = self._pinned_connection
            if pinned is not None:
                try:
                    result = callback(pinned)
                    pinned.commit()
                    return result
                except Exception:
                    pinned.rollback()
                    raise
        with self.pool.connection() as connection:
            result = callback(connection)
            connection.commit()
            return result

    @contextmanager
    def exclusive_background_operation(self) -> Iterator[None]:
        """Impide solapar indexación y preparación y fija una sola conexión."""
        with self._pinned_lock:
            if self._pinned_connection is not None:
                raise RuntimeError("semantic_background_operation_nested")
        with self.pool.connection() as connection:
            row = connection.execute(
                "SELECT pg_try_advisory_lock(%s) AS acquired",
                (4_242_019,),
            ).fetchone()
            if row is None or not row["acquired"]:
                raise RuntimeError("semantic_background_busy")
            with self._pinned_lock:
                self._pinned_connection = connection
            try:
                yield
            finally:
                with self._pinned_lock:
                    self._pinned_connection = None
                    connection.execute("SELECT pg_advisory_unlock(%s)", (4_242_019,))
                    connection.commit()

    def metrics(self) -> dict[str, int | float]:
        """Obtiene contadores numéricos del pool para Prometheus."""
        return {
            str(key): value
            for key, value in self.pool.get_stats().items()
            if isinstance(value, int | float)
        }

    def healthy(self) -> bool:
        """Ejecuta `healthy` dentro de `Database`.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        try:
            return bool(self.run(lambda connection: connection.execute("SELECT 1").fetchone()))
        except Exception:
            return False
