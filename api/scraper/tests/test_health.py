"""Verifica los contratos de vida y disponibilidad del scraper."""

import httpx
import pytest

from app.api import routes
from app.main import app


@pytest.mark.asyncio
async def test_health_liveness_does_not_require_database() -> None:
    """Liveness confirma el proceso sin consultar MySQL."""
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/api/health/live")

    assert response.status_code == 200
    assert response.json()["status"] == "ok"


@pytest.mark.asyncio
@pytest.mark.parametrize(("ready", "status"), ((True, 200), (False, 503)))
async def test_health_readiness_reflects_database(
    monkeypatch: pytest.MonkeyPatch,
    ready: bool,
    status: int,
) -> None:
    """Readiness refleja una consulta real a MySQL mediante una dependencia sustituible."""

    async def database_ready() -> bool:
        return ready

    monkeypatch.setattr(routes, "database_ready", database_ready)
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/api/health/ready")

    assert response.status_code == status
    assert response.json()["database"] is ready
