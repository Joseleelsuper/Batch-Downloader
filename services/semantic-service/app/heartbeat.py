"""Persiste y evalúa la salud de los procesos de trabajo semánticos."""

from __future__ import annotations

import logging
import threading
import uuid
from dataclasses import dataclass
from typing import Any

from app.database import Database

logger = logging.getLogger("semantic-worker-heartbeat")


@dataclass(frozen=True)
class WorkerHeartbeatStatus:
    """Describe el estado observable de un único rol de trabajo."""

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
        """Convierte el estado al contrato JSON utilizado por los healthchecks."""
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


class WorkerHeartbeatStore:
    """Centraliza las escrituras acotadas de heartbeat en PostgreSQL."""

    def __init__(self, database: Database) -> None:
        """Inicializa el store sobre el pool compartido del proceso."""
        self.database = database

    def pulse(self, role: str, instance_id: uuid.UUID) -> None:
        """Actualiza únicamente la señal de vida sin ocultar fallos anteriores."""
        normalized_role = _role(role)

        def persist(connection: Any) -> None:
            connection.execute(
                """
                INSERT INTO semantic_worker_heartbeats(
                    role, instance_id, started_at, heartbeat_at,
                    last_success_at, last_error_at, last_error_code,
                    consecutive_failures
                ) VALUES (%s, %s, now(), now(), now(), NULL, NULL, 0)
                ON CONFLICT (role) DO UPDATE SET
                    instance_id = EXCLUDED.instance_id,
                    started_at = CASE
                        WHEN semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN now() ELSE semantic_worker_heartbeats.started_at END,
                    heartbeat_at = now(),
                    last_success_at = CASE
                        WHEN semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN now() ELSE semantic_worker_heartbeats.last_success_at END,
                    last_error_at = CASE
                        WHEN semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN NULL ELSE semantic_worker_heartbeats.last_error_at END,
                    last_error_code = CASE
                        WHEN semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN NULL ELSE semantic_worker_heartbeats.last_error_code END,
                    consecutive_failures = CASE
                        WHEN semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN 0 ELSE semantic_worker_heartbeats.consecutive_failures END
                """,
                (normalized_role, instance_id),
            )

        self.database.run(persist)

    def success(self, role: str, instance_id: uuid.UUID) -> None:
        """Registra una iteración correcta y restablece fallos consecutivos."""
        normalized_role = _role(role)

        def persist(connection: Any) -> None:
            connection.execute(
                """
                INSERT INTO semantic_worker_heartbeats(
                    role, instance_id, started_at, heartbeat_at,
                    last_success_at, last_error_at, last_error_code,
                    consecutive_failures
                ) VALUES (%s, %s, now(), now(), now(), NULL, NULL, 0)
                ON CONFLICT (role) DO UPDATE SET
                    instance_id = EXCLUDED.instance_id,
                    started_at = CASE
                        WHEN semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN now() ELSE semantic_worker_heartbeats.started_at END,
                    heartbeat_at = now(),
                    last_success_at = now(),
                    last_error_at = CASE
                        WHEN semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN NULL ELSE semantic_worker_heartbeats.last_error_at END,
                    last_error_code = NULL,
                    consecutive_failures = 0
                """,
                (normalized_role, instance_id),
            )

        self.database.run(persist)

    def failure(
        self,
        role: str,
        instance_id: uuid.UUID,
        error_code: str,
    ) -> None:
        """Registra solo el tipo seguro del fallo y conserva el último éxito."""
        normalized_role = _role(role)
        normalized_error = (error_code or "unknown_error").strip()[:128]

        def persist(connection: Any) -> None:
            connection.execute(
                """
                INSERT INTO semantic_worker_heartbeats(
                    role, instance_id, started_at, heartbeat_at,
                    last_success_at, last_error_at, last_error_code,
                    consecutive_failures
                ) VALUES (%s, %s, now(), now(), NULL, now(), %s, 1)
                ON CONFLICT (role) DO UPDATE SET
                    instance_id = EXCLUDED.instance_id,
                    started_at = CASE
                        WHEN semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN now() ELSE semantic_worker_heartbeats.started_at END,
                    heartbeat_at = now(),
                    last_success_at = CASE
                        WHEN semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN NULL ELSE semantic_worker_heartbeats.last_success_at END,
                    last_error_at = now(),
                    last_error_code = EXCLUDED.last_error_code,
                    consecutive_failures = CASE
                        WHEN semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN 1 ELSE semantic_worker_heartbeats.consecutive_failures + 1 END
                """,
                (normalized_role, instance_id, normalized_error),
            )

        self.database.run(persist)

    def status(
        self,
        role: str,
        *,
        max_age_seconds: float,
        failure_threshold: int,
    ) -> WorkerHeartbeatStatus:
        """Evalúa antigüedad y fallos sin alterar la fila observada."""
        normalized_role = _role(role)

        def read(connection: Any) -> dict[str, Any] | None:
            return connection.execute(
                """
                SELECT
                    GREATEST(0, EXTRACT(EPOCH FROM (now() - heartbeat_at))) AS age_seconds,
                    CASE WHEN last_success_at IS NULL THEN NULL ELSE
                        GREATEST(0, EXTRACT(EPOCH FROM (now() - last_success_at))) END
                        AS last_success_age_seconds,
                    CASE WHEN last_error_at IS NULL THEN NULL ELSE
                        GREATEST(0, EXTRACT(EPOCH FROM (now() - last_error_at))) END
                        AS last_error_age_seconds,
                    last_error_code,
                    consecutive_failures
                FROM semantic_worker_heartbeats
                WHERE role = %s
                """,
                (normalized_role,),
            ).fetchone()

        row = self.database.run(read)
        if row is None:
            return WorkerHeartbeatStatus(
                normalized_role, False, False, "missing", None, None, None, None, 0
            )
        age_seconds = float(row["age_seconds"])
        failures = int(row["consecutive_failures"])
        if age_seconds > max_age_seconds:
            reason = "stale"
        elif failures >= failure_threshold:
            reason = "persistent_failures"
        else:
            reason = "ok"
        return WorkerHeartbeatStatus(
            role=normalized_role,
            present=True,
            healthy=reason == "ok",
            reason=reason,
            age_seconds=age_seconds,
            last_success_age_seconds=_optional_float(row["last_success_age_seconds"]),
            last_error_age_seconds=_optional_float(row["last_error_age_seconds"]),
            last_error_code=row["last_error_code"],
            consecutive_failures=failures,
        )


