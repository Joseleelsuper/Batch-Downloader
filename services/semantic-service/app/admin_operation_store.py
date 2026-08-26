"""Cola transaccional de operaciones semánticas administrativas."""

from __future__ import annotations

import json
import uuid
from typing import Any

from app.admin_rows import operation_from_row
from app.database import Database


class SemanticOperationStore:
    """Casos de uso de creación, leasing, reintento y cancelación de operaciones."""

    database: Database

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
        """Crea la operación `operation`.

        Args:
            kind (str): Valor de `kind` utilizado por la operación.
            actor (str): Valor de `actor` utilizado por la operación.
            idempotency_key (str | None): Valor de `idempotency_key` utilizado por la operación.
            request_payload (dict[str, Any]): Valor de `request_payload` utilizado por la operación.
            model_id (str | None): Identificador de `model` utilizado por la operación.
            model_version (str | None): Valor de `model_version` utilizado por la operación.
            repository (str | None): Valor de `repository` utilizado por la operación.
            resolved_revision (str | None): Valor de `resolved_revision` utilizado por la operación.
            progress_total (int): Valor de `progress_total` utilizado por la operación.
            progress_unit (str): Valor de `progress_unit` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.
        """
        payload_json = json.dumps(
            request_payload,
            ensure_ascii=False,
            sort_keys=True,
        )

        def mutate(connection):
            """Aplica la mutación definida para el escenario.

            Args:
                connection (Any): Conexión de base de datos utilizada por la operación.

            Throws:
                RuntimeError: Si el estado de ejecución impide completar la operación.
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
        """Ejecuta `operations` dentro de `SemanticAdminStore`.

        Args:
            limit (int): Número máximo de elementos que se recuperarán.
            active_only (bool): Valor de `active_only` utilizado por la operación.

        Returns:
            list[dict[str, Any]]: Colección de elementos obtenidos por la operación.
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
        """Ejecuta `operation` dentro de `SemanticAdminStore`.

        Args:
            operation_id (str): Identificador de `operation` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.

        Throws:
            LookupError: Si no existe el elemento solicitado.
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
        """Ejecuta `operation_request` dentro de `SemanticAdminStore`.

        Args:
            operation_id (str): Identificador de `operation` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.

        Throws:
            LookupError: Si no existe el elemento solicitado.
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
        """Reserva la operación `operation`.

        Args:
            owner (str): Valor de `owner` utilizado por la operación.
            lease_seconds (int): Valor de `lease_seconds` utilizado por la operación.

        Returns:
            dict[str, Any] | None: Mapa con los datos producidos por la operación.
        """

        def mutate(connection):
            """Aplica la mutación definida para el escenario.

            Args:
                connection (Any): Conexión de base de datos utilizada por la operación.
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
        """Ejecuta `renew_operation` dentro de `SemanticAdminStore`.

        Args:
            operation_id (str): Identificador de `operation` utilizado por la operación.
            owner (str): Valor de `owner` utilizado por la operación.
            lease_seconds (int): Valor de `lease_seconds` utilizado por la operación.
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
        """Actualiza la operación `operation`.

        Args:
            operation_id (str): Identificador de `operation` utilizado por la operación.
            phase (str): Valor de `phase` utilizado por la operación.
            current (int | None): Valor de `current` utilizado por la operación.
            total (int | None): Valor de `total` utilizado por la operación.
            unit (str | None): Valor de `unit` utilizado por la operación.
            message (str | None): Mensaje que debe procesarse.
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
        """Ejecuta `begin_finalization` dentro de `SemanticAdminStore`.

        Args:
            operation_id (str): Identificador de `operation` utilizado por la operación.
            owner (str): Valor de `owner` utilizado por la operación.
            phase (str): Valor de `phase` utilizado por la operación.
            message (str): Mensaje que debe procesarse.

        Returns:
            bool: Indica si se cumple la condición evaluada.
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
        """Ejecuta `begin_activation` dentro de `SemanticAdminStore`.

        Args:
            operation_id (str): Identificador de `operation` utilizado por la operación.
            owner (str): Valor de `owner` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
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
        """Ejecuta `complete_operation` dentro de `SemanticAdminStore`.

        Args:
            operation_id (str): Identificador de `operation` utilizado por la operación.
            result (dict[str, Any]): Resultado que debe procesarse.
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
        """Ejecuta `fail_operation` dentro de `SemanticAdminStore`.

        Args:
            operation_id (str): Identificador de `operation` utilizado por la operación.
            error_code (str): Valor de `error_code` utilizado por la operación.
            message (str): Mensaje que debe procesarse.
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
        """Ejecuta `request_cancel` dentro de `SemanticAdminStore`.

        Args:
            operation_id (str): Identificador de `operation` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.
        """

        def mutate(connection):
            """Aplica la mutación definida para el escenario.

            Args:
                connection (Any): Conexión de base de datos utilizada por la operación.

            Throws:
                RuntimeError: Si el estado de ejecución impide completar la operación.
                LookupError: Si no existe el elemento solicitado.
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
        """Reintenta la operación `operation`.

        Args:
            operation_id (str): Identificador de `operation` utilizado por la operación.
            actor (str): Valor de `actor` utilizado por la operación.
            idempotency_key (str | None): Valor de `idempotency_key` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.
        """

        def mutate(connection):
            """Aplica la mutación definida para el escenario.

            Args:
                connection (Any): Conexión de base de datos utilizada por la operación.

            Throws:
                RuntimeError: Si el estado de ejecución impide completar la operación.
                LookupError: Si no existe el elemento solicitado.
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
        """Ejecuta `cancel_requested` dentro de `SemanticAdminStore`.

        Args:
            operation_id (str): Identificador de `operation` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        row = self.database.run(
            lambda connection: connection.execute(
                "SELECT status FROM semantic_operations WHERE id = %s",
                (operation_id,),
            ).fetchone()
        )
        return bool(row and row["status"] == "cancel_requested")

    def mark_cancelled(self, operation_id: str) -> None:
        """Marca la operación `cancelled`.

        Args:
            operation_id (str): Identificador de `operation` utilizado por la operación.
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
