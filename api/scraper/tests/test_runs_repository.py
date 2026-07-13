from datetime import timedelta

import pytest
import pytest_asyncio
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.core.config import Settings
from app.core.time import utc_now
from app.db.base import Base
from app.db.enums import ScrapeRunStatus
from app.db.models import ScrapeRun
from app.repositories.runs import RUN_LOCK_STALE_MINUTES, ScrapeRunRepository


@pytest_asyncio.fixture
async def session_factory():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    factory = async_sessionmaker(engine, expire_on_commit=False)
    yield factory
    await engine.dispose()


@pytest.mark.asyncio
async def test_only_one_fresh_run_can_hold_the_active_lock(session_factory) -> None:
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
