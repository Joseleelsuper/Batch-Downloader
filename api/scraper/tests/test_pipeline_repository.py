"""Contiene las pruebas de `test_pipeline_repository`.
"""
from datetime import timedelta
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.core.time import utc_now
from app.db.base import Base
from app.db.models import ScrapeRun, ScraperWorkerSnapshot, ScraperWorkItem
from app.repositories.pipeline import (
    MAX_SNAPSHOT_HTML_BYTES,
    QUEUE_FILTER_SCRAPER,
    QUEUE_SCRAPER_SO_FILTER,
    QUEUE_SEARCHER_FILTER,
    QUEUE_SO_FILTER_DESCRIPTOR,
    STATUS_COMPLETED,
    STATUS_DISCARDED,
    STATUS_FAILED,
    STATUS_IN_PROGRESS,
    STATUS_QUEUED,
    PipelineRepository,
    sanitize_snapshot_html,
)


@pytest_asyncio.fixture
async def db_session():
    """Ejecuta la operación `db_session`.

    Yields:
        Any: Elemento producido por la operación.
    """
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    async with session_factory() as session:
        yield session
    await engine.dispose()


@pytest.mark.asyncio
async def test_recover_stuck_requeues_expired_and_null_leases(db_session) -> None:
    """Comprueba el escenario `recover_stuck_requeues_expired_and_null_leases`.

    Args:
        db_session (Any): Valor de `db_session` utilizado por la operación.
    """
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
async def test_recover_orphaned_run_items_releases_fresh_leases_after_restart(db_session) -> None:
    """Comprueba el escenario `recover_orphaned_run_items_releases_fresh_leases_after_restart`.

    Args:
        db_session (Any): Valor de `db_session` utilizado por la operación.
    """
    now = utc_now()
    failed_run = ScrapeRun(id=uuid4(), status="failed", worker_id="previous-scheduler")
    active_run = ScrapeRun(id=uuid4(), status="running", worker_id="current-scheduler")
    released = work_item("Vendor.PreviousRun", STATUS_IN_PROGRESS, now + timedelta(minutes=5))
    retained = work_item("Vendor.CurrentRun", STATUS_IN_PROGRESS, now + timedelta(minutes=5))
    released.run_id = failed_run.id
    retained.run_id = active_run.id
    db_session.add_all([failed_run, active_run, released, retained])
    await db_session.commit()

    recovered = await PipelineRepository(db_session).recover_orphaned_run_items()
    await db_session.commit()

    assert recovered == 1
    assert released.status == STATUS_QUEUED
    assert released.lease_owner is None
    assert released.lease_expires_at is None
    assert retained.status == STATUS_IN_PROGRESS


@pytest.mark.asyncio
async def test_retry_failed_and_prune_terminal_do_not_touch_queued(db_session) -> None:
    """Comprueba el escenario `retry_failed_and_prune_terminal_do_not_touch_queued`.

    Args:
        db_session (Any): Valor de `db_session` utilizado por la operación.
    """
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


@pytest.mark.asyncio
async def test_requeue_releases_lease_and_delays_next_attempt(db_session) -> None:
    """Comprueba el escenario `requeue_releases_lease_and_delays_next_attempt`.

    Args:
        db_session (Any): Valor de `db_session` utilizado por la operación.
    """
    item = work_item("Vendor.Locked", STATUS_IN_PROGRESS, utc_now() + timedelta(minutes=1))
    item.lease_owner = "worker-1"
    db_session.add(item)
    await db_session.commit()
    before = utc_now()

    await PipelineRepository(db_session).requeue(item, "mysql_lock_retry", delay_seconds=2)
    await db_session.commit()

    assert item.status == STATUS_QUEUED
    assert item.last_error == "mysql_lock_retry"
    assert item.lease_owner is None
    assert item.lease_expires_at is None
    assert item.available_at >= before + timedelta(seconds=1)


@pytest.mark.asyncio
async def test_completed_catalog_stages_requeue_for_a_new_scrape_run(db_session) -> None:
    """Comprueba el escenario `completed_catalog_stages_requeue_for_a_new_scrape_run`.

    Args:
        db_session (Any): Valor de `db_session` utilizado por la operación.
    """
    repository = PipelineRepository(db_session)
    previous_run = uuid4()
    next_run = uuid4()

    for queue in (QUEUE_SEARCHER_FILTER, QUEUE_FILTER_SCRAPER, QUEUE_SCRAPER_SO_FILTER):
        item = await repository.enqueue(
            queue,
            f"Vendor.{queue}",
            "Vendor App",
            {"package_id": f"Vendor.{queue}"},
            previous_run,
        )
        await repository.complete(item)
    await db_session.commit()

    for queue in (QUEUE_SEARCHER_FILTER, QUEUE_FILTER_SCRAPER, QUEUE_SCRAPER_SO_FILTER):
        item = await repository.enqueue(
            queue,
            f"Vendor.{queue}",
            "Vendor App",
            {"package_id": f"Vendor.{queue}"},
            next_run,
        )
        assert item.status == STATUS_QUEUED
        assert item.run_id == next_run


