"""Implementa las responsabilidades del módulo `admin_store`.
"""
from __future__ import annotations

import json
import shutil
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from psycopg import sql

from app.database import Database
from app.model_registry import local_model_identity, model_index_name

ACTIVE_OPERATION_STATES = ("queued", "running", "cancel_requested")
"""Constante que define `ACTIVE_OPERATION_STATES`.
"""


class SemanticAdminStore:
    """Gestiona el almacenamiento de `SemanticAdmin`.
    """
    def __init__(self, database: Database) -> None:
        """Inicializa una instancia de `SemanticAdminStore`.

        Args:
            database (Database): Acceso a la base de datos utilizado por la operación.
        """
        self.database = database
        """Estado de instancia asociado a `database`.
        """

    def overview(
        self,
        model_cache_dir: str,
        *,
        model_max_bytes: int,
        model_min_free_bytes: int,
    ) -> dict[str, Any]:
        """Ejecuta `overview` dentro de `SemanticAdminStore`.

        Args:
            model_cache_dir (str): Valor de `model_cache_dir` utilizado por la operación.
            model_max_bytes (int): Valor de `model_max_bytes` utilizado por la operación.
            model_min_free_bytes (int): Valor de `model_min_free_bytes` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.
        """
        models = self.local_models()
        active = next((model for model in models if model["active"]), None)
        operation_count = self.database.run(
            lambda connection: int(
                connection.execute(
                    """
                    SELECT COUNT(*) AS count
                    FROM semantic_operations
                    WHERE status IN ('queued', 'running', 'cancel_requested')
                    """
                ).fetchone()["count"]
            )
        )
        model_bytes = sum(int(model["artifactBytes"]) for model in models)
        try:
            usage = shutil.disk_usage(model_cache_dir)
            disk = {
                "modelBytes": model_bytes,
                "freeBytes": usage.free,
                "totalBytes": usage.total,
                "reservedBytes": model_min_free_bytes,
                "maximumModelBytes": model_max_bytes,
            }
        except OSError:
            disk = {
                "modelBytes": model_bytes,
                "freeBytes": 0,
                "totalBytes": 0,
                "reservedBytes": model_min_free_bytes,
                "maximumModelBytes": model_max_bytes,
            }
        return {
            "service": "semantic-service",
            "searchReady": active is not None and active["index"]["complete"],
            "activeModel": active,
            "disk": disk,
            "activeOperations": operation_count,
        }

    def models(self) -> list[dict[str, Any]]:
        """Ejecuta `models` dentro de `SemanticAdminStore`.

        Returns:
            list[dict[str, Any]]: Colección de elementos obtenidos por la operación.
        """
        rows = self.database.run(
            lambda connection: connection.execute(
                """
                WITH catalog AS (
                    SELECT encode(
                        digest(
                            COALESCE(
                                string_agg(
                                    app_id::text || ':' || content_hash,
                                    '|' ORDER BY app_id
                                ) FILTER (WHERE active),
                                ''
                            ),
                            'sha256'
                        ),
                        'hex'
                    ) AS snapshot_hash
                    FROM semantic_documents
                )
                SELECT
                    a.*,
                    m.model_version,
                    m.model_key,
                    m.active,
                    m.lifecycle_state,
                    m.deployment_state,
                    m.created_at AS model_created_at,
                    m.activated_at,
                    s.index_version,
                    s.snapshot_hash,
                    s.expected_documents,
                    s.indexed_documents,
                    s.complete,
                    s.built_at,
                    b.id AS benchmark_id,
                    b.dataset_hash AS benchmark_dataset_hash,
                    b.scope AS benchmark_scope,
                    b.hardware_fingerprint,
                    b.configuration AS benchmark_configuration,
                    b.metrics AS benchmark_metrics,
                    b.created_at AS benchmark_created_at,
                    catalog.snapshot_hash AS current_catalog_snapshot_hash
                FROM semantic_model_artifacts a
                LEFT JOIN embedding_models m ON m.artifact_id = a.id
                LEFT JOIN semantic_index_state s ON s.model_version = m.model_version
                LEFT JOIN LATERAL (
                    SELECT br.*
                    FROM benchmark_runs br
                    WHERE br.model_ids @> ARRAY[a.id]::uuid[]
                    ORDER BY br.created_at DESC
                    LIMIT 1
                ) b ON TRUE
                CROSS JOIN catalog
                WHERE a.artifact_state <> 'deleted'
                ORDER BY COALESCE(m.active, FALSE) DESC, a.created_at DESC
                """
            ).fetchall()
        )
        return [self._model(row) for row in rows]

    def local_models(self) -> list[dict[str, Any]]:
        """Devuelve únicamente modelos cuyo artefacto existe en almacenamiento local."""
        return [
            model
            for model in self.models()
            if model.get("localPath") and Path(model["localPath"]).is_dir()
        ]

    def local_model(self, model_id: str) -> dict[str, Any]:
        """Obtiene un modelo visible solo cuando su artefacto local está disponible."""
        model = next(
            (row for row in self.local_models() if row["id"] == model_id),
            None,
        )
        if model is None:
            raise LookupError("semantic_model_not_found")
        return model

    def eligible_benchmark(self, model_id: str) -> dict[str, Any] | None:
        """Ejecuta `eligible_benchmark` dentro de `SemanticAdminStore`.

        Args:
            model_id (str): Identificador de `model` utilizado por la operación.

        Returns:
            dict[str, Any] | None: Mapa con los datos producidos por la operación.
        """
        row = self.database.run(
            lambda connection: connection.execute(
                """
                WITH catalog AS (
                    SELECT encode(
                        digest(
                            COALESCE(
                                string_agg(
                                    app_id::text || ':' || content_hash,
                                    '|' ORDER BY app_id
                                ) FILTER (WHERE active),
                                ''
                            ),
                            'sha256'
                        ),
                        'hex'
                    ) AS snapshot_hash
                    FROM semantic_documents
                )
                SELECT run.id::text AS id, run.dataset_hash
                FROM benchmark_runs run
                JOIN semantic_model_artifacts artifact
                  ON artifact.id = %s
                LEFT JOIN embedding_models active_model
                  ON active_model.active = TRUE
                CROSS JOIN catalog
                WHERE run.scope = 'full'
                  AND run.model_ids @> ARRAY[%s::uuid]
                  AND run.configuration ->> 'catalogSnapshotHash' = catalog.snapshot_hash
                  AND (
                      active_model.artifact_id IS NULL
                      OR run.model_ids @> ARRAY[active_model.artifact_id]
                  )
                  AND run.configuration -> 'modelConfigurations' -> %s
                      = jsonb_build_object(
                          'repository', artifact.hf_repository,
                          'revision', artifact.resolved_revision::text,
                          'queryPrefix', artifact.query_prefix,
                          'passagePrefix', artifact.passage_prefix,
                          'minimumSimilarity', artifact.minimum_similarity
                      )
                  AND EXISTS (
                      SELECT 1
                      FROM jsonb_array_elements(run.metrics) metric
                      WHERE metric ->> 'modelId' = %s
                        AND COALESCE((metric ->> 'eligible')::boolean, FALSE)
                  )
                  AND (
                      active_model.artifact_id IS NULL
                      OR EXISTS (
                          SELECT 1
                          FROM jsonb_array_elements(run.metrics) active_metric
                          WHERE active_metric ->> 'modelId'
                                = active_model.artifact_id::text
                            AND COALESCE(
                                (active_metric ->> 'eligible')::boolean,
                                FALSE
                            )
                      )
                  )
                ORDER BY run.created_at DESC
                LIMIT 1
                """,
                (model_id, model_id, model_id, model_id),
            ).fetchone()
        )
        return dict(row) if row else None

    def assert_model_deletable(
        self,
        model_id: str,
        *,
        excluding_operation_id: str | None = None,
    ) -> None:
        """Comprueba la operación `model_deletable`.

        Args:
            model_id (str): Identificador de `model` utilizado por la operación.
            excluding_operation_id (str | None): Identificador de `excluding_operation` utilizado
                por la operación.

        Throws:
            RuntimeError: Si el estado de ejecución impide completar la operación.
            LookupError: Si no existe el elemento solicitado.
        """
        row = self.database.run(
            lambda connection: connection.execute(
                """
                SELECT COALESCE(model.active, FALSE) AS active,
                       EXISTS (
                           SELECT 1
                           FROM semantic_operations operation
                           WHERE (
                                 operation.model_id = artifact.id
                                 OR (
                                     operation.operation_kind = 'benchmark'
                                     AND operation.request_payload -> 'modelIds'
                                         ? artifact.id::text
                                 )
                             )
                             AND operation.status IN (
                                 'queued', 'running', 'cancel_requested'
                             )
                             AND (%s::uuid IS NULL OR operation.id <> %s::uuid)
                       ) AS has_open_operations
                FROM semantic_model_artifacts artifact
                LEFT JOIN embedding_models model ON model.artifact_id = artifact.id
                WHERE artifact.id = %s AND artifact.artifact_state <> 'deleted'
                """,
                (excluding_operation_id, excluding_operation_id, model_id),
            ).fetchone()
        )
        if not row:
            raise LookupError("semantic_model_not_found")
        if row["active"]:
            raise RuntimeError("active_semantic_model_cannot_be_deleted")
        if row["has_open_operations"]:
            raise RuntimeError("semantic_model_has_open_operations")

    def model(self, model_id: str) -> dict[str, Any]:
        """Ejecuta `model` dentro de `SemanticAdminStore`.

        Args:
            model_id (str): Identificador de `model` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.

        Throws:
            LookupError: Si no existe el elemento solicitado.
        """
        model = next((row for row in self.models() if row["id"] == model_id), None)
        if model is None:
            raise LookupError("semantic_model_not_found")
        return model

    def artifact(self, model_id: str) -> dict[str, Any]:
        """Ejecuta `artifact` dentro de `SemanticAdminStore`.

        Args:
            model_id (str): Identificador de `model` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.

        Throws:
            LookupError: Si no existe el elemento solicitado.
        """
        row = self.database.run(
            lambda connection: connection.execute(
                """
                SELECT a.*, m.model_version, m.model_key, m.active,
                       m.deployment_state
                FROM semantic_model_artifacts a
                LEFT JOIN embedding_models m ON m.artifact_id = a.id
                WHERE a.id = %s AND a.artifact_state <> 'deleted'
                """,
                (model_id,),
            ).fetchone()
        )
        if not row:
            raise LookupError("semantic_model_not_found")
        return dict(row)

    def reconcile_artifact_path(
        self,
        model_id: str,
        *,
        local_path: str,
        artifact_bytes: int,
    ) -> None:
        """Ejecuta `reconcile_artifact_path` dentro de `SemanticAdminStore`.

        Args:
            model_id (str): Identificador de `model` utilizado por la operación.
            local_path (str): Ruta de `local` utilizada por la operación.
            artifact_bytes (int): Valor de `artifact_bytes` utilizado por la operación.
        """
        self.database.run(
            lambda connection: connection.execute(
                """
                UPDATE semantic_model_artifacts a
                SET local_path = %s,
                    artifact_bytes = GREATEST(artifact_bytes, %s),
                    downloaded_at = COALESCE(downloaded_at, now())
                WHERE a.id = %s
                """,
                (local_path, artifact_bytes, model_id),
            )
        )
        self.database.run(
            lambda connection: connection.execute(
                """
                UPDATE embedding_models
                SET artifact_path = %s
                WHERE artifact_id = %s
                  AND artifact_path IS NULL
                """,
                (local_path, model_id),
            )
        )

    def active_model_id(self) -> str | None:
        """Ejecuta `active_model_id` dentro de `SemanticAdminStore`.

        Returns:
            str | None: Resultado producido por la operación.
        """
        row = self.database.run(
            lambda connection: connection.execute(
                """
                SELECT artifact_id::text AS model_id
                FROM embedding_models
                WHERE active = TRUE
                LIMIT 1
                """
            ).fetchone()
        )
        return row["model_id"] if row and row["model_id"] else None

    def benchmarks(self, limit: int = 50) -> list[dict[str, Any]]:
        """Ejecuta `benchmarks` dentro de `SemanticAdminStore`.

        Args:
            limit (int): Número máximo de elementos que se recuperarán.

        Returns:
            list[dict[str, Any]]: Colección de elementos obtenidos por la operación.
        """
        rows = self.database.run(
            lambda connection: connection.execute(
                """
                SELECT id::text AS id, dataset_hash, seed, configuration, metrics,
                       selected_model_version, model_ids, scope,
                       hardware_fingerprint, document_count, query_count,
                       metrics_schema_version, created_at
                FROM benchmark_runs
                ORDER BY created_at DESC
                LIMIT %s
                """,
                (min(max(limit, 1), 200),),
            ).fetchall()
        )
        return [
            {
                "id": row["id"],
                "datasetHash": row["dataset_hash"],
                "seed": row["seed"],
                "configuration": row["configuration"],
                "metrics": row["metrics"],
                "selectedModelVersion": row["selected_model_version"],
                "modelIds": [str(value) for value in row["model_ids"]],
                "scope": row["scope"],
                "hardwareFingerprint": row["hardware_fingerprint"],
                "documentCount": row["document_count"],
                "queryCount": row["query_count"],
                "metricsSchemaVersion": row["metrics_schema_version"],
                "createdAt": _iso(row["created_at"]),
            }
            for row in rows
        ]

    def mark_artifact_state(
        self,
        model_id: str,
        state: str,
        *,
        message: str | None = None,
    ) -> None:
        """Marca la operación `artifact_state`.

        Args:
            model_id (str): Identificador de `model` utilizado por la operación.
            state (str): Valor de `state` utilizado por la operación.
            message (str | None): Mensaje que debe procesarse.
        """
        self.database.run(
            lambda connection: connection.execute(
                """
                UPDATE semantic_model_artifacts
                SET artifact_state = %s,
                    validation_message = %s,
                    validated_at = CASE WHEN %s = 'ready' THEN now() ELSE validated_at END
                WHERE id = %s
                """,
                (state, message, state, model_id),
            )
        )

    def register_validated_artifact(
        self,
        model_id: str,
        *,
        local_path: str,
        artifact_bytes: int,
        manifest_digest: str,
        dimensions: int,
        metadata: dict[str, Any],
    ) -> str:
        """Ejecuta `register_validated_artifact` dentro de `SemanticAdminStore`.

        Args:
            model_id (str): Identificador de `model` utilizado por la operación.
            local_path (str): Ruta de `local` utilizada por la operación.
            artifact_bytes (int): Valor de `artifact_bytes` utilizado por la operación.
            manifest_digest (str): Valor de `manifest_digest` utilizado por la operación.
            dimensions (int): Valor de `dimensions` utilizado por la operación.
            metadata (dict[str, Any]): Valor de `metadata` utilizado por la operación.

        Returns:
            str: Resultado producido por la operación.
        """
        def mutate(connection):
            """Aplica la mutación definida para el escenario.

            Args:
                connection (Any): Conexión de base de datos utilizada por la operación.

            Throws:
                LookupError: Si no existe el elemento solicitado.
            """
            artifact = connection.execute(
                """
                UPDATE semantic_model_artifacts
                SET local_path = %s,
                    artifact_state = 'ready',
                    artifact_bytes = %s,
                    manifest_digest = %s,
                    dimensions = %s,
                    metadata = metadata || %s::jsonb,
                    validation_message = NULL,
                    downloaded_at = now(),
                    validated_at = now()
                WHERE id = %s
                RETURNING *
                """,
                (
                    local_path,
                    artifact_bytes,
                    manifest_digest,
                    dimensions,
                    json.dumps(metadata, ensure_ascii=False),
                    model_id,
                ),
            ).fetchone()
            if not artifact:
                raise LookupError("semantic_model_not_found")
            model_key, model_version = local_model_identity(
                artifact["hf_repository"],
                artifact["resolved_revision"],
            )
            connection.execute(
                """
                INSERT INTO embedding_models (
                    model_version, model_key, hf_repository, hf_revision,
                    dimensions, query_prefix, passage_prefix, artifact_path,
                    lifecycle_state, active, artifact_id, minimum_similarity,
                    deployment_state
                ) VALUES (
                    %s, %s, %s, %s, %s, %s, %s, %s,
                    'registered', FALSE, %s, %s, 'not_prepared'
                )
                ON CONFLICT (model_version) DO UPDATE SET
                    artifact_id = EXCLUDED.artifact_id,
                    artifact_path = EXCLUDED.artifact_path,
                    dimensions = EXCLUDED.dimensions,
                    query_prefix = EXCLUDED.query_prefix,
                    passage_prefix = EXCLUDED.passage_prefix,
                    minimum_similarity = EXCLUDED.minimum_similarity
                """,
                (
                    model_version,
                    model_key,
                    artifact["hf_repository"],
                    artifact["resolved_revision"],
                    dimensions,
                    artifact["query_prefix"],
                    artifact["passage_prefix"],
                    local_path,
                    model_id,
                    artifact["minimum_similarity"],
                ),
            )
            return model_version

        return self.database.run(mutate)

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
                        str(existing["model_id"])
                        if existing["model_id"] is not None
                        else None
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
        clause = (
            "WHERE status IN ('queued', 'running', 'cancel_requested')"
            if active_only
            else ""
        )
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
        return [self._operation(row) for row in rows]

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
        return self._operation(row)

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
                source["phase"]
                in {"activating", "deleting", "finalizing", "publishing"}
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
                return self._operation(source)
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
            return self._operation(row)

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
            if source["operation_kind"] == "download":
                raise RuntimeError("semantic_remote_model_download_disabled")
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
                    retry_of = dict(existing["request_payload"] or {}).get(
                        "_retryOf"
                    )
                    if retry_of != operation_id:
                        raise RuntimeError("semantic_idempotency_key_conflict")
                    return self._operation(existing)
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
            return self._operation(row)

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

    def mark_preparing(self, model_id: str) -> str:
        """Marca la operación `preparing`.

        Args:
            model_id (str): Identificador de `model` utilizado por la operación.

        Returns:
            str: Resultado producido por la operación.
        """
        def mutate(connection):
            """Aplica la mutación definida para el escenario.

            Args:
                connection (Any): Conexión de base de datos utilizada por la operación.

            Throws:
                LookupError: Si no existe el elemento solicitado.
            """
            row = connection.execute(
                """
                UPDATE embedding_models
                SET deployment_state = 'preparing',
                    lifecycle_state = CASE
                        WHEN active THEN lifecycle_state ELSE 'selected' END
                WHERE artifact_id = %s
                RETURNING model_version
                """,
                (model_id,),
            ).fetchone()
            if not row:
                raise LookupError("semantic_model_not_registered")
            return row["model_version"]

        return self.database.run(mutate)

    def mark_ready(self, model_version: str) -> None:
        """Marca la operación `ready`.

        Args:
            model_version (str): Valor de `model_version` utilizado por la operación.
        """
        self.database.run(
            lambda connection: connection.execute(
                """
                UPDATE embedding_models
                SET deployment_state = CASE WHEN active THEN 'active' ELSE 'ready' END,
                    lifecycle_state = CASE WHEN active THEN 'active' ELSE 'registered' END
                WHERE model_version = %s
                """,
                (model_version,),
            )
        )

    def restore_deployment_state(self, model_id: str) -> None:
        """Ejecuta `restore_deployment_state` dentro de `SemanticAdminStore`.

        Args:
            model_id (str): Identificador de `model` utilizado por la operación.
        """
        self.database.run(
            lambda connection: connection.execute(
                """
                UPDATE embedding_models model
                SET deployment_state = CASE
                        WHEN model.active THEN 'active'
                        WHEN NOT EXISTS (
                            SELECT 1
                            FROM semantic_index_state state
                            WHERE state.model_version = model.model_version
                        ) THEN 'not_prepared'
                        WHEN COALESCE((
                            SELECT state.complete
                            FROM semantic_index_state state
                            WHERE state.model_version = model.model_version
                        ), FALSE) THEN 'ready'
                        ELSE 'stale'
                    END,
                    lifecycle_state = CASE
                        WHEN model.active THEN 'active'
                        ELSE 'registered'
                    END
                WHERE model.artifact_id = %s
                """,
                (model_id,),
            )
        )

    def mark_deployment_failed(self, model_id: str) -> None:
        """Marca la operación `deployment_failed`.

        Args:
            model_id (str): Identificador de `model` utilizado por la operación.
        """
        self.database.run(
            lambda connection: connection.execute(
                """
                UPDATE embedding_models
                SET deployment_state = CASE
                        WHEN active THEN 'active'
                        ELSE 'failed'
                    END,
                    lifecycle_state = CASE
                        WHEN active THEN 'active'
                        ELSE 'failed'
                    END
                WHERE artifact_id = %s
                """,
                (model_id,),
            )
        )

    def save_benchmark_run(
        self,
        *,
        run_id: str,
        operation_id: str,
        model_ids: list[str],
        dataset_hash: str,
        seed: int,
        configuration: dict[str, Any],
        metrics: list[dict[str, Any]],
        hardware_fingerprint: str,
        document_count: int,
        query_count: int,
        paths: dict[str, str],
    ) -> None:
        """Guarda la operación `benchmark_run`.

        Args:
            run_id (str): Identificador de `run` utilizado por la operación.
            operation_id (str): Identificador de `operation` utilizado por la operación.
            model_ids (list[str]): Colección de identificadores de `model`.
            dataset_hash (str): Valor de `dataset_hash` utilizado por la operación.
            seed (int): Valor de `seed` utilizado por la operación.
            configuration (dict[str, Any]): Valor de `configuration` utilizado por la operación.
            metrics (list[dict[str, Any]]): Valor de `metrics` utilizado por la operación.
            hardware_fingerprint (str): Valor de `hardware_fingerprint` utilizado por la operación.
            document_count (int): Valor de `document_count` utilizado por la operación.
            query_count (int): Valor de `query_count` utilizado por la operación.
            paths (dict[str, str]): Valor de `paths` utilizado por la operación.
        """
        result = {
            "runId": run_id,
            "datasetHash": dataset_hash,
            "modelIds": model_ids,
            "reports": paths,
        }

        def mutate(connection):
            """Aplica la mutación definida para el escenario.

            Args:
                connection (Any): Conexión de base de datos utilizada por la operación.

            Throws:
                InterruptedError: Si no puede completarse la operación bajo las condiciones
                    requeridas.
            """
            operation = connection.execute(
                """
                SELECT status
                FROM semantic_operations
                WHERE id = %s
                FOR UPDATE
                """,
                (operation_id,),
            ).fetchone()
            if not operation or operation["status"] != "running":
                raise InterruptedError("semantic_operation_cancelled")
            connection.execute(
                """
                INSERT INTO benchmark_runs (
                    id, dataset_hash, seed, configuration, metrics,
                    selected_model_version, report_json_path, report_csv_path,
                    report_markdown_path, operation_id, model_ids, scope,
                    hardware_fingerprint, document_count, query_count,
                    metrics_schema_version
                ) VALUES (
                    %s, %s, %s, %s::jsonb, %s::jsonb,
                    NULL, %s, %s, %s, %s, %s::uuid[], 'full',
                    %s, %s, %s, 2
                )
                """,
                (
                    str(uuid.UUID(run_id)),
                    dataset_hash,
                    seed,
                    json.dumps(configuration, sort_keys=True),
                    json.dumps(metrics, sort_keys=True),
                    paths["json"],
                    paths["csv"],
                    paths["markdown"],
                    operation_id,
                    model_ids,
                    hardware_fingerprint,
                    document_count,
                    query_count,
                ),
            )
            connection.execute(
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
                WHERE id = %s AND status = 'running'
                """,
                (json.dumps(result, ensure_ascii=False), operation_id),
            )

        self.database.run(mutate)

    def activate_model(
        self,
        model_id: str,
        *,
        operation_id: str,
        benchmark_run_id: str,
        expected_current_model_id: str | None,
        confirm_regression: bool,
    ) -> dict[str, Any]:
        """Ejecuta `activate_model` dentro de `SemanticAdminStore`.

        Args:
            model_id (str): Identificador de `model` utilizado por la operación.
            operation_id (str): Identificador de `operation` utilizado por la operación.
            benchmark_run_id (str): Identificador de `benchmark_run` utilizado por la operación.
            expected_current_model_id (str | None): Identificador de `expected_current_model`
                utilizado por la operación.
            confirm_regression (bool): Valor de `confirm_regression` utilizado por la operación.

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
            target = connection.execute(
                """
                SELECT m.*, a.id::text AS model_id
                FROM embedding_models m
                JOIN semantic_model_artifacts a ON a.id = m.artifact_id
                WHERE a.id = %s AND a.artifact_state = 'ready'
                FOR UPDATE
                """,
                (model_id,),
            ).fetchone()
            if not target:
                raise LookupError("semantic_model_not_ready")
            active = connection.execute(
                """
                SELECT artifact_id::text AS model_id, model_version
                FROM embedding_models
                WHERE active = TRUE
                FOR UPDATE
                """
            ).fetchone()
            current_id = active["model_id"] if active else None
            if expected_current_model_id != current_id:
                raise RuntimeError("semantic_activation_conflict")
            benchmark = connection.execute(
                """
                WITH catalog AS (
                    SELECT encode(
                        digest(
                            COALESCE(
                                string_agg(
                                    app_id::text || ':' || content_hash,
                                    '|' ORDER BY app_id
                                ) FILTER (WHERE active),
                                ''
                            ),
                            'sha256'
                        ),
                        'hex'
                    ) AS snapshot_hash
                    FROM semantic_documents
                )
                SELECT run.metrics, run.model_ids, run.configuration,
                       catalog.snapshot_hash
                FROM benchmark_runs run
                CROSS JOIN catalog
                WHERE run.id = %s
                  AND run.scope = 'full'
                  AND run.model_ids @> ARRAY[%s::uuid]
                """,
                (benchmark_run_id, model_id),
            ).fetchone()
            if not benchmark:
                raise RuntimeError("semantic_benchmark_required")
            benchmark_model_ids = {
                str(value) for value in (benchmark["model_ids"] or [])
            }
            if current_id and current_id not in benchmark_model_ids:
                raise RuntimeError("semantic_benchmark_not_comparable_to_active")
            configuration = benchmark["configuration"] or {}
            if (
                configuration.get("catalogSnapshotHash")
                != benchmark["snapshot_hash"]
            ):
                raise RuntimeError("semantic_benchmark_stale")
            evaluated_configuration = (
                configuration.get("modelConfigurations") or {}
            ).get(model_id)
            current_configuration = {
                "repository": target["hf_repository"],
                "revision": target["hf_revision"],
                "queryPrefix": target["query_prefix"],
                "passagePrefix": target["passage_prefix"],
                "minimumSimilarity": float(target["minimum_similarity"]),
            }
            if evaluated_configuration != current_configuration:
                raise RuntimeError("semantic_benchmark_configuration_stale")
            target_metric = _metric_for(benchmark["metrics"], model_id)
            active_metric = _metric_for(benchmark["metrics"], current_id)
            if (
                not target_metric
                or not target_metric.get("eligible")
                or (current_id and (
                    not active_metric or not active_metric.get("eligible")
                ))
            ):
                raise RuntimeError("semantic_benchmark_not_eligible")
            if (
                active_metric
                and float(target_metric.get("totalScore", 0))
                < float(active_metric.get("totalScore", 0))
                and not confirm_regression
            ):
                raise RuntimeError("benchmark_regression_confirmation_required")
            coverage = connection.execute(
                """
                SELECT COUNT(*) FILTER (WHERE d.active) AS expected,
                       COUNT(*) FILTER (
                           WHERE d.active AND e.content_hash = d.content_hash
                       ) AS indexed,
                       s.index_version, s.complete
                FROM semantic_documents d
                LEFT JOIN software_embeddings e
                  ON e.app_id = d.app_id AND e.model_version = %s
                LEFT JOIN semantic_index_state s
                  ON s.model_version = %s
                GROUP BY s.index_version, s.complete
                """,
                (target["model_version"], target["model_version"]),
            ).fetchone()
            expected = int(coverage["expected"] or 0) if coverage else 0
            indexed = int(coverage["indexed"] or 0) if coverage else 0
            if (
                not coverage
                or not coverage["complete"]
                or expected == 0
                or indexed != expected
            ):
                raise RuntimeError("semantic_model_coverage_incomplete")
            connection.execute(
                """
                UPDATE embedding_models
                SET active = FALSE,
                    lifecycle_state = CASE WHEN active THEN 'retired' ELSE lifecycle_state END,
                    deployment_state = CASE WHEN active THEN 'ready' ELSE deployment_state END
                WHERE active = TRUE AND model_version <> %s
                """,
                (target["model_version"],),
            )
            connection.execute(
                """
                UPDATE embedding_models
                SET active = TRUE, lifecycle_state = 'active',
                    deployment_state = 'active', activated_at = now()
                WHERE model_version = %s
                """,
                (target["model_version"],),
            )
            connection.execute(
                """
                UPDATE semantic_index_state
                SET activated_at = now()
                WHERE model_version = %s
                """,
                (target["model_version"],),
            )
            result = {
                "modelId": model_id,
                "modelVersion": target["model_version"],
                "indexVersion": coverage["index_version"],
                "expected": expected,
                "indexed": indexed,
            }
            connection.execute(
                """
                UPDATE semantic_operations
                SET status = 'succeeded', phase = 'completed',
                    result_payload = %s::jsonb,
                    lease_owner = NULL, lease_until = NULL,
                    updated_at = now(), finished_at = now()
                WHERE id = %s
                  AND status = 'running'
                  AND phase = 'activating'
                """,
                (json.dumps(result, ensure_ascii=False), operation_id),
            )
            return result

        return self.database.run(mutate)

    def begin_model_deletion(
        self,
        model_id: str,
        *,
        excluding_operation_id: str,
    ) -> dict[str, Any]:
        """Ejecuta `begin_model_deletion` dentro de `SemanticAdminStore`.

        Args:
            model_id (str): Identificador de `model` utilizado por la operación.
            excluding_operation_id (str): Identificador de `excluding_operation` utilizado por la
                operación.

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
            artifact = connection.execute(
                """
                SELECT id, local_path
                FROM semantic_model_artifacts
                WHERE id = %s AND artifact_state <> 'deleted'
                FOR UPDATE
                """,
                (model_id,),
            ).fetchone()
            if not artifact:
                raise LookupError("semantic_model_not_found")
            model = connection.execute(
                """
                SELECT model_version, active
                FROM embedding_models
                WHERE artifact_id = %s
                FOR UPDATE
                """,
                (model_id,),
            ).fetchone()
            if model and model["active"]:
                raise RuntimeError("active_semantic_model_cannot_be_deleted")
            has_open_operations = connection.execute(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM semantic_operations
                    WHERE (
                          model_id = %s
                          OR (
                              operation_kind = 'benchmark'
                              AND request_payload -> 'modelIds' ? %s
                          )
                      )
                      AND status IN ('queued', 'running', 'cancel_requested')
                      AND id <> %s
                ) AS value
                """,
                (model_id, model_id, excluding_operation_id),
            ).fetchone()["value"]
            if has_open_operations:
                raise RuntimeError("semantic_model_has_open_operations")
            if model and model["model_version"]:
                connection.execute(
                    sql.SQL("DROP INDEX IF EXISTS {}").format(
                        sql.Identifier(model_index_name(model["model_version"]))
                    )
                )
                connection.execute(
                    "DELETE FROM embedding_models WHERE artifact_id = %s",
                    (model_id,),
                )
            connection.execute(
                """
                UPDATE semantic_model_artifacts
                SET artifact_state = 'failed',
                    validation_message = 'semantic_model_delete_in_progress'
                WHERE id = %s
                """,
                (model_id,),
            )
            return {
                "modelId": model_id,
                "localPath": artifact["local_path"],
                "modelVersion": model["model_version"] if model else None,
            }

        return self.database.run(mutate)

    def finish_model_deletion(
        self,
        model_id: str,
        *,
        operation_id: str,
        model_version: str | None,
    ) -> dict[str, Any]:
        """Ejecuta `finish_model_deletion` dentro de `SemanticAdminStore`.

        Args:
            model_id (str): Identificador de `model` utilizado por la operación.
            operation_id (str): Identificador de `operation` utilizado por la operación.
            model_version (str | None): Valor de `model_version` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.
        """
        def mutate(connection):
            """Aplica la mutación definida para el escenario.

            Args:
                connection (Any): Conexión de base de datos utilizada por la operación.

            Throws:
                RuntimeError: Si el estado de ejecución impide completar la operación.
            """
            row = connection.execute(
                """
                UPDATE semantic_model_artifacts
                SET artifact_state = 'deleted', local_path = NULL,
                    validation_message = NULL, deleted_at = now()
                WHERE id = %s
                  AND artifact_state = 'failed'
                  AND validation_message = 'semantic_model_delete_in_progress'
                RETURNING id
                """,
                (model_id,),
            ).fetchone()
            if not row:
                raise RuntimeError("semantic_model_delete_state_conflict")
            result = {
                "modelId": model_id,
                "modelVersion": model_version,
            }
            connection.execute(
                """
                UPDATE semantic_operations
                SET status = 'succeeded', phase = 'completed',
                    result_payload = %s::jsonb,
                    lease_owner = NULL, lease_until = NULL,
                    updated_at = now(), finished_at = now()
                WHERE id = %s AND status = 'running'
                """,
                (json.dumps(result, ensure_ascii=False), operation_id),
            )
            return result

        return self.database.run(mutate)

    @staticmethod
    def _model(row: dict[str, Any]) -> dict[str, Any]:
        """Ejecuta el paso interno `_model`.

        Args:
            row (dict[str, Any]): Valor de `row` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.
        """
        metadata = row["metadata"] or {}
        metrics = row.get("benchmark_metrics") or []
        metric = _metric_for(metrics, str(row["id"]))
        return {
            "id": str(row["id"]),
            "displayName": row["display_name"],
            "repository": row["hf_repository"],
            "revision": row["resolved_revision"],
            "artifactState": row["artifact_state"],
            "deploymentState": row.get("deployment_state") or "not_prepared",
            "artifactBytes": int(row["artifact_bytes"] or 0),
            "dimensions": row["dimensions"],
            "queryPrefix": row["query_prefix"],
            "passagePrefix": row["passage_prefix"],
            "minimumSimilarity": float(row["minimum_similarity"]),
            "metadata": metadata,
            "validationMessage": row["validation_message"],
            "modelVersion": row.get("model_version"),
            "active": bool(row.get("active")),
            "createdAt": _iso(row["created_at"]),
            "downloadedAt": _iso(row["downloaded_at"]),
            "validatedAt": _iso(row["validated_at"]),
            "activatedAt": _iso(row.get("activated_at")),
            "index": {
                "indexVersion": row.get("index_version"),
                "snapshotHash": row.get("snapshot_hash"),
                "expected": int(row.get("expected_documents") or 0),
                "indexed": int(row.get("indexed_documents") or 0),
                "complete": bool(row.get("complete")),
                "builtAt": _iso(row.get("built_at")),
            },
            "lastBenchmark": (
                {
                    "id": str(row["benchmark_id"]),
                    "datasetHash": row["benchmark_dataset_hash"],
                    "scope": row["benchmark_scope"],
                    "hardwareFingerprint": row["hardware_fingerprint"],
                    "metric": metric,
                    "current": (
                        row["benchmark_scope"] == "full"
                        and (
                            row.get("benchmark_configuration") or {}
                        ).get("catalogSnapshotHash")
                        == row.get("current_catalog_snapshot_hash")
                    ),
                    "createdAt": _iso(row["benchmark_created_at"]),
                }
                if row.get("benchmark_id")
                else None
            ),
        }

    @staticmethod
    def _operation(row: dict[str, Any]) -> dict[str, Any]:
        """Ejecuta el paso interno `_operation`.

        Args:
            row (dict[str, Any]): Valor de `row` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.
        """
        request_payload = dict(row["request_payload"] or {})
        related_model_ids = [
            str(value)
            for value in (
                request_payload.get("modelIds")
                or ([row["model_id"]] if row["model_id"] else [])
            )
        ]
        return {
            "id": str(row["id"]),
            "kind": row["operation_kind"],
            "status": row["status"],
            "phase": row["phase"],
            "modelId": str(row["model_id"]) if row["model_id"] else None,
            "modelIds": related_model_ids,
            "modelVersion": row["model_version"],
            "repository": row["repository"],
            "revision": row["resolved_revision"],
            "progress": {
                "current": int(row["progress_current"]),
                "total": int(row["progress_total"]),
                "unit": row["progress_unit"],
            },
            "message": row["safe_message"],
            "errorCode": row["error_code"],
            "result": row["result_payload"] or {},
            "actor": row["actor"],
            "attempts": row["attempts"],
            "leaseOwner": row["lease_owner"],
            "leaseUntil": _iso(row["lease_until"]),
            "createdAt": _iso(row["created_at"]),
            "startedAt": _iso(row["started_at"]),
            "updatedAt": _iso(row["updated_at"]),
            "finishedAt": _iso(row["finished_at"]),
        }


def _metric_for(metrics: list[dict[str, Any]], model_id: str | None) -> dict[str, Any] | None:
    """Ejecuta el paso interno `_metric_for`.

    Args:
        metrics (list[dict[str, Any]]): Valor de `metrics` utilizado por la operación.
        model_id (str | None): Identificador de `model` utilizado por la operación.

    Returns:
        dict[str, Any] | None: Mapa con los datos producidos por la operación.
    """
    if not model_id:
        return None
    return next(
        (
            metric
            for metric in metrics
            if metric.get("modelId") == model_id
        ),
        None,
    )


def _iso(value: datetime | None) -> str | None:
    """Ejecuta el paso interno `_iso`.

    Args:
        value (datetime | None): Valor que debe procesarse.

    Returns:
        str | None: Resultado producido por la operación.
    """
    if value is None:
        return None
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.isoformat()


def directory_bytes(path: str | Path) -> int:
    """Ejecuta la operación `directory_bytes`.

    Args:
        path (str | Path): Ruta del recurso que debe procesarse.

    Returns:
        int: Resultado producido por la operación.
    """
    root = Path(path)
    return sum(
        entry.stat().st_size
        for entry in root.rglob("*")
        if entry.is_file()
    )
