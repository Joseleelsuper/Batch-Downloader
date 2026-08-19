"""Implementa las responsabilidades del módulo `pipeline`.
"""
from __future__ import annotations

import re
import uuid
from dataclasses import dataclass
from datetime import timedelta
from typing import Any

from sqlalchemy import delete, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.json_safe import json_safe
from app.core.time import utc_now
from app.db.enums import ResolutionStatus, ScrapeRunStatus
from app.db.models import (
    DownloadSource,
    ScraperMetricSnapshot,
    ScrapeRun,
    ScraperWorkerSnapshot,
    ScraperWorkItem,
    SoftwareApp,
)

QUEUE_SEARCHER_FILTER = "searcher_filter"
"""Constante que define `QUEUE_SEARCHER_FILTER`.
"""
QUEUE_FILTER_SCRAPER = "filter_scraper"
"""Constante que define `QUEUE_FILTER_SCRAPER`.
"""
QUEUE_SCRAPER_SO_FILTER = "scraper_so_filter"
"""Constante que define `QUEUE_SCRAPER_SO_FILTER`.
"""
QUEUE_SO_FILTER_DESCRIPTOR = "so_filter_descriptor"
"""Constante que define `QUEUE_SO_FILTER_DESCRIPTOR`.
"""
QUEUE_MANUAL_INSTALLER_ENRICHMENT = "manual_installer_enrichment"
"""Constante que define `QUEUE_MANUAL_INSTALLER_ENRICHMENT`.
"""
QUEUE_WEBSITE_APP_DISCOVERY = "website_app_discovery"
"""Constante que define `QUEUE_WEBSITE_APP_DISCOVERY`.
"""

# Las instantáneas alimentan el monitor administrativo en vivo; son vistas previas,
# nunca un archivo de una página oficial. Acota la entrada antes de sanearla para que
# una página grande no monopolice un worker del scraper durante una pasada de regex.
MAX_SNAPSHOT_HTML_BYTES = 24_000
"""Constante que define `MAX_SNAPSHOT_HTML_BYTES`.
"""

STATUS_QUEUED = "queued"
"""Constante que define `STATUS_QUEUED`.
"""
STATUS_IN_PROGRESS = "in_progress"
"""Constante que define `STATUS_IN_PROGRESS`.
"""
STATUS_COMPLETED = "completed"
"""Constante que define `STATUS_COMPLETED`.
"""
STATUS_DISCARDED = "discarded"
"""Constante que define `STATUS_DISCARDED`.
"""
STATUS_FAILED = "failed"
"""Constante que define `STATUS_FAILED`.
"""


@dataclass(frozen=True)
class QueuePreviewItem:
    """Representa un elemento de `QueuePreview`.
    """
    id: str
    """Atributo de clase `id` de `QueuePreviewItem`.
    """
    package_id: str
    """Atributo de clase `package_id` de `QueuePreviewItem`.
    """
    app_name: str | None
    """Atributo de clase `app_name` de `QueuePreviewItem`.
    """
    status: str
    """Atributo de clase `status` de `QueuePreviewItem`.
    """
    attempts: int
    """Atributo de clase `attempts` de `QueuePreviewItem`.
    """
    updated_at: object
    """Atributo de clase `updated_at` de `QueuePreviewItem`.
    """


@dataclass(frozen=True)
class QueueState:
    """Representa el componente `QueueState`.
    """
    queue: str
    """Atributo de clase `queue` de `QueueState`.
    """
    counts: dict[str, int]
    """Atributo de clase `counts` de `QueueState`.
    """
    items: list[QueuePreviewItem]
    """Atributo de clase `items` de `QueueState`.
    """


