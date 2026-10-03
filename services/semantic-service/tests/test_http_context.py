"""El API no acepta peticiones hasta completar y verificar sus migraciones."""

from unittest.mock import Mock, call

import pytest
from fastapi import FastAPI

from app import http_context
from app.database import Database


@pytest.mark.asyncio
async def test_startup_migrates_before_serving(monkeypatch: pytest.MonkeyPatch) -> None:
    database = Mock(spec=Database)
    monkeypatch.setattr(http_context, "database", database)

    async with http_context.lifespan(FastAPI()):
        assert database.mock_calls == [call.open(), call.migrate(), call.verify_schema()]

    assert database.mock_calls[-1] == call.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("stage", ["migrate", "verify_schema"])
async def test_schema_failure_stops_startup_and_closes_pool(
    monkeypatch: pytest.MonkeyPatch, stage: str,
) -> None:
    database = Mock(spec=Database)
    getattr(database, stage).side_effect = RuntimeError("schema_failure")
    monkeypatch.setattr(http_context, "database", database)

    with pytest.raises(RuntimeError, match="schema_failure"):
        async with http_context.lifespan(FastAPI()):
            pytest.fail("El API no debe arrancar con un esquema inválido")

    database.close.assert_called_once_with()
    if stage == "migrate":
        database.verify_schema.assert_not_called()
