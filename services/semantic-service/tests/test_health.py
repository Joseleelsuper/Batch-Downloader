"""Verifica los contratos de vida y disponibilidad del servicio semántico."""

import json

import pytest

from app import main


@pytest.mark.asyncio
async def test_health_liveness_does_not_require_database() -> None:
    """Liveness confirma el proceso sin consultar PostgreSQL ni cargar modelos."""
    assert await main.health_live() == {
        "status": "ok",
        "service": "semantic-service",
    }


@pytest.mark.asyncio
@pytest.mark.parametrize(("ready", "status"), ((True, 200), (False, 503)))
async def test_health_readiness_reflects_database(
    monkeypatch: pytest.MonkeyPatch,
    ready: bool,
    status: int,
) -> None:
    """Readiness usa PostgreSQL sin depender de que exista un modelo activo."""
    monkeypatch.setattr(main.database, "healthy", lambda: ready)
    monkeypatch.setattr(main, "directory_writable", lambda _path: True)

    response = await main.health_ready()

    assert response.status_code == status
    assert json.loads(response.body)["database"] is ready


@pytest.mark.asyncio
async def test_health_readiness_requires_writable_model_cache(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Readiness rechaza un caché que impediría preparar o cargar modelos."""
    monkeypatch.setattr(main.database, "healthy", lambda: True)
    monkeypatch.setattr(main, "directory_writable", lambda _path: False)

    response = await main.health_ready()

    assert response.status_code == 503
    assert json.loads(response.body)["modelCacheWritable"] is False
