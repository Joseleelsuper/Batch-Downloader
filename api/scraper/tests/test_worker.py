"""Contiene las pruebas de `test_worker`.
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timedelta
from types import SimpleNamespace
from uuid import uuid4
from zoneinfo import ZoneInfo

import pytest
from apscheduler.triggers.cron import CronTrigger

import app.worker as worker


@pytest.mark.asyncio
async def test_startup_scrape_repairs_known_apps_before_catalog(monkeypatch) -> None:
    """Comprueba el escenario `startup_scrape_repairs_known_apps_before_catalog`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    calls: list[object] = []

    async def repair() -> None:
        """Prepara el recurso `test_startup_scrape_repairs_known_apps_before_catalog.repair`
        usado por las pruebas para aislar el escenario `test startup scrape repairs known apps
        before catalog.repair` y conservar sus datos de entrada.
        """
        calls.append("repair")

    async def recover() -> int:
        return 0

    async def enqueue(scope, *, created_by: str) -> None:
        calls.append(("enqueue", scope.value, created_by))

    monkeypatch.setattr(worker, "repair_known_apps", repair)
    monkeypatch.setattr(worker, "recover_scheduler_runs", recover)
    monkeypatch.setattr(worker, "enqueue_scrape_request", enqueue)

    await worker.run_startup_scrape()

    assert calls == ["repair", ("enqueue", "incremental", "scheduler:startup")]


