"""Contiene las pruebas de `test_worker`.
"""
from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest

import app.worker as worker


@pytest.mark.asyncio
async def test_startup_scrape_repairs_known_apps_before_catalog(monkeypatch) -> None:
    """Comprueba el escenario `startup_scrape_repairs_known_apps_before_catalog`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    calls: list[object] = []

    async def repair() -> None:
        """Ejecuta la operación `repair`.
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
        """Ejecuta la operación `repair`.

        Throws:
            RuntimeError: Si el estado de ejecución impide completar la operación.
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
async def test_scheduler_exits_when_enrichment_supervisor_fails(monkeypatch) -> None:
    """Comprueba que un fallo del supervisor persistente termine el scheduler."""
    scheduler_stopped: list[bool] = []

    class SchedulerStub:
        """Sustituye APScheduler sin crear tareas persistentes."""

        def __init__(self, **_kwargs) -> None:
            pass

        def add_job(self, *_args, **_kwargs) -> None:
            pass

        def start(self) -> None:
            pass

        def shutdown(self, *, wait: bool) -> None:
            scheduler_stopped.append(not wait)

    class SupervisorStub:
        """Simula la terminación inesperada del supervisor interno."""

        async def run(self) -> None:
            raise RuntimeError("supervisor stopped")

    settings = SimpleNamespace(
        scheduler_zoneinfo="UTC",
        scheduler_hour=3,
        scheduler_minute=0,
        scheduler_timezone="UTC",
        run_on_startup=False,
    )
    monkeypatch.setattr(worker, "get_settings", lambda: settings)
    monkeypatch.setattr(worker, "AsyncIOScheduler", SchedulerStub)
    monkeypatch.setattr(worker, "ContentEnrichmentSupervisor", SupervisorStub)

    with pytest.raises(RuntimeError, match="supervisor stopped"):
        await worker.run_scheduler()

    assert scheduler_stopped == [True]


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