@dataclass(frozen=True)
class WorkerSnapshotView:
    """Representa el componente `WorkerSnapshotView`.
    """
    stage: str
    """Atributo de clase `stage` de `WorkerSnapshotView`.
    """
    package_id: str | None
    """Atributo de clase `package_id` de `WorkerSnapshotView`.
    """
    app_name: str | None
    """Atributo de clase `app_name` de `WorkerSnapshotView`.
    """
    url: str | None
    """Atributo de clase `url` de `WorkerSnapshotView`.
    """
    html: str | None
    """Atributo de clase `html` de `WorkerSnapshotView`.
    """
    captured_at: object
    """Atributo de clase `captured_at` de `WorkerSnapshotView`.
    """


@dataclass(frozen=True)
class MetricSnapshotView:
    """Representa el componente `MetricSnapshotView`.
    """
    available: int
    """Atributo de clase `available` de `MetricSnapshotView`.
    """
    review: int
    """Atributo de clase `review` de `MetricSnapshotView`.
    """
    unavailable: int
    """Atributo de clase `unavailable` de `MetricSnapshotView`.
    """
    queued_searcher_filter: int
    """Atributo de clase `queued_searcher_filter` de `MetricSnapshotView`.
    """
    queued_filter_scraper: int
    """Atributo de clase `queued_filter_scraper` de `MetricSnapshotView`.
    """
    queued_scraper_so_filter: int
    """Atributo de clase `queued_scraper_so_filter` de `MetricSnapshotView`.
    """
    queued_so_filter_descriptor: int
    """Atributo de clase `queued_so_filter_descriptor` de `MetricSnapshotView`.
    """
    captured_at: object
    """Atributo de clase `captured_at` de `MetricSnapshotView`.
    """


@dataclass(frozen=True)
class QueueMaintenanceResult:
    """Representa el resultado de `QueueMaintenance`.
    """
    action: str
    """Atributo de clase `action` de `QueueMaintenanceResult`.
    """
    affected: int
    """Atributo de clase `affected` de `QueueMaintenanceResult`.
    """


