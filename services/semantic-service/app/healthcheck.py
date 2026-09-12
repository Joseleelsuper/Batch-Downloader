"""Proporciona sondas de contenedor para base de datos, directorios de trabajo y latidos de
workers.
"""

from __future__ import annotations

import argparse
import tempfile
from pathlib import Path

from psycopg import connect

from app.config import get_settings


def database_ready() -> bool:
    """Abre una conexión de prueba con timeout de tres segundos y verifica que PostgreSQL
    responde SELECT 1.

    Returns:
        True si la consulta responde; False ante cualquier fallo de conexión o ejecución.
    """
    settings = get_settings()
    try:
        with connect(settings.postgres_dsn, connect_timeout=3) as connection:
            return connection.execute("SELECT 1").fetchone() == (1,)
    except Exception:
        return False


def directory_writable(path: str) -> bool:
    """Crea el directorio si falta e intenta abrir un archivo temporal que se retira al cerrar.

    Args:
        path: Directorio local de modelos o informes cuya escritura exige el contenedor.

    Returns:
        True si puede escribirse y cerrar el temporal; False ante OSError.
    """
    directory = Path(path)
    try:
        directory.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=directory):
            return True
    except OSError:
        return False


def worker_ready(role: str, max_age_seconds: float, failure_threshold: int) -> bool:
    """Comprueba que existe un latido reciente del rol y no supera la racha admitida de fallos.

    Args:
        role: Rol del trabajador indexer o model-worker; se normaliza antes de consultar el
            latido.
        max_age_seconds: Antigüedad máxima aceptada del último latido en segundos.
        failure_threshold: Cantidad de fallos consecutivos que invalida la disponibilidad.

    Returns:
        False si falta la fila, está obsoleta, acumula fallos o no puede consultarse.
    """
    settings = get_settings()
    normalized_role = role.strip().lower().replace("_", "-")
    try:
        with connect(settings.postgres_dsn, connect_timeout=3) as connection:
            row = connection.execute(
                """
                SELECT
                    EXTRACT(EPOCH FROM (now() - heartbeat_at)) AS age_seconds,
                    consecutive_failures
                FROM semantic_worker_heartbeats
                WHERE role = %s
                """,
                (normalized_role,),
            ).fetchone()
        return bool(
            row is not None
            and float(row[0]) <= max_age_seconds
            and int(row[1]) < failure_threshold
        )
    except Exception:
        return False


def main() -> None:
    """Combina las comprobaciones solicitadas y termina con código 0 si están listas o 1 si
    alguna falla.
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--writable", action="append", default=[])
    parser.add_argument("--worker", choices=("indexer", "model-worker"))
    parser.add_argument("--max-age", type=float)
    parser.add_argument("--failure-threshold", type=int)
    arguments = parser.parse_args()
    ready = database_ready() and all(
        directory_writable(path) for path in arguments.writable
    )
    if ready and arguments.worker:
        settings = get_settings()
        ready = worker_ready(
            arguments.worker,
            arguments.max_age or settings.worker_heartbeat_stale_seconds,
            arguments.failure_threshold or settings.worker_failure_threshold,
        )
    raise SystemExit(0 if ready else 1)


if __name__ == "__main__":
    main()
