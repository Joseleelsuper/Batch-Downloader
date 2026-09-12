"""Persiste la cola administrativa con idempotencia, reservas temporales y transiciones de
cancelación y reintento.
"""

from __future__ import annotations

import json
import uuid
from typing import Any

from app.admin_rows import operation_from_row
from app.database import Database


class SemanticOperationStore:
    """Coordina trabajos administrativos entre API y trabajador sin compartir sesiones ni estado
    de ejecución en memoria.

    See Also:
        app.model_worker.SemanticModelWorker: Reserva y ejecuta los trabajos.
        app.admin_router: Traduce conflictos y estados al contrato HTTP.
    """

    def __init__(self, database: Database) -> None:
        """Conserva el pool utilizado para los cambios transaccionales de la cola.

        Args:
            database: Pool del servicio; run confirma o revierte la transacción de cada
                operación.
        """
        self.database = database

    def create_operation(
        self,
        *,
        kind: str,
        actor: str,
        idempotency_key: str | None,
        request_payload: dict[str, Any],
        model_id: str | None = None,
        model_version: str | None = None,
        repository: str | None = None,
        resolved_revision: str | None = None,
        progress_total: int = 0,
        progress_unit: str = "items",
    ) -> dict[str, Any]:
        """Recupera la operación equivalente o inserta una nueva conservando su solicitud y
        actor.
        Con clave de idempotencia, un bloqueo asesor serializa la comprobación y rechaza
        contenido incompatible; sin ella se busca trabajo equivalente abierto.

        Args:
            kind: Tipo de trabajo que selecciona la fase del trabajador: benchmark, prepare,
                activate o delete.
            actor: Identidad de la cuenta que solicita el trabajo, normalizada en la
                dependencia HTTP.
            idempotency_key: Clave opcional; reutilizarla con otro contenido produce un
                conflicto.
            request_payload: Parámetros JSON que se conservan para ejecutar y deduplicar el
                trabajo.
            model_id: UUID del artefacto sobre el que se actúa; None para operaciones de
                repositorio.
            model_version: Versión de embeddings asociada al artefacto, si ya está registrada.
            repository: Repositorio de origen si el trabajo todavía no tiene UUID de
                artefacto.
            resolved_revision: Revisión inmutable del repositorio de origen, si corresponde.
            progress_total: Cantidad prevista de unidades; cero significa que todavía no se
                conoce.
            progress_unit: Unidad del contador de progreso, por ejemplo models o documents.

        Returns:
            fila interna creada o recuperada; conserva el payload necesario para el
                trabajador.

        Raises:
            RuntimeError: Si una clave ya identifica otro tipo, payload, modelo, repositorio o
                revisión.
        """
        payload_json = json.dumps(
            request_payload,
            ensure_ascii=False,
            sort_keys=True,
        )

        def mutate(connection):
            """Comprueba idempotencia y trabajo equivalente antes de insertar el nuevo UUID
            dentro de la misma transacción.

            Args:
                connection: Conexión de la transacción actual, conservada hasta terminar el
                    cambio de estado.

            Returns:
                fila existente compatible o recién insertada.

            Raises:
                RuntimeError: Si la clave de idempotencia se reutiliza para una solicitud
                    distinta.
            """
            if idempotency_key:
                connection.execute(
                    "SELECT pg_advisory_xact_lock(hashtext(%s)::bigint)",
                    (idempotency_key,),
                )
                existing = connection.execute(
                    "SELECT * FROM semantic_operations WHERE idempotency_key = %s",
                    (idempotency_key,),
                ).fetchone()
                if existing:
                    existing_model_id = (
                        str(existing["model_id"]) if existing["model_id"] is not None else None
                    )
                    if (
                        existing["operation_kind"] != kind
                        or dict(existing["request_payload"] or {}) != request_payload
                        or existing_model_id != model_id
                        or existing["repository"] != repository
                        or existing["resolved_revision"] != resolved_revision
                    ):
                        raise RuntimeError("semantic_idempotency_key_conflict")
                    return dict(existing)
            existing = connection.execute(
                """
                SELECT *
                FROM semantic_operations
                WHERE operation_kind = %s
                  AND status IN ('queued', 'running', 'cancel_requested')
                  AND request_payload = %s::jsonb
                  AND (
                    (%s::uuid IS NOT NULL AND model_id = %s::uuid)
                    OR (%s::uuid IS NULL AND repository = %s)
                  )
                ORDER BY created_at DESC
                LIMIT 1
                """,
                (
                    kind,
                    payload_json,
                    model_id,
                    model_id,
                    model_id,
                    repository,
                ),
            ).fetchone()
            if existing:
                return dict(existing)
            operation_id = str(uuid.uuid4())
            row = connection.execute(
                """
                INSERT INTO semantic_operations (
                    id, operation_kind, model_id, model_version, repository,
                    resolved_revision, progress_total, progress_unit,
                    request_payload, actor, idempotency_key
                ) VALUES (
                    %s, %s, %s, %s, %s, %s, %s, %s, %s::jsonb, %s, %s
                )
                RETURNING *
                """,
                (
                    operation_id,
                    kind,
                    model_id,
                    model_version,
                    repository,
                    resolved_revision,
                    progress_total,
                    progress_unit,
                    payload_json,
                    actor,
                    idempotency_key,
                ),
            ).fetchone()
            return dict(row)

        return self.database.run(mutate)

    def operations(
        self,
        *,
        limit: int = 100,
        active_only: bool = False,
    ) -> list[dict[str, Any]]:
        """Consulta el historial administrativo más reciente y permite limitarlo a trabajos que
        aún ocupan capacidad.

        Args:
            limit: Máximo de operaciones; el almacén lo acota entre 1 y 250.
            active_only: Si es True, incluye solo queued, running y cancel_requested.

        Returns:
            proyecciones de operación por fecha descendente, hasta el límite normalizado.
        """
        clause = "WHERE status IN ('queued', 'running', 'cancel_requested')" if active_only else ""
        rows = self.database.run(
            lambda connection: connection.execute(
                f"""
                SELECT *
                FROM semantic_operations
                {clause}
                ORDER BY created_at DESC
                LIMIT %s
                """,
                (min(max(limit, 1), 250),),
            ).fetchall()
        )
        return [operation_from_row(row) for row in rows]

    def operation(self, operation_id: str) -> dict[str, Any]:
        """Consulta estado, progreso y resultado público de una operación concreta.

        Args:
            operation_id: UUID de la operación administrativa persistida.

        Returns:
            proyección administrativa, sin copiar el payload interno.

        Raises:
            LookupError: Si el UUID de operación no existe.
        """
        row = self.database.run(
            lambda connection: connection.execute(
                "SELECT * FROM semantic_operations WHERE id = %s",
                (operation_id,),
            ).fetchone()
        )
        if not row:
            raise LookupError("semantic_operation_not_found")
        return operation_from_row(row)

    def operation_request(self, operation_id: str) -> dict[str, Any]:
        """Recupera los parámetros originales que el trabajador necesita para ejecutar una
        operación.

        Args:
            operation_id: UUID de la operación administrativa persistida.

        Returns:
            payload de entrada como diccionario, vacío si no había contenido.

        Raises:
            LookupError: Si el UUID de operación no existe.
        """
        row = self.database.run(
            lambda connection: connection.execute(
                """
                SELECT request_payload
                FROM semantic_operations
                WHERE id = %s
                """,
                (operation_id,),
            ).fetchone()
        )
        if not row:
            raise LookupError("semantic_operation_not_found")
        return dict(row["request_payload"] or {})

    def claim_operation(self, owner: str, lease_seconds: int) -> dict[str, Any] | None:
        """Reserva el trabajo pendiente más antiguo o recupera uno running con reserva vencida
        usando SKIP LOCKED.
        Antes termina cancelaciones con reserva vencida; la nueva reserva incrementa intentos
        y conserva la fase recuperada.

        Args:
            owner: Identidad exclusiva del trabajador que adquiere o renueva la reserva.
            lease_seconds: Duración de la reserva a partir de now() de PostgreSQL, en
                segundos.

        Returns:
            fila reservada para este trabajador o None si no hay trabajo elegible.
        """

        def mutate(connection):
            """Finaliza cancelaciones vencidas y adquiere como máximo una operación sin esperar
            filas bloqueadas por otros workers.

            Args:
                connection: Conexión de la transacción actual, conservada hasta terminar el
                    cambio de estado.

            Returns:
                fila reservada o None cuando la cola no ofrece trabajo.
            """
            connection.execute(
                """
                UPDATE semantic_operations
                SET status = 'cancelled', phase = 'cancelled',
                    lease_owner = NULL, lease_until = NULL,
                    updated_at = now(), finished_at = now()
                WHERE status = 'cancel_requested'
                  AND lease_until < now()
                """
            )
            row = connection.execute(
                """
                WITH claimed AS (
                    SELECT id
                    FROM semantic_operations
                    WHERE status = 'queued'
                       OR (status = 'running' AND lease_until < now())
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE semantic_operations operation
                SET status = 'running',
                    phase = CASE WHEN operation.status = 'queued' THEN 'starting' ELSE phase END,
                    attempts = attempts + 1,
                    lease_owner = %s,
                    lease_until = now() + (%s * interval '1 second'),
                    started_at = COALESCE(started_at, now()),
                    updated_at = now()
                FROM claimed
                WHERE operation.id = claimed.id
                RETURNING operation.*
                """,
                (owner, lease_seconds),
            ).fetchone()
            return dict(row) if row else None

        return self.database.run(mutate)

    def renew_operation(self, operation_id: str, owner: str, lease_seconds: int) -> None:
        """Prolonga la reserva solo si la operación sigue running y pertenece al mismo
        trabajador.

        Args:
            operation_id: UUID de la operación administrativa persistida.
            owner: Identidad exclusiva del trabajador que adquiere o renueva la reserva.
            lease_seconds: Duración de la reserva a partir de now() de PostgreSQL, en
                segundos.
        """
        self.database.run(
            lambda connection: connection.execute(
                """
                UPDATE semantic_operations
                SET lease_until = now() + (%s * interval '1 second'),
                    updated_at = now()
                WHERE id = %s AND lease_owner = %s AND status = 'running'
                """,
                (lease_seconds, operation_id, owner),
            )
        )

    def update_operation(
        self,
        operation_id: str,
        *,
        phase: str,
        current: int | None = None,
        total: int | None = None,
        unit: str | None = None,
        message: str | None = None,
    ) -> None:
        """Actualiza fase, mensaje y contadores indicados; None conserva contadores y unidad,
        pero permite vaciar el mensaje.

        Args:
            operation_id: UUID de la operación administrativa persistida.
            phase: Fase funcional que se guarda para recuperación y presentación del progreso.
            current: Unidades completadas; None conserva el contador anterior.
            total: Unidades previstas; None conserva el total anterior.
            unit: Unidad de progreso; None conserva la anterior.
            message: Explicación segura para administración, sin rutas ni contenidos
                sensibles.
        """
        self.database.run(
            lambda connection: connection.execute(
                """
                UPDATE semantic_operations
                SET phase = %s,
                    progress_current = COALESCE(%s, progress_current),
                    progress_total = COALESCE(%s, progress_total),
                    progress_unit = COALESCE(%s, progress_unit),
                    safe_message = %s,
                    updated_at = now()
                WHERE id = %s
                """,
                (phase, current, total, unit, message, operation_id),
            )
        )

    def begin_finalization(
        self,
        operation_id: str,
        *,
        owner: str,
        phase: str,
        message: str,
    ) -> bool:
        """Entra en una fase final indivisible solo mientras el trabajo siga running y conserve
        su propietario.

        Args:
            operation_id: UUID de la operación administrativa persistida.
            owner: Identidad exclusiva del trabajador que adquiere o renueva la reserva.
            phase: Fase funcional que se guarda para recuperación y presentación del progreso.
            message: Explicación segura para administración, sin rutas ni contenidos
                sensibles.

        Returns:
            True si se actualizó la fila; False si se canceló o cambió la reserva.
        """
        row = self.database.run(
            lambda connection: connection.execute(
                """
                UPDATE semantic_operations
                SET phase = %s,
                    safe_message = %s,
                    updated_at = now()
                WHERE id = %s
                  AND status = 'running'
                  AND lease_owner = %s
                RETURNING id
                """,
                (phase, message, operation_id, owner),
            ).fetchone()
        )
        return row is not None

    def begin_activation(
        self,
        operation_id: str,
        *,
        owner: str,
    ) -> bool:
        """Marca la fase activating antes del intercambio atómico para impedir una cancelación
        intermedia.

        Args:
            operation_id: UUID de la operación administrativa persistida.
            owner: Identidad exclusiva del trabajador que adquiere o renueva la reserva.

        Returns:
            True si el trabajador todavía conserva una operación ejecutable.
        """
        return self.begin_finalization(
            operation_id,
            owner=owner,
            phase="activating",
            message="Cambiando el modelo activo de forma atómica",
        )

    def complete_operation(
        self,
        operation_id: str,
        result: dict[str, Any],
    ) -> None:
        """Marca succeeded, completa el contador si se conoce su total y libera la reserva
        guardando el resultado JSON.

        Args:
            operation_id: UUID de la operación administrativa persistida.
            result: Resultado JSON que se devuelve a quien consulta la operación terminada.
        """
        self.database.run(
            lambda connection: connection.execute(
                """
                UPDATE semantic_operations
                SET status = 'succeeded', phase = 'completed',
                    progress_current = CASE
                        WHEN progress_total > 0 THEN progress_total
                        ELSE progress_current
                    END,
                    result_payload = %s::jsonb,
                    lease_owner = NULL, lease_until = NULL,
                    updated_at = now(), finished_at = now()
                WHERE id = %s
                """,
                (json.dumps(result, ensure_ascii=False), operation_id),
            )
        )

    def fail_operation(
        self,
        operation_id: str,
        error_code: str,
        message: str,
    ) -> None:
        """Registra failed con código y mensaje acotados a 120 y 500 caracteres, fecha final y
        reserva liberada.

        Args:
            operation_id: UUID de la operación administrativa persistida.
            error_code: Código estable del fallo; se recorta a 120 caracteres al persistirlo.
            message: Explicación segura para administración, sin rutas ni contenidos
                sensibles.
        """
        self.database.run(
            lambda connection: connection.execute(
                """
                UPDATE semantic_operations
                SET status = 'failed', phase = 'failed',
                    error_code = %s, safe_message = %s,
                    lease_owner = NULL, lease_until = NULL,
                    updated_at = now(), finished_at = now()
                WHERE id = %s
                """,
                (error_code[:120], message[:500], operation_id),
            )
        )

    def request_cancel(self, operation_id: str) -> dict[str, Any]:
        """Cancela inmediatamente trabajos queued o solicita parada cooperativa de los running.
        Los estados terminales se devuelven sin alterarlos y las fases finales protegidas
        rechazan la cancelación.

        Args:
            operation_id: UUID de la operación administrativa persistida.

        Returns:
            estado público después de aplicar o recuperar la solicitud.

        Raises:
            LookupError: Si la operación no existe.
            RuntimeError: Si está activando, eliminando, finalizando o publicando un resultado
                indivisible.
        """

        def mutate(connection):
            """Bloquea el estado de la operación antes de decidir entre cancelación inmediata,
            cooperativa o estado ya terminal.

            Args:
                connection: Conexión de la transacción actual, conservada hasta terminar el
                    cambio de estado.

            Returns:
                proyección de la operación tras la decisión.

            Raises:
                LookupError: Si el UUID no existe.
                RuntimeError: Si está dentro de una fase final no cancelable.
            """
            source = connection.execute(
                """
                SELECT *
                FROM semantic_operations
                WHERE id = %s
                FOR UPDATE
                """,
                (operation_id,),
            ).fetchone()
            if not source:
                raise LookupError("semantic_operation_not_found")
            if (
                source["phase"] in {"activating", "deleting", "finalizing", "publishing"}
                and source["status"] == "running"
            ):
                raise RuntimeError("semantic_operation_not_cancellable")
            if source["status"] == "queued":
                status = "cancelled"
                phase = "cancelled"
                finished = True
            elif source["status"] == "running":
                status = "cancel_requested"
                phase = source["phase"]
                finished = False
            else:
                return operation_from_row(source)
            row = connection.execute(
                """
                UPDATE semantic_operations
                SET status = %s, phase = %s,
                    finished_at = CASE WHEN %s THEN now() ELSE finished_at END,
                    updated_at = now()
                WHERE id = %s
                RETURNING *
                """,
                (status, phase, finished, operation_id),
            ).fetchone()
            return operation_from_row(row)

        return self.database.run(mutate)

    def retry_operation(
        self,
        operation_id: str,
        *,
        actor: str,
        idempotency_key: str | None,
    ) -> dict[str, Any]:
        """Crea un nuevo intento a partir de una operación failed o cancelled, conservando la
        solicitud original y el enlace _retryOf.
        La clave opcional permite recuperar el mismo reintento sin duplicarlo; la operación
        anterior conserva su historial.

        Args:
            operation_id: UUID de la operación administrativa persistida.
            actor: Identidad de la cuenta que solicita el trabajo, normalizada en la
                dependencia HTTP.
            idempotency_key: Clave opcional; reutilizarla con otro contenido produce un
                conflicto.

        Returns:
            proyección del nuevo intento o del reintento ya creado con esa clave.

        Raises:
            LookupError: Si el intento original no existe.
            RuntimeError: Si aún no es reintentable o la clave pertenece a otro intento
                original.
        """

        def mutate(connection):
            """Bloquea el intento original, comprueba el estado y deduplica o inserta el
            reintento con su nuevo actor.

            Args:
                connection: Conexión de la transacción actual, conservada hasta terminar el
                    cambio de estado.

            Returns:
                proyección del reintento.

            Raises:
                LookupError: Si la operación original no existe.
                RuntimeError: Si el estado o la clave de idempotencia impiden reintentar.
            """
            source = connection.execute(
                """
                SELECT *
                FROM semantic_operations
                WHERE id = %s
                FOR UPDATE
                """,
                (operation_id,),
            ).fetchone()
            if not source:
                raise LookupError("semantic_operation_not_found")
            if source["status"] not in {"failed", "cancelled"}:
                raise RuntimeError("semantic_operation_not_retryable")
            if idempotency_key:
                connection.execute(
                    "SELECT pg_advisory_xact_lock(hashtext(%s)::bigint)",
                    (idempotency_key,),
                )
                existing = connection.execute(
                    """
                    SELECT *
                    FROM semantic_operations
                    WHERE idempotency_key = %s
                    """,
                    (idempotency_key,),
                ).fetchone()
                if existing:
                    retry_of = dict(existing["request_payload"] or {}).get("_retryOf")
                    if retry_of != operation_id:
                        raise RuntimeError("semantic_idempotency_key_conflict")
                    return operation_from_row(existing)
            retry_id = str(uuid.uuid4())
            row = connection.execute(
                """
                INSERT INTO semantic_operations (
                    id, operation_kind, model_id, model_version, repository,
                    resolved_revision, progress_total, progress_unit,
                    request_payload, actor, idempotency_key, result_payload
                ) VALUES (
                    %s, %s, %s, %s, %s, %s, %s, %s,
                    %s::jsonb, %s, %s, '{}'::jsonb
                )
                RETURNING *
                """,
                (
                    retry_id,
                    source["operation_kind"],
                    source["model_id"],
                    source["model_version"],
                    source["repository"],
                    source["resolved_revision"],
                    source["progress_total"],
                    source["progress_unit"],
                    json.dumps(
                        {
                            **dict(source["request_payload"] or {}),
                            "_retryOf": operation_id,
                        },
                        sort_keys=True,
                    ),
                    actor,
                    idempotency_key,
                ),
            ).fetchone()
            return operation_from_row(row)

        return self.database.run(mutate)

    def cancel_requested(self, operation_id: str) -> bool:
        """Consulta la señal persistente que permite detener trabajo cooperativo entre fases.

        Args:
            operation_id: UUID de la operación administrativa persistida.

        Returns:
            True solo para cancel_requested; una operación ausente produce False.
        """
        row = self.database.run(
            lambda connection: connection.execute(
                "SELECT status FROM semantic_operations WHERE id = %s",
                (operation_id,),
            ).fetchone()
        )
        return bool(row and row["status"] == "cancel_requested")

    def mark_cancelled(self, operation_id: str) -> None:
        """Confirma la parada cooperativa, registra fecha final y libera el propietario y
        vencimiento de la reserva.

        Args:
            operation_id: UUID de la operación administrativa persistida.
        """
        self.database.run(
            lambda connection: connection.execute(
                """
                UPDATE semantic_operations
                SET status = 'cancelled', phase = 'cancelled',
                    lease_owner = NULL, lease_until = NULL,
                    updated_at = now(), finished_at = now()
                WHERE id = %s
                """,
                (operation_id,),
            )
        )
