"""Contiene las pruebas de `test_runs_repository`.
"""
from datetime import timedelta

import pytest
import pytest_asyncio
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.core.config import Settings
from app.core.time import utc_now
from app.db.base import Base
from app.db.enums import ScrapeRunStatus, ScrapeScope
from app.db.models import ScrapeRun
from app.repositories.runs import RUN_LOCK_STALE_MINUTES, ScrapeRunRepository


@pytest_asyncio.fixture
async def session_factory():
    """Prepara el recurso `session_factory` usado por las pruebas para aislar el escenario
    `session factory` y conservar sus datos de entrada.
    """
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    factory = async_sessionmaker(engine, expire_on_commit=False)
    yield factory
    await engine.dispose()


@pytest.mark.asyncio
async def test_only_one_fresh_run_can_hold_the_active_lock(session_factory) -> None:
    """Comprueba el escenario `only_one_fresh_run_can_hold_the_active_lock`.

    Args:
        session_factory (Any): Valor de `session_factory` utilizado por la operación.
    """
    async with session_factory() as first_session:
        first = await ScrapeRunRepository(first_session, Settings()).acquire()
        assert first is not None
        await first_session.commit()

    async with session_factory() as second_session:
        second_repository = ScrapeRunRepository(second_session, Settings())
        assert await second_repository.acquire() is None

        await second_repository.finish(first.id, ScrapeRunStatus.COMPLETED)
        await second_session.commit()

    async with session_factory() as third_session:
        replacement = await ScrapeRunRepository(third_session, Settings()).acquire()

    assert replacement is not None
    assert replacement.active_lock == 1


@pytest.mark.asyncio
async def test_acquire_recovers_an_expired_coordinator_lease(session_factory) -> None:
    """Comprueba el escenario `acquire_recovers_an_expired_coordinator_lease`.

    Args:
        session_factory (Any): Valor de `session_factory` utilizado por la operación.
    """
    stale_heartbeat = utc_now() - timedelta(minutes=RUN_LOCK_STALE_MINUTES + 1)
    stale = ScrapeRun(
        active_lock=1,
        status=ScrapeRunStatus.RUNNING.value,
        worker_id="stale-worker",
        started_at=stale_heartbeat,
        heartbeat_at=stale_heartbeat,
    )
    async with session_factory() as session:
        session.add(stale)
        await session.commit()

        replacement = await ScrapeRunRepository(session, Settings()).acquire()

        assert replacement is not None
        assert replacement.active_lock == 1
        assert stale.active_lock is None
        assert stale.status == ScrapeRunStatus.FAILED.value
        assert stale.finished_at is not None
        assert stale.heartbeat_at == stale_heartbeat


@pytest.mark.asyncio
async def test_recovery_keeps_a_recent_heartbeat_and_its_lock(session_factory) -> None:
    """Una ejecución con latido reciente conserva su reserva exclusiva."""
    recent = ScrapeRun(
        active_lock=1,
        status=ScrapeRunStatus.RUNNING.value,
        worker_id="active-worker",
        heartbeat_at=utc_now(),
    )
    async with session_factory() as session:
        session.add(recent)
        await session.commit()

        recovered = await ScrapeRunRepository(session, Settings()).recover_running(
            "stale heartbeat"
        )
        await session.commit()

        assert recovered == 0
        assert recent.active_lock == 1
        assert recent.status == ScrapeRunStatus.RUNNING.value


