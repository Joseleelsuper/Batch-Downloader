"""Consultas y registro de artefactos del almacén semántico administrativo."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from app.admin_rows import iso_value, model_from_row
from app.database import Database
from app.model_registry import local_model_identity


class SemanticModelStore:
    """Casos de uso de consulta y registro de modelos semánticos."""

    database: Database

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
        return [model_from_row(row) for row in rows]

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
                "createdAt": iso_value(row["created_at"]),
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
