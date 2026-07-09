from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from datetime import datetime, timedelta

from sqlalchemy import select

from app.core.time import utc_now
from app.db.models import ScraperRateLimit
from app.db.session import AsyncSessionLocal

LLM_RATE_LIMIT_KEY = "descriptor_llm"
LLM_REQUEST_INTERVAL_SECONDS = 5.0


class DatabaseLLMRateLimiter:
    def __init__(
        self,
        *,
        interval_seconds: float = LLM_REQUEST_INTERVAL_SECONDS,
        now: Callable[[], datetime] = utc_now,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self.interval_seconds = interval_seconds
        self.now = now
        self.sleep = sleep

    async def wait_for_slot(self) -> datetime:
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
