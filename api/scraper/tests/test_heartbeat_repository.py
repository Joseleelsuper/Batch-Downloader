"""Pruebas de la señal persistente de salud del scheduler."""

import uuid
from datetime import datetime, timedelta

import pytest
import pytest_asyncio
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.db.base import Base
from app.repositories.heartbeat import WorkerHeartbeatRepository


@pytest_asyncio.fixture
async def db_session():
    """Crea un esquema SQLite aislado para evaluar transiciones de salud."""
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    async with session_factory() as session:
        yield session
    await engine.dispose()


@pytest.mark.asyncio
async def test_heartbeat_degrades_after_three_failures_and_recovers(db_session) -> None:
    """Un fallo transitorio no degrada; el tercero sí y un éxito recupera."""
    repository = WorkerHeartbeatRepository(db_session)
    instance_id = uuid.UUID("00000000-0000-0000-0000-000000000091")
    now = datetime(2026, 8, 23, 1, 0, 0)

    missing = await repository.status(
        "scheduler",
        max_age_seconds=45,
        failure_threshold=3,
        now=now,
    )
    assert missing.reason == "missing"

    await repository.success("scheduler", instance_id, now=now)
    for attempt in range(2):
        await repository.failure(
            "scheduler",
            instance_id,
            f"TransientError{attempt}",
            now=now + timedelta(seconds=attempt + 1),
        )
    transient = await repository.status(
        "scheduler",
        max_age_seconds=45,
        failure_threshold=3,
        now=now + timedelta(seconds=3),
    )
    assert transient.healthy is True
    assert transient.consecutive_failures == 2

    await repository.failure(
        "scheduler",
        instance_id,
        "PersistentError",
        now=now + timedelta(seconds=4),
    )
    persistent = await repository.status(
        "scheduler",
        max_age_seconds=45,
        failure_threshold=3,
        now=now + timedelta(seconds=4),
    )
    assert persistent.healthy is False
    assert persistent.reason == "persistent_failures"
    assert persistent.last_error_code == "PersistentError"

    await repository.success(
        "scheduler",
        instance_id,
        now=now + timedelta(seconds=5),
    )
    recovered = await repository.status(
        "scheduler",
        max_age_seconds=45,
        failure_threshold=3,
        now=now + timedelta(seconds=5),
    )
    assert recovered.healthy is True
    assert recovered.consecutive_failures == 0


@pytest.mark.asyncio
async def test_heartbeat_detects_staleness_and_resets_on_new_instance(db_session) -> None:
    """La antigüedad degrada y una identidad nueva reinicia el estado anterior."""
    repository = WorkerHeartbeatRepository(db_session)
    first = uuid.UUID("00000000-0000-0000-0000-000000000092")
    replacement = uuid.UUID("00000000-0000-0000-0000-000000000093")
    now = datetime(2026, 8, 23, 1, 0, 0)

    await repository.success("scheduler", first, now=now)
    stale = await repository.status(
        "scheduler",
        max_age_seconds=45,
        failure_threshold=3,
        now=now + timedelta(seconds=46),
    )
    assert stale.healthy is False
    assert stale.reason == "stale"

    await repository.pulse(
        "scheduler",
        replacement,
        now=now + timedelta(seconds=47),
    )
    restarted = await repository.status(
        "scheduler",
        max_age_seconds=45,
        failure_threshold=3,
        now=now + timedelta(seconds=47),
    )
    assert restarted.healthy is True
    assert restarted.consecutive_failures == 0
