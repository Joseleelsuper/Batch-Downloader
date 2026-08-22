"""Pruebas de los wrappers HTTP del servicio semántico."""
from __future__ import annotations

import asyncio

import pytest
from fastapi import HTTPException

from app.http_policies import InternalServiceTokenGuard, SearchCapacityGuard


@pytest.mark.asyncio
async def test_internal_token_guard_rejects_missing_token_and_accepts_exact_match() -> None:
    guard = InternalServiceTokenGuard("shared-secret")

    with pytest.raises(HTTPException) as rejected:
        await guard(None)
    await guard("shared-secret")

    assert rejected.value.status_code == 401
    assert rejected.value.detail == {"code": "invalid_internal_token"}


@pytest.mark.asyncio
async def test_capacity_guard_times_out_and_releases_its_slot_in_finally() -> None:
    slots = asyncio.Semaphore(1)
    guard = SearchCapacityGuard(slots, 0.01)
    first = guard()
    await anext(first)

    with pytest.raises(HTTPException) as exhausted:
        await anext(guard())
    await first.aclose()
    recovered = guard()
    await anext(recovered)
    await recovered.aclose()

    assert exhausted.value.status_code == 503
    assert exhausted.value.headers == {"Retry-After": "1"}
