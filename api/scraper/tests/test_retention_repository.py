"""Pruebas de las políticas acotadas de retención del scraper."""
from datetime import timedelta

import pytest
import pytest_asyncio
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.core.time import utc_now
from app.db.base import Base
from app.db.models import (
    ResolverLog,
    ScraperCommand,
    ScraperMetricSnapshot,
    ScrapeRun,
    ScraperWorkerSnapshot,
    ScraperWorkItem,
)
from app.repositories.pipeline import (
    QUEUE_SEARCHER_FILTER,
    STATUS_COMPLETED,
    STATUS_IN_PROGRESS,
    STATUS_QUEUED,
)
from app.repositories.retention import RetentionRepository


@pytest_asyncio.fixture
async def db_session():
    """Crea un esquema SQLite aislado para cada escenario."""
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    async with session_factory() as session:
        yield session
    await engine.dispose()


@pytest.mark.asyncio
async def test_pruner_respects_boundaries_leases_and_per_table_batches(
    db_session,
) -> None:
    """Sólo poda terminales antiguos y limita independientemente cada tabla."""
    now = utc_now()
    old_31 = now - timedelta(days=31)
    old_91 = now - timedelta(days=91)
    boundary_30 = now - timedelta(days=30)

    first_old = work_item("Vendor.OldOne", STATUS_COMPLETED, old_31)
    second_old = work_item("Vendor.OldTwo", STATUS_COMPLETED, old_31)
    boundary = work_item("Vendor.Boundary", STATUS_COMPLETED, boundary_30)
    queued = work_item("Vendor.Pending", STATUS_QUEUED, old_31)
    leased = work_item("Vendor.Leased", STATUS_IN_PROGRESS, old_31)
    leased.lease_owner = "worker-1"
    leased.lease_expires_at = now + timedelta(hours=1)
    db_session.add_all((first_old, second_old, boundary, queued, leased))
    db_session.add_all(
        (
            ScraperMetricSnapshot(captured_at=old_31),
            ScraperMetricSnapshot(captured_at=now),
            ScraperWorkerSnapshot(
                worker_id="old-worker",
                stage="scraper",
                captured_at=old_31,
                expires_at=old_31,
            ),
            ScraperWorkerSnapshot(
                worker_id="active-worker",
                stage="scraper",
                captured_at=now,
                expires_at=now + timedelta(minutes=5),
            ),
            ResolverLog(phase="resolve", status="failed", created_at=old_91),
            ResolverLog(phase="resolve", status="ok", created_at=now),
            ScraperCommand(
                command="pause",
                status="completed",
                created_by="admin",
                consumed_at=old_91,
            ),
            ScraperCommand(
                command="run_once",
                status="pending",
                created_by="admin",
                created_at=old_91,
            ),
            ScrapeRun(
                status="completed",
                worker_id="old-scheduler",
                started_at=old_91,
                heartbeat_at=old_91,
                finished_at=old_91,
            ),
            ScrapeRun(
                status="running",
                worker_id="active-scheduler",
                started_at=old_91,
                heartbeat_at=now,
            ),
        )
    )
    await db_session.commit()

    first = await RetentionRepository(db_session).prune(now=now, batch_size=1)
    await db_session.commit()

    assert first.work_items == 1
    assert first.metric_snapshots == 1
    assert first.worker_snapshots == 1
    assert first.resolver_logs == 1
    assert first.commands == 1
    assert first.runs == 1
    remaining_packages = set(
        await db_session.scalars(select(ScraperWorkItem.package_id))
    )
    assert len({"Vendor.OldOne", "Vendor.OldTwo"} & remaining_packages) == 1
    assert {
        "Vendor.Boundary",
        "Vendor.Pending",
        "Vendor.Leased",
    } <= remaining_packages

    second = await RetentionRepository(db_session).prune(now=now, batch_size=1)
    await db_session.commit()
    assert second.work_items == 1
    assert second.metric_snapshots == 0
    assert second.worker_snapshots == 0
    assert second.resolver_logs == 0
    assert second.commands == 0
    assert second.runs == 0

    third = await RetentionRepository(db_session).prune(now=now, batch_size=1)
    await db_session.commit()
    assert third.total == 0


def work_item(package_id: str, status: str, updated_at) -> ScraperWorkItem:
    """Construye un elemento de cola con antigüedad controlada."""
    return ScraperWorkItem(
        queue=QUEUE_SEARCHER_FILTER,
        status=status,
        package_id=package_id,
        app_name=package_id,
        payload_json={"package_id": package_id},
        available_at=updated_at,
        created_at=updated_at,
        updated_at=updated_at,
    )
