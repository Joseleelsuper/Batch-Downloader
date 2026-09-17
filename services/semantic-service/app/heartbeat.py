"""Publica presencia y resultado del unico proceso de fondo semantico."""
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
    """Guarda y evalua el latido del indexador."""

    def __init__(self, database: Database) -> None:
        self.database = database

    def pulse(self, role: str, instance_id: uuid.UUID) -> None:
        """Actualiza presencia conservando el resultado de la iteracion."""
        self.database.run(
            lambda connection: connection.execute(
                """
                INSERT INTO semantic_worker_heartbeats(role, instance_id)
                VALUES (%s, %s)
                ON CONFLICT(role) DO UPDATE SET
                    instance_id = EXCLUDED.instance_id,
                    started_at = CASE WHEN
                        semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN now() ELSE semantic_worker_heartbeats.started_at END,
                    heartbeat_at = now(),
                    last_success_at = CASE WHEN
                        semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN now() ELSE semantic_worker_heartbeats.last_success_at END,
                    last_error_at = CASE WHEN
                        semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN NULL ELSE semantic_worker_heartbeats.last_error_at END,
                    last_error_code = CASE WHEN
                        semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN NULL ELSE semantic_worker_heartbeats.last_error_code END,
                    consecutive_failures = CASE WHEN
                        semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN 0 ELSE semantic_worker_heartbeats.consecutive_failures END
                """,
                (_role(role), instance_id),
            )
        )

    def success(self, role: str, instance_id: uuid.UUID) -> None:
        """Registra una iteracion correcta y reinicia los fallos."""
        self.database.run(
            lambda connection: connection.execute(
                """
                INSERT INTO semantic_worker_heartbeats(
                    role, instance_id, last_success_at
                ) VALUES (%s, %s, now())
                ON CONFLICT(role) DO UPDATE SET
                    instance_id = EXCLUDED.instance_id,
                    started_at = CASE WHEN
                        semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN now() ELSE semantic_worker_heartbeats.started_at END,
                    heartbeat_at = now(), last_success_at = now(),
                    last_error_at = NULL, last_error_code = NULL,
                    consecutive_failures = 0
                """,
                (_role(role), instance_id),
            )
        )

    def failure(self, role: str, instance_id: uuid.UUID, error_code: str) -> None:
        """Registra un fallo sin copiar detalles de la excepcion."""
        code = (error_code or "unknown_error").strip()[:128]
        self.database.run(
            lambda connection: connection.execute(
                """
                INSERT INTO semantic_worker_heartbeats(
                    role, instance_id, last_error_at, last_error_code, consecutive_failures
                ) VALUES (%s, %s, now(), %s, 1)
                ON CONFLICT(role) DO UPDATE SET
                    instance_id = EXCLUDED.instance_id,
                    started_at = CASE WHEN
                        semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN now() ELSE semantic_worker_heartbeats.started_at END,
                    heartbeat_at = now(), last_error_at = now(),
                    last_error_code = EXCLUDED.last_error_code,
                    last_success_at = CASE WHEN
                        semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN NULL ELSE semantic_worker_heartbeats.last_success_at END,
                    consecutive_failures = CASE WHEN
                        semantic_worker_heartbeats.instance_id <> EXCLUDED.instance_id
                        THEN 1 ELSE semantic_worker_heartbeats.consecutive_failures + 1 END
                """,
                (_role(role), instance_id, code),
            )
        )

    def status(
        self,
        role: str,
        *,
        max_age_seconds: float,
        failure_threshold: int,
    ) -> WorkerHeartbeatStatus:
        """Calcula presencia, antiguedad y fallos del rol."""
        normalized = _role(role)
        row = self.database.run(
            lambda connection: connection.execute(
                """
                SELECT GREATEST(0, EXTRACT(EPOCH FROM (now() - heartbeat_at))) AS age_seconds,
                       CASE WHEN last_success_at IS NULL THEN NULL ELSE
                           GREATEST(0, EXTRACT(EPOCH FROM (now() - last_success_at))) END
                           AS last_success_age_seconds,
                       CASE WHEN last_error_at IS NULL THEN NULL ELSE
                           GREATEST(0, EXTRACT(EPOCH FROM (now() - last_error_at))) END
                           AS last_error_age_seconds,
                       last_error_code, consecutive_failures
                FROM semantic_worker_heartbeats WHERE role = %s
                """,
                (normalized,),
            ).fetchone()
        )
        if row is None:
            return WorkerHeartbeatStatus(
                normalized, False, False, "missing", None, None, None, None, 0
            )
        age = float(row["age_seconds"])
        failures = int(row["consecutive_failures"])
        reason = "stale" if age > max_age_seconds else (
            "persistent_failures" if failures >= failure_threshold else "ok"
        )
        return WorkerHeartbeatStatus(
            normalized,
            True,
            reason == "ok",
            reason,
            age,
            _optional_float(row["last_success_age_seconds"]),
            _optional_float(row["last_error_age_seconds"]),
            row["last_error_code"],
            failures,
        )


class WorkerHeartbeat:
    """Publica presencia en segundo plano sin bloquear el trabajo indexador."""

    def __init__(self, database: Database, role: str, *, interval_seconds: float) -> None:
        self.store = WorkerHeartbeatStore(database)
        self.role = _role(role)
        self.instance_id = uuid.uuid4()
        self.interval_seconds = max(1.0, interval_seconds)
        self.stopped = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)

    def start(self) -> None:
        self._safe(self.store.success)
        self.thread.start()

    def close(self) -> None:
        self.stopped.set()
        if self.thread.is_alive():
            self.thread.join(timeout=min(2.0, self.interval_seconds))

    def success(self) -> None:
        self._safe(self.store.success)

    def failure(self, exception: BaseException) -> None:
        self._safe(self.store.failure, exception.__class__.__name__)

    def _run(self) -> None:
        while not self.stopped.wait(self.interval_seconds):
            self._safe(self.store.pulse)

    def _safe(self, operation: Any, *arguments: object) -> None:
        try:
            operation(self.role, self.instance_id, *arguments)
        except Exception as exception:
            logger.warning(
                "semantic_worker_heartbeat_failed role=%s error=%s",
                self.role,
                exception.__class__.__name__,
            )


def _role(value: str) -> str:
    normalized = value.strip().lower().replace("_", "-")
    if normalized != "indexer":
        raise ValueError("invalid_semantic_worker_role")
    return normalized


def _optional_float(value: Any) -> float | None:
    return None if value is None else float(value)
