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
from app.db.enums import ResolutionStatus
from app.db.models import (
    DownloadSource,
    ScraperMetricSnapshot,
    ScraperWorkerSnapshot,
    ScraperWorkItem,
    SoftwareApp,
)

QUEUE_SEARCHER_FILTER = "searcher_filter"
QUEUE_FILTER_SCRAPER = "filter_scraper"
QUEUE_SCRAPER_DESCRIPTOR = "scraper_descriptor"

STATUS_QUEUED = "queued"
STATUS_IN_PROGRESS = "in_progress"
STATUS_COMPLETED = "completed"
STATUS_DISCARDED = "discarded"
STATUS_FAILED = "failed"


@dataclass(frozen=True)
class QueuePreviewItem:
    id: str
    package_id: str
    app_name: str | None
    status: str
    attempts: int
    updated_at: object


@dataclass(frozen=True)
class QueueState:
    queue: str
    counts: dict[str, int]
    items: list[QueuePreviewItem]


@dataclass(frozen=True)
class WorkerSnapshotView:
    stage: str
    package_id: str | None
    app_name: str | None
    url: str | None
    html: str | None
    captured_at: object


@dataclass(frozen=True)
class MetricSnapshotView:
    available: int
    review: int
    unavailable: int
    queued_searcher_filter: int
    queued_filter_scraper: int
    queued_scraper_descriptor: int
    captured_at: object


@dataclass(frozen=True)
class QueueMaintenanceResult:
    action: str
    affected: int