@pytest.mark.asyncio
async def test_queue_states_expose_so_filter_pipeline_tail(db_session) -> None:
    """Comprueba el escenario `queue_states_expose_so_filter_pipeline_tail`.

    Args:
        db_session (Any): Valor de `db_session` utilizado por la operación.
    """
    repository = PipelineRepository(db_session)
    await repository.enqueue(
        QUEUE_SCRAPER_SO_FILTER,
        "Vendor.SOFilter",
        "SO Filter App",
        {"software_app_id": str(uuid4())},
        uuid4(),
    )
    await repository.enqueue(
        QUEUE_SO_FILTER_DESCRIPTOR,
        "Vendor.Descriptor",
        "Descriptor App",
        {"software_app_id": str(uuid4())},
        uuid4(),
    )
    await db_session.commit()

    states = await repository.queue_states()

    assert [state.queue for state in states] == [
        QUEUE_SEARCHER_FILTER,
        QUEUE_FILTER_SCRAPER,
        QUEUE_SCRAPER_SO_FILTER,
        QUEUE_SO_FILTER_DESCRIPTOR,
    ]
    assert states[2].counts == {STATUS_QUEUED: 1}
    assert states[3].counts == {STATUS_QUEUED: 1}
    assert await repository.item_statuses(
        QUEUE_SCRAPER_SO_FILTER,
        ["Vendor.SOFilter", "Vendor.Missing"],
    ) == {"Vendor.SOFilter": STATUS_QUEUED}


@pytest.mark.asyncio
async def test_so_filter_backfill_does_not_mask_a_later_scrape_result(db_session) -> None:
    """Comprueba el escenario `so_filter_backfill_does_not_mask_a_later_scrape_result`.

    Args:
        db_session (Any): Valor de `db_session` utilizado por la operación.
    """
    repository = PipelineRepository(db_session)
    item = await repository.enqueue(
        QUEUE_SCRAPER_SO_FILTER,
        "Vendor.Race",
        "Race App",
        {"input_hash": "1:backfill"},
        None,
    )
    await repository.complete(item)
    await db_session.commit()

    run_id = uuid4()
    item = await repository.enqueue(
        QUEUE_SCRAPER_SO_FILTER,
        "Vendor.Race",
        "Race App",
        {"input_hash": f"1:{run_id}"},
        run_id,
    )

    assert item.status == STATUS_QUEUED
    assert item.run_id == run_id


@pytest.mark.asyncio
async def test_active_package_ids_detects_only_live_upstream_work(db_session) -> None:
    """Comprueba el escenario `active_package_ids_detects_only_live_upstream_work`.

    Args:
        db_session (Any): Valor de `db_session` utilizado por la operación.
    """
    repository = PipelineRepository(db_session)
    active = await repository.enqueue(
        QUEUE_FILTER_SCRAPER,
        "Vendor.Active",
        "Active App",
        {},
        uuid4(),
    )
    await repository.enqueue(
        QUEUE_SEARCHER_FILTER,
        "Vendor.Active",
        "Active App",
        {},
        uuid4(),
    )
    completed = await repository.enqueue(
        QUEUE_FILTER_SCRAPER,
        "Vendor.CompletedUpstream",
        "Completed App",
        {},
        uuid4(),
    )
    await repository.complete(completed)
    await db_session.commit()

    package_ids = await repository.active_package_ids(
        (QUEUE_SEARCHER_FILTER, QUEUE_FILTER_SCRAPER),
        [active.package_id, completed.package_id, "Vendor.Missing"],
    )

    assert package_ids == {"Vendor.Active"}


def test_snapshot_html_is_bounded_before_sanitizing_large_pages() -> None:
    """Comprueba el escenario `snapshot_html_is_bounded_before_sanitizing_large_pages`.
    """
    html = "<html><script>" + ("a" * 100_000) + "</script>" + ("\u00f1" * 100_000) + "</html>"

    sanitized = sanitize_snapshot_html(html)

    assert sanitized is not None
    assert len(sanitized.encode("utf-8")) <= MAX_SNAPSHOT_HTML_BYTES + 32
    assert "<script" not in sanitized.lower()

    truncated = sanitize_snapshot_html("<html>" + ("a" * 100_000))
    assert truncated is not None
    assert "snapshot truncated" in truncated


@pytest.mark.asyncio
async def test_snapshot_insert_does_not_prune_or_lock_the_active_run(db_session) -> None:
    """Comprueba el escenario `snapshot_insert_does_not_prune_or_lock_the_active_run`.

    Args:
        db_session (Any): Valor de `db_session` utilizado por la operación.
    """
    expired = ScraperWorkerSnapshot(
        worker_id="previous-worker",
        stage="scraper",
        captured_at=utc_now() - timedelta(hours=1),
        expires_at=utc_now() - timedelta(seconds=1),
    )
    db_session.add(expired)
    await db_session.commit()

    repository = PipelineRepository(db_session)
    await repository.save_snapshot(
        run_id=uuid4(),
        worker_id="scraper-worker",
        stage="scraper",
        package_id="Vendor.App",
        app_name="Vendor App",
        url="https://example.com",
        html="<html>snapshot</html>",
    )
    await db_session.commit()

    snapshots = list(await db_session.scalars(select(ScraperWorkerSnapshot)))
    current = next(snapshot for snapshot in snapshots if snapshot.worker_id == "scraper-worker")
    assert len(snapshots) == 2
    assert current.run_id is None

    assert await repository.prune_expired_snapshots() == 1
    await db_session.commit()
    remaining = list(await db_session.scalars(select(ScraperWorkerSnapshot)))
    assert [snapshot.worker_id for snapshot in remaining] == ["scraper-worker"]


def work_item(package_id: str, status: str, lease_expires_at):
    """Ejecuta la operación `work_item`.

    Args:
        package_id (str): Identificador de `package` utilizado por la operación.
        status (str): Valor de `status` utilizado por la operación.
        lease_expires_at (Any): Instante asociado a `lease_expires`.
    """
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
