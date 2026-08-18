"""Contiene las pruebas de `test_worker`.
"""
from __future__ import annotations

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

    async def scrape(*, recover_running: bool = False) -> None:
        """Ejecuta la operación `scrape`.

        Args:
            recover_running (bool): Valor de `recover_running` utilizado por la operación.
        """
        calls.append(("scrape", recover_running))

    monkeypatch.setattr(worker, "repair_known_apps", repair)
    monkeypatch.setattr(worker, "scrape_once", scrape)

    await worker.run_startup_scrape()

    assert calls == ["repair", ("scrape", True)]


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

    async def scrape(*, recover_running: bool = False) -> None:
        """Ejecuta la operación `scrape`.

        Args:
            recover_running (bool): Valor de `recover_running` utilizado por la operación.
        """
        calls.append(("scrape", recover_running))

    monkeypatch.setattr(worker, "repair_known_apps", repair)
    monkeypatch.setattr(worker, "scrape_once", scrape)

    await worker.run_startup_scrape()

    assert calls == ["repair", ("scrape", True)]


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
