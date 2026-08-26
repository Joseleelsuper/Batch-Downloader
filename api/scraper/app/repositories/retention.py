"""Poda acotada de datos operativos del scraper.

Las filas reclamables o todavía arrendadas no forman parte de ninguna política de
retención. Cada ejecución elimina como máximo un lote por tabla para que el
mantenimiento no monopolice el pool de dos conexiones del scheduler.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.time import utc_now
from app.db.enums import ScrapeRunStatus
from app.db.models import (
    ResolverLog,
    ScraperCommand,
    ScraperMetricSnapshot,
    ScrapeRun,
    ScraperWorkerSnapshot,
    ScraperWorkItem,
)
from app.repositories.pipeline import STATUS_COMPLETED, STATUS_DISCARDED

WORK_ITEM_RETENTION_DAYS = 30
"""Conservación de métricas, instantáneas y elementos terminales."""

RUN_LOG_RETENTION_DAYS = 90
"""Conservación de ejecuciones, comandos consumidos y logs técnicos."""

DEFAULT_RETENTION_BATCH_SIZE = 500
"""Máximo de filas eliminado de cada tabla en una pasada."""


@dataclass(frozen=True, slots=True)
class RetentionResult:
    """Resume una pasada idempotente de retención."""

    work_items: int = 0
    metric_snapshots: int = 0
    worker_snapshots: int = 0
    resolver_logs: int = 0
    commands: int = 0
    runs: int = 0

    @property
    def total(self) -> int:
        """Devuelve el número total de filas eliminadas."""
        return sum(
            (
                self.work_items,
                self.metric_snapshots,
                self.worker_snapshots,
                self.resolver_logs,
                self.commands,
                self.runs,
            )
        )


class RetentionRepository:
    """Aplica las ventanas de retención sin tocar trabajo pendiente o arrendado."""

    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def prune(
        self,
        *,
        now: datetime | None = None,
        batch_size: int = DEFAULT_RETENTION_BATCH_SIZE,
    ) -> RetentionResult:
        """Elimina un lote por tabla y conserva siempre filas activas."""
        if batch_size < 1:
            raise ValueError("retention_batch_size_must_be_positive")
        current = now or utc_now()
        operational_cutoff = current - timedelta(days=WORK_ITEM_RETENTION_DAYS)
        history_cutoff = current - timedelta(days=RUN_LOG_RETENTION_DAYS)

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
        metric_snapshots = await self._delete_ids(
            ScraperMetricSnapshot,
            ScraperMetricSnapshot.id,
            ScraperMetricSnapshot.captured_at,
            (ScraperMetricSnapshot.captured_at < operational_cutoff,),
            batch_size,
        )
        worker_snapshots = await self._delete_ids(
            ScraperWorkerSnapshot,
            ScraperWorkerSnapshot.id,
            ScraperWorkerSnapshot.captured_at,
            (ScraperWorkerSnapshot.captured_at < operational_cutoff,),
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
        referenced_metrics = select(ScraperMetricSnapshot.id).where(
            ScraperMetricSnapshot.run_id == ScrapeRun.id
        ).exists()
        referenced_snapshots = select(ScraperWorkerSnapshot.id).where(
            ScraperWorkerSnapshot.run_id == ScrapeRun.id
        ).exists()
        referenced_commands = select(ScraperCommand.id).where(
            ScraperCommand.run_id == ScrapeRun.id
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
                ~referenced_metrics,
                ~referenced_snapshots,
                ~referenced_commands,
            ),
            batch_size,
        )
        return RetentionResult(
            work_items=work_items,
            metric_snapshots=metric_snapshots,
            worker_snapshots=worker_snapshots,
            resolver_logs=resolver_logs,
            commands=commands,
            runs=runs,
        )

    async def _delete_ids(
        self,
        model: type[Any],
        id_column: Any,
        order_column: Any,
        predicates: tuple[Any, ...],
        batch_size: int,
    ) -> int:
        """Selecciona primero identificadores para conservar un límite portable."""
        ids = list(
            await self.session.scalars(
                select(id_column)
                .where(*predicates)
                .order_by(order_column.asc(), id_column.asc())
                .limit(batch_size)
            )
        )
        if not ids:
            return 0
        result = await self.session.execute(delete(model).where(id_column.in_(ids)))
        rowcount = getattr(result, "rowcount", None)
        return len(ids) if rowcount is None or rowcount < 0 else int(rowcount)
