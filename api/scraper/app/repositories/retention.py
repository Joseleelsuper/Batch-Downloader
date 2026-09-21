"""Elimina por lotes datos operativos antiguos y conserva trabajo activo o ejecuciones todavía
referenciadas.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from time import monotonic
from typing import Any

from sqlalchemy import delete, null, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.time import utc_now
from app.db.enums import ScrapeRunStatus
from app.db.models import (
    ResolverLog,
    ScraperCommand,
    ScrapeRun,
    ScraperWorkItem,
)
from app.repositories.pipeline import (
    QUEUE_FILTER_SCRAPER,
    QUEUE_SEARCHER_FILTER,
    STATUS_COMPLETED,
    STATUS_DISCARDED,
)

WORK_ITEM_RETENTION_DAYS = 30
"""Conservación de elementos terminales."""

RUN_LOG_RETENTION_DAYS = 90
"""Conservación de ejecuciones, comandos consumidos y logs técnicos."""

DEFAULT_RETENTION_BATCH_SIZE = 500
"""Máximo de filas eliminado de cada tabla en una pasada."""

PAYLOAD_COMPACTION_BATCH_SIZE = 1_000
"""Número de payloads operativos que se libera en una transacción lógica."""

PAYLOAD_COMPACTION_MAX_BATCHES = 20
"""Límite de lotes de compactación por ciclo del scheduler."""

PAYLOAD_COMPACTION_MAX_SECONDS = 30.0
"""Tiempo máximo de compactación antes de ceder el ciclo al resto del scheduler."""


@dataclass(frozen=True, slots=True)
class RetentionResult:
    """Desglosa las filas retiradas de cada categoría durante una pasada de retención.

    Attributes:
        work_items: Tareas terminales eliminadas.
        resolver_logs, commands, runs: Registros, comandos terminales y ejecuciones históricas
            retirados.
        compacted_payloads: Payloads terminales de las dos primeras etapas liberados sin borrar
            la identidad ni el resultado de la tarea.
    """

    work_items: int = 0
    resolver_logs: int = 0
    commands: int = 0
    runs: int = 0
    compacted_payloads: int = 0

    @property
    def total(self) -> int:
        """Suma las filas afectadas en todas las categorías de la pasada.

        Returns:
            cantidad total de filas eliminadas o payloads compactados.
        """
        return sum(
            (
                self.work_items,
                self.resolver_logs,
                self.commands,
                self.runs,
                self.compacted_payloads,
            )
        )


class RetentionRepository:
    """Aplica retención de 30 días a datos operativos y de 90 días a logs, comandos y
    ejecuciones, sin confirmar por su cuenta.

    See Also:
        app.db.models.ScrapeRun: Se conserva si otra categoría todavía referencia la
            ejecución.
    """

    def __init__(self, session: AsyncSession) -> None:
        """Conserva la sesión de mantenimiento del llamador.

        Args:
            session: Sesión asíncrona del llamador; este decide cuándo confirmar los cambios.
        """
        self.session = session

    async def prune(
        self,
        *,
        now: datetime | None = None,
        batch_size: int = DEFAULT_RETENTION_BATCH_SIZE,
    ) -> RetentionResult:
        """Borra hasta un lote por categoría, exige tareas completadas o descartadas sin reserva
        y conserva ejecuciones con referencias existentes.

        Args:
            now: Instante UTC sin tzinfo; None toma el reloj actual cuando se admite.
            batch_size: Máximo de filas por categoría en una pasada; debe ser positivo.

        Returns:
            recuentos por categoría, pendientes de commit.

        Raises:
            ValueError: retention_batch_size_must_be_positive si el tamaño de lote es menor
                que uno.
        """
        if batch_size < 1:
            raise ValueError("retention_batch_size_must_be_positive")
        current = now or utc_now()
        operational_cutoff = current - timedelta(days=WORK_ITEM_RETENTION_DAYS)
        history_cutoff = current - timedelta(days=RUN_LOG_RETENTION_DAYS)

        compacted_payloads = await self.compact_terminal_work_payloads()

        work_items = await self._delete_ids(
            ScraperWorkItem,
            ScraperWorkItem.id,
            ScraperWorkItem.updated_at,
            (
                ScraperWorkItem.status.in_((STATUS_COMPLETED, STATUS_DISCARDED)),
                ScraperWorkItem.updated_at < operational_cutoff,
                ScraperWorkItem.lease_owner.is_(None),
                ScraperWorkItem.lease_expires_at.is_(None),
            ),
            batch_size,
        )
        resolver_logs = await self._delete_ids(
            ResolverLog,
            ResolverLog.id,
            ResolverLog.created_at,
            (ResolverLog.created_at < history_cutoff,),
            batch_size,
        )
        commands = await self._delete_ids(
            ScraperCommand,
            ScraperCommand.id,
            ScraperCommand.consumed_at,
            (
                ScraperCommand.status.in_(("completed", "failed", "rejected")),
                ScraperCommand.consumed_at.is_not(None),
                ScraperCommand.consumed_at < history_cutoff,
            ),
            batch_size,
        )

        referenced_work = select(ScraperWorkItem.id).where(
            ScraperWorkItem.run_id == ScrapeRun.id
        ).exists()
        referenced_commands = select(ScraperCommand.id).where(
            ScraperCommand.id == ScrapeRun.request_id
        ).exists()
        runs = await self._delete_ids(
            ScrapeRun,
            ScrapeRun.id,
            ScrapeRun.finished_at,
            (
                ScrapeRun.status.in_(
                    (
                        ScrapeRunStatus.COMPLETED.value,
                        ScrapeRunStatus.PARTIAL.value,
                        ScrapeRunStatus.FAILED.value,
                    )
                ),
                ScrapeRun.finished_at.is_not(None),
                ScrapeRun.finished_at < history_cutoff,
                ~referenced_work,
                ~referenced_commands,
            ),
            batch_size,
        )
        return RetentionResult(
            work_items=work_items,
            resolver_logs=resolver_logs,
            commands=commands,
            runs=runs,
            compacted_payloads=compacted_payloads,
        )

    async def compact_terminal_work_payloads(
        self,
        *,
        batch_size: int = PAYLOAD_COMPACTION_BATCH_SIZE,
        max_batches: int = PAYLOAD_COMPACTION_MAX_BATCHES,
        max_seconds: float = PAYLOAD_COMPACTION_MAX_SECONDS,
    ) -> int:
        """Libera payloads grandes de tareas terminales en lotes acotados.

        Sólo se compactan ``searcher_filter`` y ``filter_scraper`` completados o descartados.
        Las consultas de selección y actualización repiten el predicado para no vaciar un
        payload que haya sido reencolado concurrentemente.
        """
        if batch_size < 1 or max_batches < 1 or max_seconds <= 0:
            raise ValueError("payload_compaction_limits_must_be_positive")
        started = monotonic()
        affected = 0
        for _ in range(max_batches):
            if monotonic() - started >= max_seconds:
                break
            ids = list(
                await self.session.scalars(
                    select(ScraperWorkItem.id)
                    .where(
                        ScraperWorkItem.queue.in_(
                            (QUEUE_SEARCHER_FILTER, QUEUE_FILTER_SCRAPER)
                        )
                    )
                    .where(ScraperWorkItem.status.in_((STATUS_COMPLETED, STATUS_DISCARDED)))
                    .where(ScraperWorkItem.payload_json.is_not(None))
                    .order_by(ScraperWorkItem.updated_at.asc(), ScraperWorkItem.id.asc())
                    .limit(batch_size)
                )
            )
            if not ids:
                break
            result = await self.session.execute(
                update(ScraperWorkItem)
                .where(ScraperWorkItem.id.in_(ids))
                .where(
                    ScraperWorkItem.queue.in_(
                        (QUEUE_SEARCHER_FILTER, QUEUE_FILTER_SCRAPER)
                    )
                )
                .where(ScraperWorkItem.status.in_((STATUS_COMPLETED, STATUS_DISCARDED)))
                .where(ScraperWorkItem.payload_json.is_not(None))
                # Preserva updated_at: compactar un payload no debe rejuvenecer una tarea
                # terminal y retrasar su retención de 30 días.
                .values(
                    payload_json=null(),
                    updated_at=ScraperWorkItem.updated_at,
                )
            )
            await self.session.flush()
            rowcount = getattr(result, "rowcount", None)
            affected += len(ids) if rowcount is None or rowcount < 0 else int(rowcount)
            if len(ids) < batch_size:
                break
        return affected

    async def _delete_ids(
        self,
        model: type[Any],
        id_column: Any,
        order_column: Any,
        predicates: tuple[Any, ...],
        batch_size: int,
        *,
        max_batches: int = 1,
        max_seconds: float | None = None,
    ) -> int:
        """Selecciona primero las claves elegibles más antiguas y ejecuta una eliminación
        limitada a ellas.

        Args:
            model: Modelo ORM cuya tabla se depura.
            id_column: Columna de clave primaria utilizada para seleccionar y borrar.
            order_column: Columna temporal que ordena primero las filas más antiguas.
            predicates: Condiciones de elegibilidad para borrar datos retenidos.
            batch_size: Máximo de filas por categoría en una pasada; debe ser positivo.

        Returns:
            filas afectadas o cantidad seleccionada si el driver no proporciona un recuento
                válido.
        """
        started = monotonic()
        affected = 0
        for _ in range(max_batches):
            if max_seconds is not None and monotonic() - started >= max_seconds:
                break
            ids = list(
                await self.session.scalars(
                    select(id_column)
                    .where(*predicates)
                    .order_by(order_column.asc(), id_column.asc())
                    .limit(batch_size)
                )
            )
            if not ids:
                break
            result = await self.session.execute(delete(model).where(id_column.in_(ids)))
            rowcount = getattr(result, "rowcount", None)
            affected += len(ids) if rowcount is None or rowcount < 0 else int(rowcount)
            if len(ids) < batch_size:
                break
        return affected