@pytest.mark.asyncio
async def test_startup_scrape_continues_when_known_app_repair_fails(monkeypatch) -> None:
    """Comprueba el escenario `startup_scrape_continues_when_known_app_repair_fails`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    calls: list[object] = []

    async def repair() -> None:
        """Prepara el recurso `test_startup_scrape_continues_when_known_app_repair_fails.repair`
        usado por las pruebas para aislar el escenario `test startup scrape continues when
        known app repair fails.repair` y conservar sus datos de entrada.
        """
        calls.append("repair")
        raise RuntimeError("temporary provider failure")

    async def recover() -> int:
        return 0

    async def enqueue(scope, *, created_by: str) -> None:
        calls.append(("enqueue", scope.value, created_by))

    monkeypatch.setattr(worker, "repair_known_apps", repair)
    monkeypatch.setattr(worker, "recover_scheduler_runs", recover)
    monkeypatch.setattr(worker, "enqueue_scrape_request", enqueue)

    await worker.run_startup_scrape()

    assert calls == ["repair", ("enqueue", "incremental", "scheduler:startup")]


@pytest.mark.asyncio
async def test_scheduler_uses_requested_schedule_and_exits_on_supervisor_failure(
    monkeypatch,
) -> None:
    """Verifica el calendario configurado y que un supervisor fallido cierre el scheduler."""
    scheduler_stopped: list[bool] = []
    scheduled_jobs: list[dict[str, object]] = []
    scheduler_timezones: list[str] = []

    class SchedulerStub:
        """Sustituye APScheduler sin crear tareas persistentes."""

        def __init__(self, **_kwargs) -> None:
            scheduler_timezones.append(_kwargs["timezone"])

        def add_job(self, *_args, **_kwargs) -> None:
            scheduled_jobs.append(_kwargs)

        def start(self) -> None:
            pass

        def shutdown(self, *, wait: bool) -> None:
            scheduler_stopped.append(not wait)

    class SupervisorStub:
        """Simula la terminación inesperada del supervisor interno."""

        async def run(self) -> None:
            raise RuntimeError("supervisor stopped")

    settings = SimpleNamespace(
        scheduler_zoneinfo="Europe/Madrid",
        scheduler_hour=3,
        scheduler_minute=0,
        scheduler_timezone="Europe/Madrid",
        run_on_startup=False,
    )

    async def no_op(*_args, **_kwargs):
        return None

    monkeypatch.setattr(worker, "get_settings", lambda: settings)
    monkeypatch.setattr(worker, "AsyncIOScheduler", SchedulerStub)
    monkeypatch.setattr(worker, "ContentEnrichmentSupervisor", SupervisorStub)
    monkeypatch.setattr(worker, "persist_scheduler_heartbeat", no_op)
    monkeypatch.setattr(worker, "recover_scheduler_runs", no_op)
    monkeypatch.setattr(worker, "prune_retained_records", no_op)

    with pytest.raises(RuntimeError, match="supervisor stopped"):
        await worker.run_scheduler()

    assert scheduler_stopped == [True]
    assert scheduler_timezones == ["Europe/Madrid"]
    cron_jobs = [job for job in scheduled_jobs if job["trigger"] == "cron"]
    assert [(job["id"], job["day_of_week"], job["hour"], job["minute"]) for job in cron_jobs] == [
        ("incremental-winstall-scrape", "mon-thu,sat-sun", 3, 0),
        ("weekly-full-winstall-scrape", "fri", 3, 0),
    ]
    zone = ZoneInfo("Europe/Madrid")
    start = datetime(2026, 9, 25, 4, tzinfo=zone)
    end = start + timedelta(days=7)
    for job, expected_days in zip(cron_jobs, ([5, 6, 0, 1, 2, 3], [4]), strict=True):
        trigger = CronTrigger(
            day_of_week=job["day_of_week"],
            hour=job["hour"],
            minute=job["minute"],
            timezone=zone,
        )
        fire_times = []
        previous = None
        cursor = start
        while (next_fire := trigger.get_next_fire_time(previous, cursor)) is not None:
            if next_fire >= end:
                break
            fire_times.append(next_fire)
            previous = next_fire
            cursor = next_fire + timedelta(microseconds=1)
        assert [fire.weekday() for fire in fire_times] == expected_days
        assert all((fire.hour, fire.minute) == (3, 0) for fire in fire_times)


@pytest.mark.asyncio
async def test_stale_scrape_is_cancelled_before_its_lock_is_released(monkeypatch) -> None:
    """El watchdog detiene la tarea antes de marcar el run como fallido y liberar la cola."""
    events: list[str] = []
    run_id = uuid4()
    request_id = uuid4()

    class SessionContext:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args) -> None:
            return None

        async def rollback(self) -> None:
            return None

        async def commit(self) -> None:
            return None

    class RepositoryStub:
        def __init__(self, _session, _settings) -> None:
            pass

        async def stale_running_run_id(self, _request_id):
            return run_id

        async def fail_running_request(self, failed_request_id, _error_summary):
            assert failed_request_id == request_id
            events.append("failed")
            return run_id

    class FetcherStub:
        def __init__(self, _settings, _session) -> None:
            pass

        async def scrape_once(self, **_kwargs) -> None:
            events.append("started")
            try:
                await asyncio.Event().wait()
            finally:
                events.append("cancelled")

    monkeypatch.setattr(worker, "AsyncSessionLocal", SessionContext)
    monkeypatch.setattr(worker, "ScrapeRunRepository", RepositoryStub)
    monkeypatch.setattr(worker, "CatalogFetcher", FetcherStub)
    monkeypatch.setattr(worker, "SCRAPE_WATCHDOG_POLL_SECONDS", 0.001)
    monkeypatch.setattr(worker, "SCRAPE_CANCELLATION_TIMEOUT_SECONDS", 0.1)

    await worker.execute_scrape_with_watchdog(
        object(),
        SessionContext(),
        request_id=request_id,
        scope=worker.ScrapeScope.INCREMENTAL,
        selected_app_ids=[],
    )

    assert events == ["started", "cancelled", "failed"]


@pytest.mark.asyncio
async def test_enrichment_consumer_retries_pool_timeout() -> None:
    """Comprueba que la contención transitoria no termine el scheduler completo."""
    supervisor = worker.ContentEnrichmentSupervisor.__new__(
        worker.ContentEnrichmentSupervisor
    )
    supervisor.settings = SimpleNamespace(
        database_pool_max=2,
        database_pool_timeout_seconds=5,
    )
    calls = 0

    async def consumer() -> None:
        nonlocal calls
        calls += 1
        if calls == 1:
            raise worker.SQLAlchemyTimeoutError()
        raise asyncio.CancelledError

    with pytest.raises(asyncio.CancelledError):
        await supervisor._restart_on_pool_timeout("so-filter-0", consumer)

    assert calls == 2


@pytest.mark.asyncio
async def test_enrichment_consumer_restarts_after_mysql_deadlock() -> None:
    """Un deadlock agotado afecta solo al consumidor que lo recibió."""
    supervisor = worker.ContentEnrichmentSupervisor.__new__(
        worker.ContentEnrichmentSupervisor
    )
    supervisor.settings = SimpleNamespace(
        database_pool_max=2,
        database_pool_timeout_seconds=5,
    )
    calls = 0

    async def consumer() -> None:
        nonlocal calls
        calls += 1
        if calls == 1:
            raise worker.OperationalError("UPDATE", {}, Exception(1213, "Deadlock"))
        raise asyncio.CancelledError

    with pytest.raises(asyncio.CancelledError):
        await supervisor._restart_on_pool_timeout("so-filter-0", consumer)

    assert calls == 2


@pytest.mark.asyncio
async def test_description_consumer_runs_while_scrape_is_active(monkeypatch) -> None:
    """La cola de descripciones se consume sin esperar a que termine el scrape."""
    processed = asyncio.Event()

    class LLMStub:
        def has_provider(self) -> bool:
            return True

    class DescriptorWorkerStub:
        def __init__(self, _settings) -> None:
            self.llm = LLMStub()

        async def process_one(self) -> bool:
            processed.set()
            raise asyncio.CancelledError

    supervisor = worker.ContentEnrichmentSupervisor.__new__(
        worker.ContentEnrichmentSupervisor
    )
    supervisor.settings = SimpleNamespace()

    async def not_paused_or_stopping() -> bool:
        return False

    async def active_scrape() -> bool:
        return True

    supervisor._paused_or_stopping = not_paused_or_stopping
    # Simula el estado que anteriormente bloqueaba este consumidor. La asignación
    # también hace que la prueba falle si se reintroduce esa consulta en el bucle.
    supervisor._scrape_run_active = active_scrape
    monkeypatch.setattr(worker, "DescriptorWorker", DescriptorWorkerStub)

    with pytest.raises(asyncio.CancelledError):
        await asyncio.wait_for(supervisor._consume_descriptions(), timeout=0.2)

    assert processed.is_set()