class WorkerHeartbeat:
    """Emite pulsos en segundo plano y registra resultados del supervisor."""

    def __init__(
        self,
        database: Database,
        role: str,
        *,
        interval_seconds: float,
    ) -> None:
        """Prepara una identidad efímera para la ejecución actual."""
        self.store = WorkerHeartbeatStore(database)
        self.role = _role(role)
        self.instance_id = uuid.uuid4()
        self.interval_seconds = max(1.0, interval_seconds)
        self.stopped = threading.Event()
        self.thread = threading.Thread(
            target=self._run,
            name=f"{self.role}-heartbeat",
            daemon=True,
        )

    def start(self) -> None:
        """Publica el primer éxito y arranca el emisor periódico."""
        self._safe(self.store.success)
        self.thread.start()

    def close(self) -> None:
        """Detiene el emisor sin bloquear indefinidamente el cierre."""
        self.stopped.set()
        if self.thread.is_alive():
            self.thread.join(timeout=min(2.0, self.interval_seconds))

    def success(self) -> None:
        """Marca una iteración supervisada como correcta."""
        self._safe(self.store.success)

    def failure(self, exception: BaseException) -> None:
        """Marca un fallo con su clase, nunca con texto potencialmente sensible."""
        self._safe(self.store.failure, exception.__class__.__name__)

    def _run(self) -> None:
        while not self.stopped.wait(self.interval_seconds):
            self._safe(self.store.pulse)

    def _safe(self, operation: Any, *arguments: object) -> None:
        try:
            operation(self.role, self.instance_id, *arguments)
        except Exception as exception:  # supervisor: la antigüedad hará visible el fallo
            logger.warning(
                "semantic_worker_heartbeat_failed role=%s error=%s",
                self.role,
                exception.__class__.__name__,
            )


def _role(value: str) -> str:
    normalized = value.strip().lower().replace("_", "-")
    if normalized not in {"indexer", "model-worker"}:
        raise ValueError("invalid_semantic_worker_role")
    return normalized


def _optional_float(value: Any) -> float | None:
    return None if value is None else float(value)
