"""Implementa las responsabilidades del módulo `rate_limits`.
"""
from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from datetime import datetime, timedelta

from sqlalchemy import select

from app.core.time import utc_now
from app.db.models import ScraperRateLimit
from app.db.session import AsyncSessionLocal

LLM_RATE_LIMIT_KEY = "descriptor_llm"
"""Constante que define `LLM_RATE_LIMIT_KEY`.
"""
LLM_REQUEST_INTERVAL_SECONDS = 5.0
"""Constante que define `LLM_REQUEST_INTERVAL_SECONDS`.
"""


class DatabaseLLMRateLimiter:
    """Representa el componente `DatabaseLLMRateLimiter`.
    """
    def __init__(
        self,
        *,
        interval_seconds: float = LLM_REQUEST_INTERVAL_SECONDS,
        now: Callable[[], datetime] = utc_now,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        """Inicializa una instancia de `DatabaseLLMRateLimiter`.

        Args:
            interval_seconds (float): Valor de `interval_seconds` utilizado por la operación.
            now (Callable[[], datetime]): Valor de `now` utilizado por la operación.
            sleep (Callable[[float], Awaitable[None]]): Valor de `sleep` utilizado por la operación.
        """
        self.interval_seconds = interval_seconds
        """Estado de instancia asociado a `interval_seconds`.
        """
        self.now = now
        """Estado de instancia asociado a `now`.
        """
        self.sleep = sleep
        """Estado de instancia asociado a `sleep`.
        """

    async def wait_for_slot(self) -> datetime:
        """Ejecuta `wait_for_slot` dentro de `DatabaseLLMRateLimiter`.

        Returns:
            datetime: Resultado producido por la operación.
        """
        async with AsyncSessionLocal() as session:
            async with session.begin():
                row = await session.scalar(
                    select(ScraperRateLimit)
                    .where(ScraperRateLimit.key == LLM_RATE_LIMIT_KEY)
                    .with_for_update()
                )
                now = self.now()
                reserved_at = max(now, row.next_allowed_at) if row else now
                next_allowed_at = reserved_at + timedelta(seconds=self.interval_seconds)
                if row:
                    row.next_allowed_at = next_allowed_at
                    row.updated_at = now
                else:
                    session.add(
                        ScraperRateLimit(
                            key=LLM_RATE_LIMIT_KEY,
                            next_allowed_at=next_allowed_at,
                            updated_at=now,
                        )
                    )

        delay = max(0.0, (reserved_at - self.now()).total_seconds())
        if delay:
            await self.sleep(delay)
        return reserved_at