class PipelineRepository:
    """Gestiona la persistencia y consulta de `Pipeline`.
    """
    def __init__(self, session: AsyncSession) -> None:
        """Inicializa una instancia de `PipelineRepository`.

        Args:
            session (AsyncSession): Sesión de base de datos utilizada por la operación.
        """
        self.session = session
        """Estado de instancia asociado a `session`.
        """

    async def reset_expired_leases(self) -> int:
        """Restablece la operación `expired_leases`.

        Returns:
            int: Resultado producido por la operación.
        """
        return await self.recover_stuck()

    async def recover_stuck(self) -> int:
        """Recupera la operación `stuck`.

        Returns:
            int: Número de elementos afectados por la operación.
        """
        now = utc_now()
        result = await self.session.scalars(
            select(ScraperWorkItem)
            .where(ScraperWorkItem.status == STATUS_IN_PROGRESS)
            .where(
                or_(
                    ScraperWorkItem.lease_expires_at.is_(None),
                    ScraperWorkItem.lease_expires_at < now,
                )
            )
        )
        items = list(result)
        for item in items:
            item.status = STATUS_QUEUED
            item.lease_owner = None
            item.lease_expires_at = None
            item.available_at = now
            item.last_error = None
            item.updated_at = now
        await self.session.flush()
        return len(items)

    async def recover_orphaned_run_items(self) -> int:
        """Recupera la operación `orphaned_run_items`.

        Returns:
            int: Número de elementos afectados por la operación.
        """
        now = utc_now()
        inactive_run_ids = select(ScrapeRun.id).where(
            ScrapeRun.status != ScrapeRunStatus.RUNNING.value
        )
        result = await self.session.scalars(
            select(ScraperWorkItem)
            .where(ScraperWorkItem.status == STATUS_IN_PROGRESS)
            .where(
                or_(
                    ScraperWorkItem.run_id.is_(None),
                    ScraperWorkItem.run_id.in_(inactive_run_ids),
                )
            )
        )
        items = list(result)
        for item in items:
            item.status = STATUS_QUEUED
            item.lease_owner = None
            item.lease_expires_at = None
            item.available_at = now
            item.last_error = "scheduler_restart_recovery"
            item.updated_at = now
        await self.session.flush()
        return len(items)

    async def retry_failed(self) -> int:
        """Reintenta la operación `failed`.

        Returns:
            int: Número de elementos afectados por la operación.
        """
        now = utc_now()
        result = await self.session.scalars(
            select(ScraperWorkItem).where(ScraperWorkItem.status == STATUS_FAILED)
        )
        items = list(result)
        for item in items:
            item.status = STATUS_QUEUED
            item.lease_owner = None
            item.lease_expires_at = None
            item.available_at = now
            item.last_error = None
            item.updated_at = now
        await self.session.flush()
        return len(items)

    async def prune_terminal(self) -> int:
        """Ejecuta `prune_terminal` dentro de `PipelineRepository`.

        Returns:
            int: Resultado producido por la operación.
        """
        result = await self.session.execute(
            delete(ScraperWorkItem).where(
                ScraperWorkItem.status.in_([STATUS_COMPLETED, STATUS_DISCARDED])
            )
        )
        await self.session.flush()
        return statement_rowcount(result)

    async def clear_pending(self) -> int:
        """Limpia la operación `pending`.

        Returns:
            int: Número de elementos afectados por la operación.
        """
        result = await self.session.execute(
            delete(ScraperWorkItem).where(
                ScraperWorkItem.status.in_(
                    [STATUS_QUEUED, STATUS_FAILED, STATUS_COMPLETED, STATUS_DISCARDED]
                )
            )
        )
        await self.session.flush()
        return statement_rowcount(result)

    async def clear_all(self) -> int:
        """Limpia la operación `all`.

        Returns:
            int: Número de elementos afectados por la operación.
        """
        result = await self.session.execute(delete(ScraperWorkItem))
        await self.session.flush()
        return statement_rowcount(result)

    async def enqueue(
        self,
        queue: str,
        package_id: str,
        app_name: str | None,
        payload: dict[str, Any],
        run_id: uuid.UUID | None,
        *,
        priority: int = 0,
        force: bool = False,
    ) -> ScraperWorkItem:
        """Ejecuta `enqueue` dentro de `PipelineRepository`.

        Args:
            queue (str): Valor de `queue` utilizado por la operación.
            package_id (str): Identificador de `package` utilizado por la operación.
            app_name (str | None): Valor de `app_name` utilizado por la operación.
            payload (dict[str, Any]): Carga de datos recibida por la operación.
            run_id (uuid.UUID | None): Identificador de `run` utilizado por la operación.
            priority (int): Valor de `priority` utilizado por la operación.
            force (bool): Valor de `force` utilizado por la operación.

        Returns:
            ScraperWorkItem: Resultado producido por la operación.
        """
        existing = await self.session.scalar(
            select(ScraperWorkItem)
            .where(ScraperWorkItem.queue == queue)
            .where(ScraperWorkItem.package_id == package_id)
            .limit(1)
        )
        now = utc_now()
        if existing:
            payload_changed = (existing.payload_json or {}).get("input_hash") != payload.get(
                "input_hash"
            )
            belongs_to_new_run = run_id is not None and existing.run_id != run_id
            should_requeue = existing.status in {
                STATUS_QUEUED,
                STATUS_FAILED,
                STATUS_DISCARDED,
            } or (
                existing.status == STATUS_COMPLETED
                and queue in {QUEUE_SCRAPER_SO_FILTER, QUEUE_SO_FILTER_DESCRIPTOR}
                and (force or payload_changed)
            ) or (
                existing.status == STATUS_COMPLETED
                and queue
                in {
                    QUEUE_SEARCHER_FILTER,
                    QUEUE_FILTER_SCRAPER,
                    QUEUE_SCRAPER_SO_FILTER,
                }
                and belongs_to_new_run
            )
            if should_requeue:
                existing.status = STATUS_QUEUED
                existing.payload_json = json_safe(payload)
                existing.app_name = app_name or existing.app_name
                existing.run_id = run_id or existing.run_id
                existing.priority = max(existing.priority, priority)
                existing.last_error = None
                existing.lease_owner = None
                existing.lease_expires_at = None
                if belongs_to_new_run:
                    existing.attempts = 0
                existing.available_at = now
                existing.updated_at = now
            return existing

        item = ScraperWorkItem(
            queue=queue,
            status=STATUS_QUEUED,
            package_id=package_id,
            app_name=app_name,
            payload_json=json_safe(payload),
            run_id=run_id,
            priority=priority,
            available_at=now,
        )
        self.session.add(item)
        await self.session.flush()
        return item

    async def has_active_item(self, queue: str, package_id: str) -> bool:
        """Indica si existe la operación `active_item`.

        Args:
            queue (str): Valor de `queue` utilizado por la operación.
            package_id (str): Identificador de `package` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        item_id = await self.session.scalar(
            select(ScraperWorkItem.id)
            .where(ScraperWorkItem.queue == queue)
            .where(ScraperWorkItem.package_id == package_id)
            .where(ScraperWorkItem.status.in_([STATUS_QUEUED, STATUS_IN_PROGRESS]))
            .limit(1)
        )
        return item_id is not None

    async def item_statuses(self, queue: str, package_ids: list[str]) -> dict[str, str]:
        """Ejecuta `item_statuses` dentro de `PipelineRepository`.

        Args:
            queue (str): Valor de `queue` utilizado por la operación.
            package_ids (list[str]): Colección de identificadores de `package`.

        Returns:
            dict[str, str]: Mapa con los datos producidos por la operación.
        """
        if not package_ids:
            return {}
        rows = await self.session.execute(
            select(ScraperWorkItem.package_id, ScraperWorkItem.status)
            .where(ScraperWorkItem.queue == queue)
            .where(ScraperWorkItem.package_id.in_(package_ids))
        )
        return {package_id: status for package_id, status in rows}

    async def active_package_ids(
        self,
        queues: tuple[str, ...],
        package_ids: list[str],
    ) -> set[str]:
        """Ejecuta `active_package_ids` dentro de `PipelineRepository`.

        Args:
            queues (tuple[str, ...]): Valor de `queues` utilizado por la operación.
            package_ids (list[str]): Colección de identificadores de `package`.

        Returns:
            set[str]: Resultado producido por la operación.
        """
        if not queues or not package_ids:
            return set()
        rows = await self.session.scalars(
            select(ScraperWorkItem.package_id)
            .where(ScraperWorkItem.queue.in_(queues))
            .where(ScraperWorkItem.package_id.in_(package_ids))
            .where(ScraperWorkItem.status.in_((STATUS_QUEUED, STATUS_IN_PROGRESS)))
        )
        return set(rows)

    async def claim_next(
        self,
        queue: str,
        worker_id: str,
        lease_seconds: int,
        *,
        run_id: uuid.UUID | None = None,
    ) -> ScraperWorkItem | None:
        """Reserva la operación `next`.

        Args:
            queue (str): Valor de `queue` utilizado por la operación.
            worker_id (str): Identificador de `worker` utilizado por la operación.
            lease_seconds (int): Valor de `lease_seconds` utilizado por la operación.

        Returns:
            ScraperWorkItem | None: Resultado producido por la operación.
        """
        now = utc_now()
        statement = (
            select(ScraperWorkItem.id)
            .where(ScraperWorkItem.queue == queue)
            .where(ScraperWorkItem.status == STATUS_QUEUED)
            .where(ScraperWorkItem.available_at <= now)
            .order_by(
                ScraperWorkItem.priority.desc(),
                ScraperWorkItem.available_at.asc(),
                ScraperWorkItem.created_at.asc(),
            )
            .limit(1)
            .with_for_update(skip_locked=True)
        )
        if run_id is not None:
            statement = statement.where(ScraperWorkItem.run_id == run_id)
        item_id = await self.session.scalar(statement)
        if not item_id:
            return None
        item = await self.session.get(ScraperWorkItem, item_id)
        if not item:
            return None
        item.status = STATUS_IN_PROGRESS
        item.attempts += 1
        item.lease_owner = worker_id
        item.lease_expires_at = now + timedelta(seconds=lease_seconds)
        item.updated_at = now
        await self.session.flush()
        return item

    async def complete(self, item: ScraperWorkItem) -> None:
        """Ejecuta `complete` dentro de `PipelineRepository`.

        Args:
            item (ScraperWorkItem): Valor de `item` utilizado por la operación.
        """
        await self._finish(item, STATUS_COMPLETED, None)

    async def discard(self, item: ScraperWorkItem, reason: str) -> None:
        """Ejecuta `discard` dentro de `PipelineRepository`.

        Args:
            item (ScraperWorkItem): Valor de `item` utilizado por la operación.
            reason (str): Valor de `reason` utilizado por la operación.
        """
        await self._finish(item, STATUS_DISCARDED, reason)

    async def fail(self, item: ScraperWorkItem, error: str) -> None:
        """Ejecuta `fail` dentro de `PipelineRepository`.

        Args:
            item (ScraperWorkItem): Valor de `item` utilizado por la operación.
            error (str): Error que debe registrarse o propagarse.
        """
        await self._finish(item, STATUS_FAILED, error)

    async def requeue(
        self,
        item: ScraperWorkItem,
        reason: str,
        *,
        delay_seconds: int = 2,
    ) -> None:
        """Ejecuta `requeue` dentro de `PipelineRepository`.

        Args:
            item (ScraperWorkItem): Valor de `item` utilizado por la operación.
            reason (str): Valor de `reason` utilizado por la operación.
            delay_seconds (int): Valor de `delay_seconds` utilizado por la operación.
        """
        now = utc_now()
        item.status = STATUS_QUEUED
        item.last_error = truncate(reason, 1000)
        item.lease_owner = None
        item.lease_expires_at = None
        item.available_at = now + timedelta(seconds=max(0, delay_seconds))
        item.updated_at = now
        await self.session.flush()

    async def _finish(self, item: ScraperWorkItem, status: str, message: str | None) -> None:
        """Ejecuta el paso interno `_finish`.

        Args:
            item (ScraperWorkItem): Valor de `item` utilizado por la operación.
            status (str): Valor de `status` utilizado por la operación.
            message (str | None): Mensaje que debe procesarse.
        """
        item.status = status
        item.last_error = truncate(message, 1000)
        item.lease_owner = None
        item.lease_expires_at = None
        item.updated_at = utc_now()
        await self.session.flush()

    async def has_pending_work(self) -> bool:
        """Indica si existe la operación `pending_work`.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        count = await self.session.scalar(
            select(func.count(ScraperWorkItem.id)).where(
                ScraperWorkItem.status.in_([STATUS_QUEUED, STATUS_IN_PROGRESS])
            )
        )
        return bool(count)

    async def queue_depth(
        self,
        queue: str,
        *,
        run_id: uuid.UUID | None = None,
    ) -> int:
        """Ejecuta `queue_depth` dentro de `PipelineRepository`.

        Args:
            queue (str): Valor de `queue` utilizado por la operación.

        Returns:
            int: Resultado producido por la operación.
        """
        return await self._count_queue(queue, run_id=run_id)

    async def queue_states(self, limit: int = 20) -> list[QueueState]:
        """Ejecuta `queue_states` dentro de `PipelineRepository`.

        Args:
            limit (int): Número máximo de elementos que se recuperarán.

        Returns:
            list[QueueState]: Colección de elementos obtenidos por la operación.
        """
        states: list[QueueState] = []
        for queue in (
            QUEUE_SEARCHER_FILTER,
            QUEUE_FILTER_SCRAPER,
            QUEUE_SCRAPER_SO_FILTER,
            QUEUE_SO_FILTER_DESCRIPTOR,
        ):
            rows = await self.session.execute(
                select(ScraperWorkItem.status, func.count(ScraperWorkItem.id))
                .where(ScraperWorkItem.queue == queue)
                .group_by(ScraperWorkItem.status)
            )
            counts = {status: int(count) for status, count in rows}
            result = await self.session.execute(
                select(
                    ScraperWorkItem.id,
                    ScraperWorkItem.package_id,
                    ScraperWorkItem.app_name,
                    ScraperWorkItem.status,
                    ScraperWorkItem.attempts,
                    ScraperWorkItem.updated_at,
                )
                .where(ScraperWorkItem.queue == queue)
                .where(ScraperWorkItem.status.in_([STATUS_QUEUED, STATUS_IN_PROGRESS]))
                .order_by(
                    (ScraperWorkItem.status == STATUS_IN_PROGRESS).desc(),
                    ScraperWorkItem.updated_at.desc(),
                )
                .limit(limit)
            )
            states.append(
                QueueState(
                    queue=queue,
                    counts=counts,
                    items=[
                        QueuePreviewItem(
                            id=str(item["id"]),
                            package_id=item["package_id"],
                            app_name=item["app_name"],
                            status=item["status"],
                            attempts=item["attempts"],
                            updated_at=item["updated_at"],
                        )
                        for item in result.mappings()
                    ],
                )
            )
        return states

    async def save_snapshot(
        self,
        *,
        run_id: uuid.UUID | None,
        worker_id: str,
        stage: str,
        package_id: str | None,
        app_name: str | None,
        url: str | None,
        html: str | None,
        ttl_seconds: int = 900,
    ) -> None:
        """Guarda la operación `snapshot`.

        Args:
            run_id (uuid.UUID | None): Identificador de `run` utilizado por la operación.
            worker_id (str): Identificador de `worker` utilizado por la operación.
            stage (str): Valor de `stage` utilizado por la operación.
            package_id (str | None): Identificador de `package` utilizado por la operación.
            app_name (str | None): Valor de `app_name` utilizado por la operación.
            url (str | None): URL del recurso que debe procesarse.
            html (str | None): Valor de `html` utilizado por la operación.
            ttl_seconds (int): Valor de `ttl_seconds` utilizado por la operación.
        """
        now = utc_now()
        self.session.add(
            ScraperWorkerSnapshot(
            # Las instantáneas son una vista en vivo de mejor esfuerzo. Referenciar la
            # ejecución que se actualiza hace que cada inserción bloquee su FK y puede
            # interbloquear workers concurrentes con `set_current`.
                run_id=None,
                worker_id=worker_id,
                stage=stage,
                package_id=package_id,
                app_name=app_name,
                url=url,
                html=sanitize_snapshot_html(html),
                captured_at=now,
                expires_at=now + timedelta(seconds=ttl_seconds),
            )
        )
        await self.session.flush()

    async def prune_expired_snapshots(self) -> int:
        """Ejecuta `prune_expired_snapshots` dentro de `PipelineRepository`.

        Returns:
            int: Resultado producido por la operación.
        """
        result = await self.session.execute(
            delete(ScraperWorkerSnapshot).where(
                ScraperWorkerSnapshot.expires_at < utc_now()
            )
        )
        await self.session.flush()
        return statement_rowcount(result)

    async def latest_snapshots(self) -> list[WorkerSnapshotView]:
        """Ejecuta `latest_snapshots` dentro de `PipelineRepository`.

        Returns:
            list[WorkerSnapshotView]: Colección de elementos obtenidos por la operación.
        """
        snapshots: list[WorkerSnapshotView] = []
        for stage in ("searcher", "filter", "scraper", "so_filter", "descriptor"):
            snapshot = await self.session.scalar(
                select(ScraperWorkerSnapshot)
                .where(ScraperWorkerSnapshot.stage == stage)
                .where(ScraperWorkerSnapshot.expires_at >= utc_now())
                .order_by(ScraperWorkerSnapshot.captured_at.desc())
                .limit(1)
            )
            if snapshot:
                snapshots.append(
                    WorkerSnapshotView(
                        stage=snapshot.stage,
                        package_id=snapshot.package_id,
                        app_name=snapshot.app_name,
                        url=snapshot.url,
                        html=snapshot.html,
                        captured_at=snapshot.captured_at,
                    )
                )
        return snapshots

    async def save_metric_snapshot(self, run_id: uuid.UUID | None = None) -> None:
        """Guarda la operación `metric_snapshot`.

        Args:
            run_id (uuid.UUID | None): Identificador de `run` utilizado por la operación.
        """
        available = await self._count_apps_with_statuses(
            [ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value],
        )
        review = await self._count_apps_with_statuses(
            [ResolutionStatus.REQUIRES_MANUAL_REVIEW.value],
            exclude_available=True,
        )
        unavailable = await self._count_apps_with_statuses(
            [ResolutionStatus.MISSING.value, ResolutionStatus.BROKEN.value],
            exclude_available=True,
            exclude_review=True,
        )
        queued_searcher_filter = await self._count_queue(QUEUE_SEARCHER_FILTER)
        queued_filter_scraper = await self._count_queue(QUEUE_FILTER_SCRAPER)
        queued_scraper_so_filter = await self._count_queue(QUEUE_SCRAPER_SO_FILTER)
        queued_so_filter_descriptor = await self._count_queue(QUEUE_SO_FILTER_DESCRIPTOR)
        self.session.add(
            ScraperMetricSnapshot(
                run_id=run_id,
                available=available,
                review=review,
                unavailable=unavailable,
                queued_searcher_filter=queued_searcher_filter,
                queued_filter_scraper=queued_filter_scraper,
                queued_scraper_so_filter=queued_scraper_so_filter,
                queued_so_filter_descriptor=queued_so_filter_descriptor,
                captured_at=utc_now(),
            )
        )
        await self.session.flush()

    async def metric_snapshots(self, limit: int = 60) -> list[MetricSnapshotView]:
        """Ejecuta `metric_snapshots` dentro de `PipelineRepository`.

        Args:
            limit (int): Número máximo de elementos que se recuperarán.

        Returns:
            list[MetricSnapshotView]: Colección de elementos obtenidos por la operación.
        """
        result = await self.session.scalars(
            select(ScraperMetricSnapshot)
            .order_by(ScraperMetricSnapshot.captured_at.desc())
            .limit(limit)
        )
        return [
            MetricSnapshotView(
                available=item.available,
                review=item.review,
                unavailable=item.unavailable,
                queued_searcher_filter=item.queued_searcher_filter,
                queued_filter_scraper=item.queued_filter_scraper,
                queued_scraper_so_filter=item.queued_scraper_so_filter,
                queued_so_filter_descriptor=item.queued_so_filter_descriptor,
                captured_at=item.captured_at,
            )
            for item in reversed(list(result))
        ]

    async def _count_queue(
        self,
        queue: str,
        *,
        run_id: uuid.UUID | None = None,
    ) -> int:
        """Ejecuta el paso interno `_count_queue`.

        Args:
            queue (str): Valor de `queue` utilizado por la operación.

        Returns:
            int: Número de elementos afectados por la operación.
        """
        statement = (
            select(func.count(ScraperWorkItem.id))
            .where(ScraperWorkItem.queue == queue)
            .where(ScraperWorkItem.status.in_([STATUS_QUEUED, STATUS_IN_PROGRESS]))
        )
        if run_id is not None:
            statement = statement.where(ScraperWorkItem.run_id == run_id)
        return int(await self.session.scalar(statement) or 0)

    async def _count_apps_with_statuses(
        self,
        statuses: list[str],
        *,
        exclude_available: bool = False,
        exclude_review: bool = False,
    ) -> int:
        """Ejecuta el paso interno `_count_apps_with_statuses`.

        Args:
            statuses (list[str]): Valor de `statuses` utilizado por la operación.
            exclude_available (bool): Valor de `exclude_available` utilizado por la operación.
            exclude_review (bool): Valor de `exclude_review` utilizado por la operación.

        Returns:
            int: Número de elementos afectados por la operación.
        """
        source_query = (
            select(DownloadSource.id)
            .where(DownloadSource.software_app_id == SoftwareApp.id)
            .where(DownloadSource.resolution_status.in_(statuses))
        )
        if set(statuses) == {ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value}:
            source_query = source_query.where(DownloadSource.validation_status == "valid")
        source_exists = source_query.limit(1).exists()
        stmt = (
            select(func.count(SoftwareApp.id))
            .where(SoftwareApp.app_status == "active")
            .where(source_exists)
        )
        if exclude_available:
            available_exists = (
                select(DownloadSource.id)
                .where(DownloadSource.software_app_id == SoftwareApp.id)
                .where(
                    DownloadSource.resolution_status.in_(
                        [ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value]
                    )
                )
                .where(DownloadSource.validation_status == "valid")
                .limit(1)
                .exists()
            )
            stmt = stmt.where(~available_exists)
        if exclude_review:
            review_exists = (
                select(DownloadSource.id)
                .where(DownloadSource.software_app_id == SoftwareApp.id)
                .where(
                    DownloadSource.resolution_status
                    == ResolutionStatus.REQUIRES_MANUAL_REVIEW.value
                )
                .limit(1)
                .exists()
            )
            stmt = stmt.where(~review_exists)
        return int(await self.session.scalar(stmt) or 0)