class PipelineRepository:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def reset_expired_leases(self) -> int:
        return await self.recover_stuck()

    async def recover_stuck(self) -> int:
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

    async def retry_failed(self) -> int:
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
        result = await self.session.execute(
            delete(ScraperWorkItem).where(
                ScraperWorkItem.status.in_([STATUS_COMPLETED, STATUS_DISCARDED])
            )
        )
        await self.session.flush()
        return int(result.rowcount or 0)

    async def clear_pending(self) -> int:
        result = await self.session.execute(
            delete(ScraperWorkItem).where(
                ScraperWorkItem.status.in_(
                    [STATUS_QUEUED, STATUS_FAILED, STATUS_COMPLETED, STATUS_DISCARDED]
                )
            )
        )
        await self.session.flush()
        return int(result.rowcount or 0)

    async def clear_all(self) -> int:
        result = await self.session.execute(delete(ScraperWorkItem))
        await self.session.flush()
        return int(result.rowcount or 0)

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
            should_requeue = existing.status in {
                STATUS_QUEUED,
                STATUS_FAILED,
                STATUS_DISCARDED,
            } or (
                existing.status == STATUS_COMPLETED
                and queue == QUEUE_SCRAPER_DESCRIPTOR
                and (force or payload_changed)
            )
            if should_requeue:
                existing.status = STATUS_QUEUED
                existing.payload_json = json_safe(payload)
                existing.app_name = app_name or existing.app_name
                existing.run_id = run_id or existing.run_id
                existing.priority = max(existing.priority, priority)
                existing.last_error = None
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

    async def claim_next(
        self,
        queue: str,
        worker_id: str,
        lease_seconds: int,
    ) -> ScraperWorkItem | None:
        now = utc_now()
        item_id = await self.session.scalar(
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
        )
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
        await self._finish(item, STATUS_COMPLETED, None)

    async def discard(self, item: ScraperWorkItem, reason: str) -> None:
        await self._finish(item, STATUS_DISCARDED, reason)

    async def fail(self, item: ScraperWorkItem, error: str) -> None:
        await self._finish(item, STATUS_FAILED, error)

    async def _finish(self, item: ScraperWorkItem, status: str, message: str | None) -> None:
        item.status = status
        item.last_error = truncate(message, 1000)
        item.lease_owner = None
        item.lease_expires_at = None
        item.updated_at = utc_now()
        await self.session.flush()

    async def has_pending_work(self) -> bool:
        count = await self.session.scalar(
            select(func.count(ScraperWorkItem.id)).where(
                ScraperWorkItem.status.in_([STATUS_QUEUED, STATUS_IN_PROGRESS])
            )
        )
        return bool(count)

    async def queue_depth(self, queue: str) -> int:
        return await self._count_queue(queue)

    async def queue_states(self, limit: int = 20) -> list[QueueState]:
        states: list[QueueState] = []
        for queue in (
            QUEUE_SEARCHER_FILTER,
            QUEUE_FILTER_SCRAPER,
            QUEUE_SCRAPER_DESCRIPTOR,
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
        now = utc_now()
        await self.session.execute(
            delete(ScraperWorkerSnapshot).where(ScraperWorkerSnapshot.expires_at < now)
        )
        self.session.add(
            ScraperWorkerSnapshot(
                run_id=run_id,
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

    async def latest_snapshots(self) -> list[WorkerSnapshotView]:
        snapshots: list[WorkerSnapshotView] = []
        for stage in ("searcher", "filter", "scraper", "descriptor"):
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
        available = await self._count_apps_with_statuses(
            [ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value]
        )
        review = await self._count_apps_with_statuses(
            [ResolutionStatus.REQUIRES_MANUAL_REVIEW.value]
        )
        unavailable = await self._count_apps_with_statuses(
            [ResolutionStatus.MISSING.value, ResolutionStatus.BROKEN.value]
        )
        queued_searcher_filter = await self._count_queue(QUEUE_SEARCHER_FILTER)
        queued_filter_scraper = await self._count_queue(QUEUE_FILTER_SCRAPER)
        queued_scraper_descriptor = await self._count_queue(QUEUE_SCRAPER_DESCRIPTOR)
        self.session.add(
            ScraperMetricSnapshot(
                run_id=run_id,
                available=available,
                review=review,
                unavailable=unavailable,
                queued_searcher_filter=queued_searcher_filter,
                queued_filter_scraper=queued_filter_scraper,
                queued_scraper_descriptor=queued_scraper_descriptor,
                captured_at=utc_now(),
            )
        )
        await self.session.flush()

    async def metric_snapshots(self, limit: int = 60) -> list[MetricSnapshotView]:
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
                queued_scraper_descriptor=item.queued_scraper_descriptor,
                captured_at=item.captured_at,
            )
            for item in reversed(list(result))
        ]

    async def _count_queue(self, queue: str) -> int:
        return int(
            await self.session.scalar(
                select(func.count(ScraperWorkItem.id))
                .where(ScraperWorkItem.queue == queue)
                .where(ScraperWorkItem.status.in_([STATUS_QUEUED, STATUS_IN_PROGRESS]))
            )
            or 0
        )

    async def _count_apps_with_statuses(self, statuses: list[str]) -> int:
        source_exists = (
            select(DownloadSource.id)
            .where(DownloadSource.software_app_id == SoftwareApp.id)
            .where(DownloadSource.resolution_status.in_(statuses))
            .limit(1)
            .exists()
        )
        return int(
            await self.session.scalar(
                select(func.count(SoftwareApp.id))
                .where(SoftwareApp.app_status == "active")
                .where(source_exists)
            )
            or 0
        )


def sanitize_snapshot_html(html: str | None, max_length: int = 200_000) -> str | None:
    if not html:
        return None
    cleaned = re.sub(r"<script\b[^<]*(?:(?!</script>)<[^<]*)*</script>", "", html, flags=re.I)
    cleaned = re.sub(r"\s+on[a-z]+\s*=\s*(['\"]).*?\1", "", cleaned, flags=re.I | re.S)
    cleaned = re.sub(r"\s+on[a-z]+\s*=\s*[^\s>]+", "", cleaned, flags=re.I)
    if len(cleaned) > max_length:
        return cleaned[:max_length] + "\n<!-- snapshot truncated -->"
    return cleaned


def truncate(value: str | None, max_length: int) -> str | None:
    if value is None:
        return None
    return value if len(value) <= max_length else value[: max_length - 3] + "..."
