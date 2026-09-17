"""Persiste latidos del scheduler por instancia y los interpreta como frescura o degradación sin
depender de una ejecución activa.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.time import utc_now
from app.db.models import ScraperWorkerHeartbeat


@dataclass(frozen=True, slots=True)
class WorkerHeartbeatStatus:
    """Resume disponibilidad y evidencia temporal de un rol para la respuesta de salud.

    Attributes:
        role: Identidad normalizada del worker.
        present, healthy, reason: Existencia del latido, disponibilidad y motivo missing,
            stale, persistent_failures u ok.
        age_seconds, last_success_age_seconds, last_error_age_seconds: Antigüedades en
            segundos, nunca negativas; None cuando no existe el evento.
        last_error_code, consecutive_failures: Último fallo clasificado y fallos desde el
            último éxito.
    """

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
        """Serializa el estado con alias camelCase para incorporarlo a salud sin repetir el rol
        utilizado como clave.

        Returns:
            campos públicos de disponibilidad y diagnóstico.
        """
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
    """Mantiene un registro por rol y reinicia la evidencia de fallos cuando cambia la instancia
    del proceso.

    See Also:
        app.healthcheck.worker_ready: Consume el mismo registro desde la sonda del contenedor.
    """

    def __init__(self, session: AsyncSession) -> None:
        """Conserva la sesión cedida para consultar o actualizar latidos.

        Args:
            session: Sesión asíncrona del llamador; este decide cuándo confirmar los cambios.
        """
        self.session = session

    async def pulse(
        self,
        role: str,
        instance_id: uuid.UUID,
        *,
        now: datetime | None = None,
    ) -> None:
        """Actualiza la fecha del latido sin borrar la secuencia de fallos del mismo proceso.

        Args:
            role: Rol persistido; actualmente solo se admite scheduler tras normalizarlo.
            instance_id: UUID del proceso que publica el latido.
            now: Instante UTC sin tzinfo; None toma el reloj actual cuando se admite.
        """
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
        """Actualiza latido y último éxito, limpia el código de error y pone a cero fallos
        consecutivos.

        Args:
            role: Rol persistido; actualmente solo se admite scheduler tras normalizarlo.
            instance_id: UUID del proceso que publica el latido.
            now: Instante UTC sin tzinfo; None toma el reloj actual cuando se admite.
        """
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
        """Actualiza latido y último fallo, guarda su código e incrementa los fallos
        consecutivos.

        Args:
            role: Rol persistido; actualmente solo se admite scheduler tras normalizarlo.
            instance_id: UUID del proceso que publica el latido.
            error_code: Código de fallo, recortado a 128 caracteres; vacío usa unknown_error.
            now: Instante UTC sin tzinfo; None toma el reloj actual cuando se admite.
        """
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
        """Prioriza ausencia y antigüedad excesiva sobre la degradación por fallos y calcula las
        edades desde el reloj indicado.

        Args:
            role: Rol persistido; actualmente solo se admite scheduler tras normalizarlo.
            max_age_seconds: Antigüedad máxima del latido en segundos.
            failure_threshold: Número de fallos consecutivos que indica degradación.
            now: Instante UTC sin tzinfo; None toma el reloj actual cuando se admite.

        Returns:
            instantánea de salud; no modifica el registro.
        """
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
        """Obtiene o crea el registro del rol y restablece inicio, éxito y errores al detectar
        una instancia distinta.

        Args:
            role: Rol persistido; actualmente solo se admite scheduler tras normalizarlo.
            instance_id: UUID del proceso que publica el latido.
            now: Instante UTC sin tzinfo; None toma el reloj actual cuando se admite.

        Returns:
            fila de latido de la sesión, pendiente de confirmación.
        """
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
    """Recorta espacios y normaliza el rol antes de comprobar que corresponde al scheduler.

    Args:
        value: Rol solicitado por el productor o la consulta de salud.

    Returns:
        scheduler.

    Raises:
        ValueError: invalid_scraper_worker_role si se solicita otro rol.
    """
    normalized = value.strip().lower().replace("_", "-")
    if normalized != "scheduler":
        raise ValueError("invalid_scraper_worker_role")
    return normalized


def _age(current: datetime, value: datetime | None) -> float | None:
    """Calcula antigüedad sin producir números negativos si el reloj se ha desplazado hacia
    atrás.

    Args:
        current: Instante UTC contra el que se calcula antigüedad.
        value: Instante UTC opcional del último evento.

    Returns:
        segundos transcurridos o None si falta el evento.
    """
    return None if value is None else max(0.0, (current - value).total_seconds())
