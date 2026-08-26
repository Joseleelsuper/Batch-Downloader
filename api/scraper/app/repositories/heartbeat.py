"""Persistencia y evaluación del heartbeat del scheduler del scraper."""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.time import utc_now
from app.db.models import ScraperWorkerHeartbeat


@dataclass(frozen=True, slots=True)
class WorkerHeartbeatStatus:
    """Estado observable calculado sin modificar la fila del worker."""

    role: str
    present: bool
    healthy: bool
    reason: str
    age_seconds: float | None
    last_success_age_seconds: float | None
    last_error_age_seconds: float | None
    last_error_code: str | None
    consecutive_failures: int

    def as_dict(self) -> dict[str, object]:
        """Convierte la instantánea al contrato público del healthcheck."""
        return {
            "present": self.present,
            "healthy": self.healthy,
            "reason": self.reason,
            "ageSeconds": self.age_seconds,
            "lastSuccessAgeSeconds": self.last_success_age_seconds,
            "lastErrorAgeSeconds": self.last_error_age_seconds,
            "lastErrorCode": self.last_error_code,
            "consecutiveFailures": self.consecutive_failures,
        }


class WorkerHeartbeatRepository:
    """Actualiza una única fila y conserva únicamente códigos de error seguros."""

    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def pulse(
        self,
        role: str,
        instance_id: uuid.UUID,
        *,
        now: datetime | None = None,
    ) -> None:
        """Actualiza la señal de vida sin borrar fallos de la misma instancia."""
        current = now or utc_now()
        row = await self._row(role, instance_id, current)
        row.heartbeat_at = current

    async def success(
        self,
        role: str,
        instance_id: uuid.UUID,
        *,
        now: datetime | None = None,
    ) -> None:
        """Registra una iteración correcta y reinicia el contador de fallos."""
        current = now or utc_now()
        row = await self._row(role, instance_id, current)
        row.heartbeat_at = current
        row.last_success_at = current
        row.last_error_code = None
        row.consecutive_failures = 0

    async def failure(
        self,
        role: str,
        instance_id: uuid.UUID,
        error_code: str,
        *,
        now: datetime | None = None,
    ) -> None:
        """Incrementa fallos y conserva exclusivamente la clase del error."""
        current = now or utc_now()
        row = await self._row(role, instance_id, current)
        row.heartbeat_at = current
        row.last_error_at = current
        row.last_error_code = (error_code or "unknown_error").strip()[:128]
        row.consecutive_failures += 1

    async def status(
        self,
        role: str,
        *,
        max_age_seconds: float,
        failure_threshold: int,
        now: datetime | None = None,
    ) -> WorkerHeartbeatStatus:
        """Degrada por antigüedad o por un umbral explícito de fallos."""
        normalized_role = _role(role)
        row = await self.session.get(ScraperWorkerHeartbeat, normalized_role)
        if row is None:
            return WorkerHeartbeatStatus(
                normalized_role, False, False, "missing", None, None, None, None, 0
            )
        current = now or utc_now()
        age = max(0.0, (current - row.heartbeat_at).total_seconds())
        if age > max_age_seconds:
            reason = "stale"
        elif row.consecutive_failures >= failure_threshold:
            reason = "persistent_failures"
        else:
            reason = "ok"
        return WorkerHeartbeatStatus(
            role=normalized_role,
            present=True,
            healthy=reason == "ok",
            reason=reason,
            age_seconds=age,
            last_success_age_seconds=_age(current, row.last_success_at),
            last_error_age_seconds=_age(current, row.last_error_at),
            last_error_code=row.last_error_code,
            consecutive_failures=row.consecutive_failures,
        )

    async def _row(
        self,
        role: str,
        instance_id: uuid.UUID,
        now: datetime,
    ) -> ScraperWorkerHeartbeat:
        normalized_role = _role(role)
        row = await self.session.get(ScraperWorkerHeartbeat, normalized_role)
        if row is None:
            row = ScraperWorkerHeartbeat(
                role=normalized_role,
                instance_id=instance_id,
                started_at=now,
                heartbeat_at=now,
                last_success_at=now,
                consecutive_failures=0,
            )
            self.session.add(row)
            return row
        if row.instance_id != instance_id:
            row.instance_id = instance_id
            row.started_at = now
            row.last_success_at = now
            row.last_error_at = None
            row.last_error_code = None
            row.consecutive_failures = 0
        return row


def _role(value: str) -> str:
    normalized = value.strip().lower().replace("_", "-")
    if normalized != "scheduler":
        raise ValueError("invalid_scraper_worker_role")
    return normalized


def _age(current: datetime, value: datetime | None) -> float | None:
    return None if value is None else max(0.0, (current - value).total_seconds())
