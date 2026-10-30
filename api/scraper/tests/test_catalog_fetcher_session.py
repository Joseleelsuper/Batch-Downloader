"""Regresiones de la transacción del coordinador durante scrapes largos."""

from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine

from app.core.config import Settings
from app.core.time import utc_now
from app.db.enums import ScrapeRunStatus
from app.scraper import catalog_fetcher


@pytest.mark.asyncio
@pytest.mark.parametrize("repaired", [0, 1])
@pytest.mark.parametrize("worker_fails", [False, True])
async def test_coordinator_releases_preparation_transaction(
    monkeypatch, repaired: int, worker_fails: bool,
) -> None:
    """La reparación sin cambios también libera la conexión antes de ejecutar workers."""
    engine = create_async_engine("sqlite+aiosqlite://")
    run = SimpleNamespace(id=uuid4(), started_at=utc_now())
    request_id = uuid4()
    failure = RuntimeError("worker failed")
    try:
        async with AsyncSession(engine, expire_on_commit=False) as session:
            fetcher = catalog_fetcher.CatalogFetcher(Settings(), session)

            async def repair() -> int:
                await session.execute(text("SELECT 1"))
                return repaired

            async def consume(_runtime) -> None:
                assert not session.in_transaction(), "coordinator connection held by workers"
                if worker_fails:
                    raise failure

            runs = SimpleNamespace(
                acquire=AsyncMock(return_value=run),
                mark_run_request_started=AsyncMock(),
                finish=AsyncMock(),
                finish_run_request=AsyncMock(),
            )
            fetcher.runs = runs
            fetcher.catalog = SimpleNamespace(sources=SimpleNamespace(
                repair_resolved_source_platforms=repair,
            ))
            monkeypatch.setattr(fetcher, "_prepare_run_request", AsyncMock())
            monkeypatch.setattr(fetcher, "_recover_pipeline", AsyncMock(return_value=False))
            monkeypatch.setattr(fetcher, "_command_monitor", AsyncMock())
            monkeypatch.setattr(fetcher, "_heartbeat", AsyncMock())
            monkeypatch.setattr(fetcher, "_run_scraper_workers", AsyncMock())
            monkeypatch.setattr(fetcher, "_run_so_filter_workers", AsyncMock())
            monkeypatch.setattr(catalog_fetcher, "SearcherWorker", lambda _: SimpleNamespace(
                run=consume,
            ))
            monkeypatch.setattr(catalog_fetcher, "FilterWorker", lambda _: SimpleNamespace(
                run=AsyncMock(),
            ))
            monkeypatch.setattr(session, "get", AsyncMock(return_value=None))

            if worker_fails:
                with pytest.raises(RuntimeError, match="worker failed"):
                    await fetcher.scrape_once(request_id=request_id)
            else:
                await fetcher.scrape_once(request_id=request_id)

            status = ScrapeRunStatus.FAILED if worker_fails else ScrapeRunStatus.COMPLETED
            assert runs.finish.await_args.args == (run.id, status)
            assert runs.finish_run_request.await_args.args == (request_id,)
            assert runs.finish_run_request.await_args.kwargs["status"] == status.value
    finally:
        await engine.dispose()
