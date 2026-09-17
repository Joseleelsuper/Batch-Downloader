"""Controla el pool PostgreSQL, las migraciones y la exclusion de indexacion."""
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



class Database:
    """Presta conexiones transaccionales y coordina operaciones de fondo mediante locks
    consultivos de PostgreSQL.
    El pool se crea cerrado. Durante una operación exclusiva fija una conexión y serializa su
    uso
    con un RLock para permitir progreso y latidos sin consumir conexiones adicionales.

    Attributes:
        settings: Límites y credenciales validados del proceso.
        pool: Pool de conexiones que devuelve filas como diccionarios.
        _pinned_connection: Conexión fijada solo mientras dura una operación exclusiva.
        _pinned_lock: Protección reentrante del acceso a la conexión fijada.

    See Also:
        app.http_context.lifespan: Abre y verifica el pool del API.
        app.indexer.SemanticIndexer: Coordina el trabajo de indexacion.
    """
    def __init__(self, settings: Settings) -> None:
        """Crea un pool cerrado con filas de diccionario y prepara la exclusión de la conexión de
        fondo.

        Args:
            settings: Configuración validada del proceso, incluidos límites de conexión y
                caché local.

        Raises:
            ValueError: Si el mínimo de conexiones configurado supera el máximo.
        """
        self.settings = settings

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

        self._pinned_connection: Connection[dict[str, Any]] | None = None
        """Conexión reutilizada durante una operación de fondo exclusiva."""
        self._pinned_lock = RLock()
        """Serializa el uso de la conexión compartida, incluido el heartbeat."""

    def open(self) -> None:
        """Abre el pool y espera hasta 60 segundos a disponer de sus conexiones iniciales."""
        self.pool.open(wait=True, timeout=60)

    def close(self) -> None:
        """Cierra el pool y libera sus conexiones; no inicia otro ciclo de apertura."""
        self.pool.close()

    def migrate(self) -> None:
        """Serializa migradores con un lock global, verifica checksums y aplica las versiones
        pendientes en orden.
        Confirma cada versión por separado; un fallo posterior conserva las anteriores y
        libera el lock.

        Raises:
            RuntimeError: Si faltan archivos de una versión aplicada o cambió su checksum.
            OSError: Si no pueden leerse las migraciones locales.
        """
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
        """Exige que versiones y checksums aplicados coincidan exactamente con los archivos del
        servicio, sin migrar.

        Raises:
            RuntimeError: Si falta el registro del esquema, no tiene checksum o difieren
                versiones o huellas.
        """
        migrations = self._migration_files()

        def verify(connection: Connection[dict[str, Any]]) -> None:
            """Compara tabla, columnas, versiones y huellas en la conexión cedida para verificar
            el esquema.

            Args:
                connection: Conexión de la transacción cedida por Database.run; no se abre
                    otra conexión.
            """
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
        """Lee las migraciones SQL locales por nombre y calcula SHA-256 de sus bytes originales.

        Returns:
            mapa ordenado de versión a checksum y SQL UTF-8.

        Raises:
            OSError: Si falla la lectura de un archivo de migración.
            UnicodeDecodeError: Si una migración no es UTF-8 válido.
        """
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
        """Ejecuta trabajo y confirma su transacción, reutilizando bajo lock la conexión
        exclusiva cuando existe.
        Propaga el fallo del trabajo; en la conexión fijada revierte explícitamente antes de
        propagarlo.

        Args:
            callback: Trabajo que se ejecuta con una conexión y cuyo resultado se devuelve
                tras confirmar.

        Returns:
            resultado del callback después de confirmar la transacción.
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
        """Reserva una conexión y un lock consultivo para impedir solapar indexación y
        preparación entre procesos.

        Yields:
            control mientras se mantiene la exclusión del trabajo de fondo.

        Raises:
            RuntimeError: Si se intenta anidar otra operación exclusiva o no se puede adquirir
                la reserva.
        """
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
        """Filtra las estadísticas numéricas del pool para exponerlas como métricas operativas.

        Returns:
            contadores y medidas numéricas de psycopg_pool con claves de texto.
        """
        return {
            str(key): value
            for key, value in self.pool.get_stats().items()
            if isinstance(value, int | float)
        }

    def healthy(self) -> bool:
        """Comprueba una consulta trivial a través del mismo acceso transaccional utilizado por
        el servicio.

        Returns:
            True si PostgreSQL devuelve una fila; False ante cualquier fallo de acceso.
        """
        try:
            return bool(self.run(lambda connection: connection.execute("SELECT 1").fetchone()))
        except Exception:
            return False
