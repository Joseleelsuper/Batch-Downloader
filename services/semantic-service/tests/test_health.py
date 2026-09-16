"""Comprueba separacion entre presencia del API y disponibilidad del modelo local."""

import json

import pytest

from app import health_router as main


@pytest.mark.asyncio
async def test_health_liveness_does_not_require_database() -> None:
    """Liveness responde ok sin depender de una conexión a PostgreSQL."""
    assert await main.health_live() == {
        "status": "ok",
        "service": "semantic-service",
    }


@pytest.mark.asyncio
async def test_health_keeps_index_contract_when_database_is_unavailable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """El diagnóstico conserva campos mínimos aunque PostgreSQL no responda."""
    monkeypatch.setattr(main.database, "healthy", lambda: False)
    monkeypatch.setattr(
        main,
        "current_model_manifest",
        lambda: (_ for _ in ()).throw(RuntimeError("missing")),
    )

    payload = await main.health()

    assert payload["index"] == {
        "indexVersion": None,
        "expected": 0,
        "indexed": 0,
        "complete": False,
        "builtAt": None,
    }
    assert payload["indexer"]["reason"] == "database_unavailable"


@pytest.mark.asyncio
@pytest.mark.parametrize(("ready", "status"), ((True, 200), (False, 503)))
async def test_health_readiness_reflects_database(
    monkeypatch: pytest.MonkeyPatch,
    ready: bool,
    status: int,
) -> None:
    """Con caché escribible, readiness refleja disponibilidad de base de datos con 200 o 503.

    Args:
        monkeypatch: Sustituciones locales de configuración o colaboradores que pytest
            restaura después de la prueba.
        ready: Resultado simulado de disponibilidad de PostgreSQL.
        status: Código HTTP esperado para la disponibilidad simulada.
    """
    monkeypatch.setattr(main.database, "healthy", lambda: ready)
    monkeypatch.setattr(main, "model_directory_ready", lambda *_args, **_kwargs: True)

    response = await main.health_ready()

    assert response.status_code == status
    assert json.loads(response.body)["database"] is ready


@pytest.mark.asyncio
async def test_health_readiness_requires_writable_model_cache(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Una base de datos sana no compensa una caché sin escritura: readiness devuelve 503 y
    expone la capacidad fallida.

    Args:
        monkeypatch: Sustituciones locales de configuración o colaboradores que pytest
            restaura después de la prueba.
    """
    monkeypatch.setattr(main.database, "healthy", lambda: True)
    monkeypatch.setattr(main, "model_directory_ready", lambda *_args, **_kwargs: False)

    response = await main.health_ready()

    assert response.status_code == 503
    assert json.loads(response.body)["modelReady"] is False
