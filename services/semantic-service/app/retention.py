"""Retira historial terminal sin reserva activa mediante lotes pequeños que no esperan filas
bloqueadas.
"""
from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

from psycopg import Connection

from app.database import Database

WORK_ITEM_RETENTION_DAYS = 30
"""Conservación de trabajos de embeddings ya completados."""

OPERATION_RETENTION_DAYS = 90
"""Conservación de operaciones administrativas terminales."""

DEFAULT_RETENTION_BATCH_SIZE = 500
"""Máximo de filas eliminado de cada tabla en una pasada."""


class SemanticRetentionStore:
    """Elimina trabajos completados tras 30 días y operaciones terminales tras 90 días,
    preservando todo trabajo pendiente o reservado.
    """

    def __init__(self, database: Database) -> None:
        """Conserva el pool para confirmar juntos los lotes de limpieza del historial.

        Args:
            database: Pool que delimita las transacciones de persistencia y evaluación.
        """
        self.database = database

    def prune(
        self,
        *,
        now: datetime | None = None,
        batch_size: int = DEFAULT_RETENTION_BATCH_SIZE,
    ) -> dict[str, int]:
        """Elimina como máximo un lote por tabla con SKIP LOCKED, priorizando las filas
        terminales más antiguas.

        Args:
            now: Reloj UTC opcional para calcular los cortes; None usa el instante actual.
            batch_size: Máximo positivo de filas por tabla que se eliminan en una llamada.

        Returns:
            conteos de embeddingJobs y operations eliminados.

        Raises:
            ValueError: Si el tamaño del lote no es positivo.
        """
        if batch_size < 1:
            raise ValueError("retention_batch_size_must_be_positive")
        current = now or datetime.now(UTC)
        work_cutoff = current - timedelta(days=WORK_ITEM_RETENTION_DAYS)
        operation_cutoff = current - timedelta(days=OPERATION_RETENTION_DAYS)

        def operation(connection: Connection[dict[str, Any]]) -> dict[str, int]:
            """Borra filas terminales vencidas y sin propietario ni lease dentro de la misma
            transacción.

            Args:
                connection: Conexión de la misma transacción que contiene tabla temporal,
                    índice y consultas de medición.

            Returns:
                conteos reales de los dos lotes retirados.
            """
            jobs = connection.execute(
                """
                WITH doomed AS (
                    SELECT id
                    FROM embedding_jobs
                    WHERE status = 'completed'
                      AND updated_at < %s
                      AND lease_owner IS NULL
                      AND lease_until IS NULL
                    ORDER BY updated_at ASC, id ASC
                    LIMIT %s
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM embedding_jobs job
                USING doomed
                WHERE job.id = doomed.id
                RETURNING job.id
                """,
                (work_cutoff, batch_size),
            ).fetchall()
            operations = connection.execute(
                """
                WITH doomed AS (
                    SELECT id
                    FROM semantic_operations
                    WHERE status IN ('succeeded', 'failed', 'cancelled')
                      AND finished_at IS NOT NULL
                      AND finished_at < %s
                      AND lease_owner IS NULL
                      AND lease_until IS NULL
                    ORDER BY finished_at ASC, id ASC
                    LIMIT %s
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM semantic_operations operation
                USING doomed
                WHERE operation.id = doomed.id
                RETURNING operation.id
                """,
                (operation_cutoff, batch_size),
            ).fetchall()
            return {
                "embeddingJobs": len(jobs),
                "operations": len(operations),
            }

        return self.database.run(operation)
