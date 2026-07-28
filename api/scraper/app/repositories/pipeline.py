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
QUEUE_FILTER_SCRAPER = "filter_scraper"
QUEUE_SCRAPER_SO_FILTER = "scraper_so_filter"
QUEUE_SO_FILTER_DESCRIPTOR = "so_filter_descriptor"
QUEUE_MANUAL_INSTALLER_ENRICHMENT = "manual_installer_enrichment"
QUEUE_WEBSITE_APP_DISCOVERY = "website_app_discovery"

# Snapshots back the live admin monitor; they are previews, never an archive of
# an official web page. Bound the raw input before sanitizing so a large page
# cannot monopolize a scraper worker in a regular-expression pass.
MAX_SNAPSHOT_HTML_BYTES = 24_000

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
    queued_scraper_so_filter: int
    queued_so_filter_descriptor: int
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

    async def recover_orphaned_run_items(self) -> int:
        """Release work owned by a run that cannot still have live workers.

        A scheduler restart marks its previous run as failed immediately. Its
        leases may still have minutes remaining, so waiting for normal lease
        expiry leaves the admin panel apparently stuck after a restart.
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
        return statement_rowcount(result)

    async def clear_pending(self) -> int:
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
        item_id = await self.session.scalar(
            select(ScraperWorkItem.id)
            .where(ScraperWorkItem.queue == queue)
            .where(ScraperWorkItem.package_id == package_id)
            .where(ScraperWorkItem.status.in_([STATUS_QUEUED, STATUS_IN_PROGRESS]))
            .limit(1)
        )
        return item_id is not None

    async def item_statuses(self, queue: str, package_ids: list[str]) -> dict[str, str]:
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
            .with_for_update(skip_locked=True)
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

    async def requeue(
        self,
        item: ScraperWorkItem,
        reason: str,
        *,
        delay_seconds: int = 2,
    ) -> None:
        now = utc_now()
        item.status = STATUS_QUEUED
        item.last_error = truncate(reason, 1000)
        item.lease_owner = None
        item.lease_expires_at = None
        item.available_at = now + timedelta(seconds=max(0, delay_seconds))
        item.updated_at = now
        await self.session.flush()

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
        now = utc_now()
        self.session.add(
            ScraperWorkerSnapshot(
                # Snapshots are a best-effort live view. Referencing the actively
                # updated scrape run makes every insert take an FK lock on that row
                # and can deadlock concurrent workers with `set_current`.
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
        result = await self.session.execute(
            delete(ScraperWorkerSnapshot).where(
                ScraperWorkerSnapshot.expires_at < utc_now()
            )
        )
        await self.session.flush()
        return statement_rowcount(result)

    async def latest_snapshots(self) -> list[WorkerSnapshotView]:
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

    async def _count_queue(self, queue: str) -> int:
        return int(
            await self.session.scalar(
                select(func.count(ScraperWorkItem.id))
                .where(ScraperWorkItem.queue == queue)
                .where(ScraperWorkItem.status.in_([STATUS_QUEUED, STATUS_IN_PROGRESS]))
            )
            or 0
        )

    async def _count_apps_with_statuses(
        self,
        statuses: list[str],
        *,
        exclude_available: bool = False,
        exclude_review: bool = False,
    ) -> int:
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
    if not html:
        return None

    bounded = truncate_snapshot_bytes(html)
    # The previous nested script-tag expression could backtrack heavily on
    # pages with large inline scripts. This bounded, non-nested pattern is
    # sufficient for the monitor preview and remains predictable.
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
    encoded = value.encode("utf-8", errors="ignore")
    if len(encoded) <= MAX_SNAPSHOT_HTML_BYTES:
        return value
    preview = encoded[:MAX_SNAPSHOT_HTML_BYTES].decode("utf-8", errors="ignore")
    return preview + "\n<!-- snapshot truncated -->"


def truncate(value: str | None, max_length: int) -> str | None:
    if value is None:
        return None
    return value if len(value) <= max_length else value[: max_length - 3] + "..."


def statement_rowcount(result: object) -> int:
    """Return the affected-row count exposed by SQLAlchemy DML cursor results."""
    return int(getattr(result, "rowcount", 0) or 0)
