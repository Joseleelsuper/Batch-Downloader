"""Comprueba separación entre presencia del API y disponibilidad de PostgreSQL y caché de
modelos.
"""

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
    monkeypatch.setattr(main, "directory_writable", lambda _path: True)

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
    monkeypatch.setattr(main, "directory_writable", lambda _path: False)

    response = await main.health_ready()

    assert response.status_code == 503
    assert json.loads(response.body)["modelCacheWritable"] is False
