from datetime import timedelta

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.core.time import utc_now
from app.db.base import Base
from app.db.models import ScraperWorkItem
from app.repositories.pipeline import (
    QUEUE_FILTER_SCRAPER,
    QUEUE_SEARCHER_FILTER,
    STATUS_COMPLETED,
    STATUS_DISCARDED,
    STATUS_FAILED,
    STATUS_IN_PROGRESS,
    STATUS_QUEUED,
    PipelineRepository,
)


@pytest.fixture
async def db_session():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    async with session_factory() as session:
        yield session
    await engine.dispose()


@pytest.mark.asyncio
async def test_recover_stuck_requeues_expired_and_null_leases(db_session) -> None:
    now = utc_now()
    db_session.add_all(
        [
            work_item("Vendor.Expired", STATUS_IN_PROGRESS, now - timedelta(seconds=1)),
            work_item("Vendor.NullLease", STATUS_IN_PROGRESS, None),
            work_item("Vendor.Fresh", STATUS_IN_PROGRESS, now + timedelta(minutes=5)),
            work_item("Vendor.Queued", STATUS_QUEUED, None),
        ]
    )
    await db_session.commit()

    affected = await PipelineRepository(db_session).recover_stuck()
    await db_session.commit()

    rows = await db_session.scalars(select(ScraperWorkItem))
    by_package = {item.package_id: item for item in rows}
    assert affected == 2
    assert by_package["Vendor.Expired"].status == STATUS_QUEUED
    assert by_package["Vendor.NullLease"].status == STATUS_QUEUED
    assert by_package["Vendor.Fresh"].status == STATUS_IN_PROGRESS
    assert by_package["Vendor.Queued"].status == STATUS_QUEUED


@pytest.mark.asyncio
async def test_retry_failed_and_prune_terminal_do_not_touch_queued(db_session) -> None:
    db_session.add_all(
        [
            work_item("Vendor.Failed", STATUS_FAILED, None),
            work_item("Vendor.Completed", STATUS_COMPLETED, None),
            work_item("Vendor.Discarded", STATUS_DISCARDED, None),
            work_item("Vendor.Queued", STATUS_QUEUED, None),
        ]
    )
    await db_session.commit()

    retried = await PipelineRepository(db_session).retry_failed()
    pruned = await PipelineRepository(db_session).prune_terminal()
    await db_session.commit()

    rows = await db_session.scalars(select(ScraperWorkItem))
    by_package = {item.package_id: item for item in rows}
    assert retried == 1
    assert pruned == 2
    assert by_package["Vendor.Failed"].status == STATUS_QUEUED
    assert by_package["Vendor.Queued"].status == STATUS_QUEUED
    assert "Vendor.Completed" not in by_package
    assert "Vendor.Discarded" not in by_package


def work_item(package_id: str, status: str, lease_expires_at):
    return ScraperWorkItem(
        queue=QUEUE_SEARCHER_FILTER if package_id != "Vendor.Failed" else QUEUE_FILTER_SCRAPER,
        status=status,
        package_id=package_id,
        app_name=package_id,
        payload_json={"package_id": package_id},
        lease_owner="test-worker" if status == STATUS_IN_PROGRESS else None,
        lease_expires_at=lease_expires_at,
        last_error="previous_error" if status == STATUS_FAILED else None,
        available_at=utc_now(),
    )
