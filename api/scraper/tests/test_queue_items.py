"""Los consumidores comparten reserva y drenaje manteniendo las esperas de cada etapa."""
import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from sqlalchemy.exc import OperationalError
from sqlalchemy.exc import TimeoutError as SQLAlchemyTimeoutError

from app.core.config import Settings
from app.scraper import pipeline_support


@pytest.mark.parametrize("stage,failure,delay", [
    ("filter", OperationalError("SELECT", {}, Exception()), 0.5),
    ("filter", SQLAlchemyTimeoutError(), 0.5),
    ("scraper", OperationalError("SELECT", {}, Exception()), 0.25),
    ("scraper", SQLAlchemyTimeoutError(), 1),
    ("filter", RuntimeError("unavailable"), 1),
    ("scraper", RuntimeError("unavailable"), 1),
])
async def test_claim_retry_preserves_each_stage_delay(monkeypatch, stage, failure, delay):
    upstream = asyncio.Event()
    upstream.set()
    runtime = SimpleNamespace(stop_event=asyncio.Event(), run_id=uuid4(),
                              before_next_item=AsyncMock(return_value=True))
    claim = AsyncMock(side_effect=[failure, None])
    sleep = AsyncMock()
    monkeypatch.setattr(pipeline_support, "claim_item", claim)
    monkeypatch.setattr(pipeline_support, "queue_has_active_work", AsyncMock(return_value=False))
    monkeypatch.setattr(pipeline_support.asyncio, "sleep", sleep)
    items = [item async for item in pipeline_support.queue_items(
        Settings(), runtime, "queue", "worker", stage=stage, upstream_done=(upstream,),
    )]
    assert items == []
    assert claim.await_count == 2
    sleep.assert_awaited_once_with(delay)


async def test_drain_waits_for_upstream_and_active_work(monkeypatch):
    upstream = asyncio.Event()
    runtime = SimpleNamespace(stop_event=asyncio.Event(), run_id=uuid4(),
                              before_next_item=AsyncMock(return_value=True))
    claimed = object()
    claim = AsyncMock(side_effect=[None, None, claimed, None])
    active = AsyncMock(side_effect=[True, False])
    sleep = AsyncMock(side_effect=lambda _delay: upstream.set())
    monkeypatch.setattr(pipeline_support, "claim_item", claim)
    monkeypatch.setattr(pipeline_support, "queue_has_active_work", active)
    monkeypatch.setattr(pipeline_support.asyncio, "sleep", sleep)
    items = [item async for item in pipeline_support.queue_items(
        Settings(), runtime, "queue", "worker", stage="filter", upstream_done=(upstream,),
    )]
    assert items == [claimed]
    assert claim.await_count == 4
    assert active.await_count == 2
    assert sleep.await_count == 2


async def test_cooperative_stop_prevents_a_new_claim(monkeypatch):
    runtime = SimpleNamespace(stop_event=asyncio.Event(), run_id=uuid4(),
                              before_next_item=AsyncMock(return_value=False))
    claim = AsyncMock()
    monkeypatch.setattr(pipeline_support, "claim_item", claim)
    assert [item async for item in pipeline_support.queue_items(
        Settings(), runtime, "queue", "worker", stage="filter", upstream_done=(),
    )] == []
    claim.assert_not_awaited()
