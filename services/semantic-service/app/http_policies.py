"""Dependencias HTTP que envuelven las operaciones semánticas internas."""
from __future__ import annotations

import asyncio
import secrets
from collections.abc import AsyncIterator
from typing import Annotated

from fastapi import Header, HTTPException

INTERNAL_SERVICE_TOKEN_HEADER = "X-Internal-Service-Token"


class InternalServiceTokenGuard:
    """Autoriza peticiones internas sin mezclar el secreto con la búsqueda."""

    def __init__(self, expected_token: str) -> None:
        self._expected_token = expected_token

    async def __call__(
        self,
        provided_token: Annotated[
            str | None,
            Header(alias=INTERNAL_SERVICE_TOKEN_HEADER),
        ] = None,
    ) -> None:
        matches = secrets.compare_digest(provided_token or "", self._expected_token)
        if not self._expected_token or not matches:
            raise HTTPException(status_code=401, detail={"code": "invalid_internal_token"})


class SearchCapacityGuard:
    """Reserva y libera una plaza de búsqueda con una espera máxima."""

    def __init__(self, slots: asyncio.Semaphore, wait_seconds: float) -> None:
        self._slots = slots
        self._wait_seconds = wait_seconds

    async def __call__(self) -> AsyncIterator[None]:
        try:
            await asyncio.wait_for(
                self._slots.acquire(),
                timeout=self._wait_seconds,
            )
        except TimeoutError as exception:
            raise HTTPException(
                status_code=503,
                detail={"code": "service_busy"},
                headers={"Retry-After": "1"},
            ) from exception
        try:
            yield
        finally:
            self._slots.release()
