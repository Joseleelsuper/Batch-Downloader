"""Coordina en la base de datos los turnos de llamadas LLM compartidos por los procesos de
descripciones.
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

LLM_REQUEST_INTERVAL_SECONDS = 5.0



class DatabaseLLMRateLimiter:
    """Reserva turnos bajo bloqueo de la fila descriptor_llm y espera fuera de la transacción
    para no retener la conexión durante la pausa.

    See Also:
        app.db.models.ScraperRateLimit: Guarda el próximo instante permitido.
    """
    def __init__(
        self,
        *,
        interval_seconds: float = LLM_REQUEST_INTERVAL_SECONDS,
        now: Callable[[], datetime] = utc_now,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        """Configura la separación de solicitudes y permite sustituir reloj y espera para probar
        reservas sin pausas reales.

        Args:
            interval_seconds: Separación en segundos entre turnos de solicitudes LLM.
            now: Función que devuelve un instante UTC sin tzinfo.
            sleep: Espera asíncrona sustituible en pruebas; recibe segundos.
        """
        self.interval_seconds = interval_seconds

        self.now = now

        self.sleep = sleep


    async def wait_for_slot(self) -> datetime:
        """Abre una sesión independiente, reserva el siguiente turno y confirma antes de esperar
        hasta su instante.

        Returns:
            instante UTC reservado para la solicitud.
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
