"""Implementa las responsabilidades del módulo `database`.
"""
from __future__ import annotations

from collections.abc import Callable, Iterator
from contextlib import contextmanager
from hashlib import sha256
from pathlib import Path
from threading import RLock
from typing import Any, TypeVar

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
        self.pool: ConnectionPool[Connection[dict[str, Any]]] = ConnectionPool(
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
        self._pinned_connection: Connection[dict[str, Any]] | None = None
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
        """Aplica migraciones con lock global y fija el checksum de cada versión."""
        migrations = self._migration_files()
        with self.pool.connection() as connection:
            connection.execute("SELECT pg_advisory_lock(%s)", (4_242_018,))
            try:
                connection.execute(
                    """
                    CREATE TABLE IF NOT EXISTS semantic_schema_migrations (
                        version TEXT PRIMARY KEY,
                        checksum CHAR(64),
                        applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """
                )
                connection.execute(
                    """
                    ALTER TABLE semantic_schema_migrations
                    ADD COLUMN IF NOT EXISTS checksum CHAR(64)
                    """
                )
                connection.commit()
                applied_rows = connection.execute(
                    "SELECT version, checksum FROM semantic_schema_migrations"
                ).fetchall()
                applied = {row["version"]: row["checksum"] for row in applied_rows}
                missing_files = sorted(set(applied) - set(migrations))
                if missing_files:
                    raise RuntimeError(
                        "semantic_migration_file_missing:" + ",".join(missing_files)
                    )
                for version, stored_checksum in applied.items():
                    expected_checksum, _sql = migrations[version]
                    if stored_checksum is None:
                        connection.execute(
                            """
                            UPDATE semantic_schema_migrations
                            SET checksum = %s
                            WHERE version = %s AND checksum IS NULL
                            """,
                            (expected_checksum, version),
                        )
                    elif stored_checksum != expected_checksum:
                        raise RuntimeError(
                            f"semantic_migration_checksum_mismatch:{version}"
                        )
                connection.commit()
                for version, (checksum, sql) in migrations.items():
                    if version in applied:
                        continue
                    connection.execute(sql)
                    connection.execute(
                        """
                        INSERT INTO semantic_schema_migrations(version, checksum)
                        VALUES (%s, %s)
                        """,
                        (version, checksum),
                    )
                    connection.commit()
            finally:
                connection.rollback()
                connection.execute("SELECT pg_advisory_unlock(%s)", (4_242_018,))
                connection.commit()

    def verify_schema(self) -> None:
        """Comprueba versión y checksums sin modificar el esquema."""
        migrations = self._migration_files()

        def verify(connection: Connection[dict[str, Any]]) -> None:
            table = connection.execute(
                "SELECT to_regclass('public.semantic_schema_migrations') AS name"
            ).fetchone()
            if table is None or table["name"] is None:
                raise RuntimeError("semantic_schema_not_migrated")
            columns = {
                row["column_name"]
                for row in connection.execute(
                    """
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'semantic_schema_migrations'
                    """
                ).fetchall()
            }
            if "checksum" not in columns:
                raise RuntimeError("semantic_schema_checksum_missing")
            rows = connection.execute(
                "SELECT version, checksum FROM semantic_schema_migrations"
            ).fetchall()
            applied = {row["version"]: row["checksum"] for row in rows}
            if set(applied) != set(migrations):
                raise RuntimeError("semantic_schema_version_mismatch")
            for version, (expected_checksum, _sql) in migrations.items():
                if applied[version] != expected_checksum:
                    raise RuntimeError(
                        f"semantic_migration_checksum_mismatch:{version}"
                    )

        self.run(verify)

    @staticmethod
    def _migration_files() -> dict[str, tuple[str, str]]:
        migration_dir = Path(__file__).resolve().parents[1] / "migrations"
        migrations: dict[str, tuple[str, str]] = {}
        for migration in sorted(migration_dir.glob("*.sql")):
            raw = migration.read_bytes()
            migrations[migration.stem] = (
                sha256(raw).hexdigest(),
                raw.decode("utf-8"),
            )
        return migrations

    def run(self, callback: Callable[[Connection[dict[str, Any]]], T]) -> T:
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
