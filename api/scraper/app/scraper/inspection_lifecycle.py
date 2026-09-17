"""Persiste el progreso común de inspecciones sin compartir sesiones entre workers."""

from __future__ import annotations

import uuid
from dataclasses import dataclass

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.time import utc_now
from app.db.models import ManualInstallerInspection, ScraperWorkItem, WebsiteAppDiscovery
from app.repositories.pipeline import PipelineRepository

type InspectionRecord = ManualInstallerInspection | WebsiteAppDiscovery


def append_warning(existing: list[str] | None, *warnings: str) -> list[str]:
    """Añade avisos no vacíos una sola vez y conserva su orden de aparición."""
    return list(dict.fromkeys([*(existing or []), *(warning for warning in warnings if warning)]))


async def load_inspection[T: InspectionRecord](
    session: AsyncSession,
    pipeline: PipelineRepository,
    item: ScraperWorkItem,
    model: type[T],
    payload_key: str,
    error_prefix: str,
) -> T | None:
    """Carga la inspección reservada o confirma por qué el trabajo deja de ser ejecutable.

    Un identificador ilegible falla el trabajo; una inspección ausente, aplicada o
    expirada lo descarta. Cada rechazo se confirma en la sesión del worker.
    """
    try:
        record_id = uuid.UUID(str((item.payload_json or {}).get(payload_key)))
    except ValueError, TypeError, AttributeError:
        await pipeline.fail(item, f"invalid_{error_prefix}_id")
        await session.commit()
        return None
    record = await session.get(model, record_id)
    if record is None:
        await pipeline.discard(item, f"{error_prefix}_not_found")
    elif record.status in {"applied", "expired"}:
        await pipeline.discard(item, f"{error_prefix}_{record.status}")
    elif record.expires_at <= utc_now():
        record.status = record.phase = "expired"
        record.error_code = f"{error_prefix}_expired"
        await pipeline.discard(item, record.error_code)
    else:
        return record
    await session.commit()
    return None


@dataclass
class InspectionProgress:
    """Confirma cambios visibles y reintentos sobre la reserva del worker llamador.

    La validación de entradas y la persistencia de instaladores corresponden al
    flujo concreto. ``complete`` confirma también esos cambios en su transacción.

    See Also:
        app.scraper.manual_installer.ManualInstallerWorker
        app.scraper.website_discovery.WebsiteAppDiscoveryWorker
    """

    session: AsyncSession
    pipeline: PipelineRepository
    item: ScraperWorkItem
    record: InspectionRecord
    max_attempts: int

    def _transition(self, status: str, phase: str, error: str | None = None) -> None:
        """Actualiza estado, fase y error sin confirmar aún la transacción."""
        self.record.status, self.record.phase, self.record.error_code = status, phase, error
        self.record.updated_at = utc_now()

    async def start(self) -> None:
        """Hace visible el inicio antes de comenzar las peticiones externas."""
        self._transition("running", "starting")
        await self.session.commit()

    async def set_phase(self, phase: str) -> None:
        """Publica el progreso del inspector conservando el estado de ejecución."""
        self.record.phase, self.record.updated_at = phase, utc_now()
        await self.session.commit()

    async def fail(self, code: str, *, touch: bool = True) -> None:
        """Confirma un fallo definitivo; ``touch=False`` conserva la fecha de entrada."""
        self.record.status = self.record.phase = "failed"
        self.record.error_code = code
        if touch:
            self.record.updated_at = utc_now()
        await self.pipeline.fail(self.item, code)
        await self.session.commit()

    async def expire(self, code: str) -> None:
        """Descarta resultados cuya aplicación de destino ha cambiado durante la inspección."""
        self._transition("expired", "expired", code)
        await self.pipeline.discard(self.item, code)
        await self.session.commit()

    async def retry(self, code: str) -> None:
        """Reencola con espera exponencial de hasta 60 segundos o agota el presupuesto."""
        if self.item.attempts >= self.max_attempts:
            await self.fail(code)
            return
        self._transition("queued", "retry_wait")
        self.record.warnings_json = append_warning(self.record.warnings_json, f"retry:{code}")
        await self.pipeline.requeue(
            self.item, code, delay_seconds=min(60, 2 ** max(1, self.item.attempts))
        )
        await self.session.commit()

    async def complete(self, result: dict, warnings: list[str]) -> None:
        """Publica sugerencias y avisos junto con la finalización atómica del trabajo."""
        self.record.result_json = result
        self.record.warnings_json = append_warning(self.record.warnings_json, *warnings)
        self._transition("ready", "ready")
        await self.pipeline.complete(self.item)
        await self.session.commit()
