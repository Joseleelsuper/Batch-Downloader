"""Pruebas de la política compartida de autenticación interna."""
from __future__ import annotations

import pytest
from fastapi import HTTPException
from fastapi.routing import APIRoute
from pydantic import SecretStr

from app.api.dependencies import require_internal_service_token
from app.api.routes import router
from app.core.config import Settings


@pytest.mark.asyncio
async def test_rejects_missing_or_wrong_internal_tokens() -> None:
    settings = Settings(internal_service_token=SecretStr("shared-secret"))

    with pytest.raises(HTTPException) as missing:
        await require_internal_service_token(settings, None)
    with pytest.raises(HTTPException) as wrong:
        await require_internal_service_token(settings, "wrong-secret")

    assert missing.value.status_code == 401
    assert wrong.value.detail == {"code": "invalid_internal_token"}


@pytest.mark.asyncio
async def test_accepts_the_shared_internal_token() -> None:
    settings = Settings(internal_service_token=SecretStr("shared-secret"))
    await require_internal_service_token(settings, "shared-secret")


def test_public_router_exposes_only_healthchecks() -> None:
    paths = {
        candidate.path
        for candidate in router.routes
        if isinstance(candidate, APIRoute)
    }

    assert paths == {"/api/health", "/api/health/live", "/api/health/ready"}
