"""Publica presencia y resultado de los workers en PostgreSQL para diferenciar parada, retraso y
fallos persistentes.
"""

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
    """Representa la evaluación de un rol con antigüedad y fallos consecutivos en el momento de
    consultar su latido.

    Attributes:
        role: Rol normalizado del trabajador.
        present: Existe una fila de latido para ese rol.
        healthy: El motivo de salud es ok.
        reason: missing, stale, persistent_failures u ok.
        age_seconds: Antigüedad del latido o None si no existe.
        last_success_age_seconds: Segundos desde el último éxito conocido.
        last_error_age_seconds: Segundos desde el último fallo conocido.
        last_error_code: Código seguro del último fallo o None.
        consecutive_failures: Racha de fallos no interrumpida por un éxito.
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
        """Proyecta el estado con nombres camelCase para las sondas HTTP, cuyo contenedor ya
        identifica el rol.

        Returns:
            campos de salud, edades y error; no repite role.
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


class WorkerHeartbeatStore:
    """Guarda un latido por rol y reinicia el historial cuando cambia el UUID de instancia.

    See Also:
        WorkerHeartbeat: Publicación periódica sin propagar fallos del monitor.
    """

    def __init__(self, database: Database) -> None:
        """Conserva el pool de escritura y consulta de latidos.

        Args:
            database: Acceso transaccional al pool; durante una operación exclusiva reutiliza
                la conexión reservada bajo el bloqueo del proceso.
        """
        self.database = database

    def pulse(self, role: str, instance_id: uuid.UUID) -> None:
        """Actualiza presencia sin borrar fallos de la misma instancia; una instancia nueva
        inicia un historial sano.

        Args:
            role: Rol del proceso, indexer o model-worker; admite guiones bajos antes de
                normalizar.
            instance_id: UUID de esta instancia; cambiarlo reinicia el historial de salud del
                rol.
        """
        normalized_role = _role(role)

        def persist(connection: Any) -> None:
            """Actualiza hora de latido y conserva el historial salvo que cambie el UUID del
            proceso.

            Args:
                connection: Conexión de la transacción del llamador, que confirma o revierte
                    todos sus cambios.
            """
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
        """Registra una iteración correcta, actualiza el último éxito y reinicia la racha de
        fallos.

        Args:
            role: Rol del proceso, indexer o model-worker; admite guiones bajos antes de
                normalizar.
            instance_id: UUID de esta instancia; cambiarlo reinicia el historial de salud del
                rol.
        """
        normalized_role = _role(role)

        def persist(connection: Any) -> None:
            """Publica éxito del proceso y vacía el código y contador de errores en una
            sentencia.

            Args:
                connection: Conexión de la transacción del llamador, que confirma o revierte
                    todos sus cambios.
            """
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
        """Registra el fallo seguro y aumenta la racha; una instancia nueva empieza con un fallo
        y sin éxito previo.

        Args:
            role: Rol del proceso, indexer o model-worker; admite guiones bajos antes de
                normalizar.
            instance_id: UUID de esta instancia; cambiarlo reinicia el historial de salud del
                rol.
            error_code: Nombre o código seguro del fallo, limitado a 128 caracteres.
        """
        normalized_role = _role(role)
        normalized_error = (error_code or "unknown_error").strip()[:128]

        def persist(connection: Any) -> None:
            """Actualiza presencia y hora de error, conservando el último éxito solo si pertenece
            a la misma instancia.

            Args:
                connection: Conexión de la transacción del llamador, que confirma o revierte
                    todos sus cambios.
            """
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
        """Evalúa primero presencia y antigüedad del latido y después el umbral de fallos
        consecutivos.

        Args:
            role: Rol del proceso, indexer o model-worker; admite guiones bajos antes de
                normalizar.
            max_age_seconds: Antigüedad máxima del latido; superarla marca stale.
            failure_threshold: Número de fallos consecutivos a partir del que se marca
                persistent_failures.

        Returns:
            estado inmutable con motivo de degradación y edades calculadas por PostgreSQL.
        """
        normalized_role = _role(role)

        def read(connection: Any) -> dict[str, Any] | None:
            """Calcula edades no negativas de latido, éxito y error usando el reloj de la misma
            consulta.

            Args:
                connection: Conexión de la transacción del llamador, que confirma o revierte
                    todos sus cambios.

            Returns:
                fila de salud o None si el rol todavía no ha publicado presencia.
            """
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
    """Publica presencia periódica y resultados explícitos de iteración sin hacer fallar el
    trabajo monitorizado.

    See Also:
        WorkerHeartbeatStore: Persistencia y cálculo de salud por rol.
    """

    def __init__(
        self,
        database: Database,
        role: str,
        *,
        interval_seconds: float,
    ) -> None:
        """Crea una identidad de instancia y un hilo detenido con intervalo mínimo de un segundo.

        Args:
            database: Acceso transaccional al pool; durante una operación exclusiva reutiliza
                la conexión reservada bajo el bloqueo del proceso.
            role: Rol del proceso, indexer o model-worker; admite guiones bajos antes de
                normalizar.
            interval_seconds: Intervalo entre latidos en segundos; se eleva a uno si es menor.
        """
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
        """Publica un éxito inicial y arranca el hilo de presencia periódica."""
        self._safe(self.store.success)
        self.thread.start()

    def close(self) -> None:
        """Señala parada y espera al hilo como máximo dos segundos o un intervalo si es menor."""
        self.stopped.set()
        if self.thread.is_alive():
            self.thread.join(timeout=min(2.0, self.interval_seconds))

    def success(self) -> None:
        """Publica el éxito de una iteración sin propagar errores de escritura del latido."""
        self._safe(self.store.success)

    def failure(self, exception: BaseException) -> None:
        """Publica como código seguro el nombre de la excepción que hizo fallar la iteración.

        Args:
            exception: Fallo capturado en una fase del trabajador.
        """
        self._safe(self.store.failure, exception.__class__.__name__)

    def _run(self) -> None:
        """Publica presencia en cada intervalo hasta recibir la señal de parada, conservando el
        resultado de las iteraciones.
        """
        while not self.stopped.wait(self.interval_seconds):
            self._safe(self.store.pulse)

    def _safe(self, operation: Any, *arguments: object) -> None:
        """Invoca una escritura de latido con rol e instancia y registra solo la clase de error
        si falla.

        Args:
            operation: Método del almacén que registra presencia, éxito o fallo.
            arguments: Argumentos adicionales de la escritura de latido, como el código de
                fallo.
        """
        try:
            operation(self.role, self.instance_id, *arguments)
        except Exception as exception:  # supervisor: la antigüedad hará visible el fallo
            logger.warning(
                "semantic_worker_heartbeat_failed role=%s error=%s",
                self.role,
                exception.__class__.__name__,
            )


def _role(value: str) -> str:
    """Normaliza espacios, mayúsculas y guiones bajos y rechaza roles fuera del conjunto
    soportado.

    Args:
        value: Nombre recibido del rol que debe corresponder a indexer o model-worker.

    Returns:
        rol canónico con guiones.

    Raises:
        ValueError: Si el nombre no identifica uno de los dos workers soportados.
    """
    normalized = value.strip().lower().replace("_", "-")
    if normalized not in {"indexer", "model-worker"}:
        raise ValueError("invalid_semantic_worker_role")
    return normalized


def _optional_float(value: Any) -> float | None:
    """Convierte una edad numérica opcional preservando la ausencia de un evento.

    Args:
        value: Valor numérico devuelto por PostgreSQL o None si no se registró la fecha.

    Returns:
        edad como float o None.
    """
    return None if value is None else float(value)