def sanitize_snapshot_html(html: str | None) -> str | None:
    """Ejecuta la operación `sanitize_snapshot_html`.

    Args:
        html (str | None): Valor de `html` utilizado por la operación.

    Returns:
        str | None: Resultado producido por la operación.
    """
    if not html:
        return None

    bounded = truncate_snapshot_bytes(html)
        # La expresión anterior de etiquetas script anidadas podía retroceder en exceso
        # con scripts grandes. Este patrón acotado y no anidado basta para la vista previa
        # del monitor y mantiene un coste predecible.
    cleaned = re.sub(
        r"<script\b[^>]*>.*?(?:</script\s*>|\Z)",
        "",
        bounded,
        flags=re.I | re.S,
    )
    cleaned = re.sub(r"\s+on[a-z]+\s*=\s*(['\"]).*?\1", "", cleaned, flags=re.I | re.S)
    cleaned = re.sub(r"\s+on[a-z]+\s*=\s*[^\s>]+", "", cleaned, flags=re.I)
    return truncate_snapshot_bytes(cleaned)


def truncate_snapshot_bytes(value: str) -> str:
    """Ejecuta la operación `truncate_snapshot_bytes`.

    Args:
        value (str): Valor que debe procesarse.

    Returns:
        str: Resultado producido por la operación.
    """
    encoded = value.encode("utf-8", errors="ignore")
    if len(encoded) <= MAX_SNAPSHOT_HTML_BYTES:
        return value
    preview = encoded[:MAX_SNAPSHOT_HTML_BYTES].decode("utf-8", errors="ignore")
    return preview + "\n<!-- snapshot truncated -->"


def truncate(value: str | None, max_length: int) -> str | None:
    """Ejecuta la operación `truncate`.

    Args:
        value (str | None): Valor que debe procesarse.
        max_length (int): Valor de `max_length` utilizado por la operación.

    Returns:
        str | None: Resultado producido por la operación.
    """
    if value is None:
        return None
    return value if len(value) <= max_length else value[: max_length - 3] + "..."


def statement_rowcount(result: object) -> int:
    """Ejecuta la operación `statement_rowcount`.

    Args:
        result (object): Resultado que debe procesarse.

    Returns:
        int: Número de elementos afectados por la operación.
    """
    return int(getattr(result, "rowcount", 0) or 0)