@pytest.mark.asyncio
async def test_stale_recovery_fails_its_request_and_keeps_pending_requests_fifo(
    session_factory,
) -> None:
    """La recuperación cierra la solicitud atascada y conserva la cola pendiente en orden."""
    stale_heartbeat = utc_now() - timedelta(minutes=RUN_LOCK_STALE_MINUTES + 1)
    async with session_factory() as session:
        repository = ScrapeRunRepository(session, Settings())
        stale_request = await repository.enqueue_run_request(
            scope=ScrapeScope.INCREMENTAL,
            app_ids=None,
            created_by="test-stale",
        )
        first_pending = await repository.enqueue_run_request(
            scope=ScrapeScope.INCREMENTAL,
            app_ids=None,
            created_by="test-first",
        )
        second_pending = await repository.enqueue_run_request(
            scope=ScrapeScope.FULL,
            app_ids=None,
            created_by="test-second",
        )
        stale_request.status = "running"
        stale_run = ScrapeRun(
            active_lock=1,
            status=ScrapeRunStatus.RUNNING.value,
            request_id=stale_request.id,
            worker_id="stale-worker",
            heartbeat_at=stale_heartbeat,
        )
        session.add(stale_run)
        await session.commit()

        recovered = await repository.recover_running("heartbeat expired")
        await session.commit()

        assert recovered == 1
        assert stale_run.status == ScrapeRunStatus.FAILED.value
        assert stale_run.active_lock is None
        assert stale_run.heartbeat_at == stale_heartbeat
        assert stale_request.status == ScrapeRunStatus.FAILED.value
        assert first_pending.status == "pending"
        assert second_pending.status == "pending"

        claimed = await repository.next_pending_run_request()
        assert claimed is not None
        assert claimed.id == first_pending.id
        await repository.consume_command(claimed, status="completed")
        await session.commit()

        claimed_next = await repository.next_pending_run_request()
        assert claimed_next is not None
        assert claimed_next.id == second_pending.id


@pytest.mark.asyncio
async def test_stopped_request_releases_its_active_run(session_factory) -> None:
    """Al confirmar la cancelación, el run y su solicitud quedan fallidos y sin bloqueo."""
    stale_heartbeat = utc_now() - timedelta(minutes=RUN_LOCK_STALE_MINUTES + 1)
    async with session_factory() as session:
        repository = ScrapeRunRepository(session, Settings())
        request = await repository.enqueue_run_request(
            scope=ScrapeScope.INCREMENTAL,
            app_ids=None,
            created_by="test-watchdog",
        )
        request.status = "running"
        run = ScrapeRun(
            active_lock=1,
            status=ScrapeRunStatus.RUNNING.value,
            request_id=request.id,
            worker_id="watchdog-worker",
            heartbeat_at=stale_heartbeat,
        )
        session.add(run)
        await session.commit()

        assert await repository.stale_running_run_id(request.id) == run.id
        failed_run_id = await repository.fail_running_request(
            request.id,
            "stale heartbeat",
        )
        await session.commit()

        assert failed_run_id == run.id
        assert run.status == ScrapeRunStatus.FAILED.value
        assert run.active_lock is None
        assert run.finished_at is not None
        assert request.status == ScrapeRunStatus.FAILED.value


@pytest.mark.asyncio
async def test_durable_run_request_is_not_consumed_as_control_command(session_factory) -> None:
    """Las solicitudes pendientes sobreviven hasta que no exista un run activo."""
    async with session_factory() as session:
        repository = ScrapeRunRepository(session, Settings())
        request = await repository.enqueue_run_request(
            scope=ScrapeScope.SELECTED,
            app_ids=["11111111-1111-4111-8111-111111111111"],
            created_by="test-admin",
        )
        await session.commit()

        assert await repository.next_pending_command() is None
        claimed = await repository.next_pending_run_request()
        assert claimed is not None
        assert claimed.id == request.id

        run = await repository.acquire(scope=ScrapeScope.SELECTED, request_id=request.id)
        assert run is not None
        await repository.mark_run_request_started(request, run.id)
        await repository.set_manifest(
            run.id,
            app_ids=request.app_ids_json or [],
            winstall_ids=["Vendor.App"],
        )
        await session.commit()

        assert request.status == "running"
        assert run.request_id == request.id
        assert run.scope == ScrapeScope.SELECTED.value
        assert run.target_count == 1
