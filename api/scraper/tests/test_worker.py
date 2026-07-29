from __future__ import annotations

import pytest

import app.worker as worker


@pytest.mark.asyncio
async def test_startup_scrape_repairs_known_apps_before_catalog(monkeypatch) -> None:
    calls: list[object] = []

    async def repair() -> None:
        calls.append("repair")

    async def scrape(*, recover_running: bool = False) -> None:
        calls.append(("scrape", recover_running))

    monkeypatch.setattr(worker, "repair_known_apps", repair)
    monkeypatch.setattr(worker, "scrape_once", scrape)

    await worker.run_startup_scrape()

    assert calls == ["repair", ("scrape", True)]


@pytest.mark.asyncio
async def test_startup_scrape_continues_when_known_app_repair_fails(monkeypatch) -> None:
    calls: list[object] = []

    async def repair() -> None:
        calls.append("repair")
        raise RuntimeError("temporary provider failure")

    async def scrape(*, recover_running: bool = False) -> None:
        calls.append(("scrape", recover_running))

    monkeypatch.setattr(worker, "repair_known_apps", repair)
    monkeypatch.setattr(worker, "scrape_once", scrape)

    await worker.run_startup_scrape()

    assert calls == ["repair", ("scrape", True)]
