"""Consulta artefactos, elegibilidad de benchmarks y configuración registrada para administración
de modelos.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from app.admin_rows import iso_value, model_from_row
from app.catalog_fingerprint import CATALOG_SNAPSHOT_CTE
from app.database import Database
from app.model_registry import local_model_identity


class SemanticModelStore:
    """Accede al catálogo de modelos y sus artefactos sin asumir responsabilidades de reserva o
    activación.

    See Also:
        app.admin_model_lifecycle.SemanticModelLifecycleStore: Transiciones atómicas de
            activación y borrado.
        app.admin_operation_store.SemanticOperationStore: Reservas y ejecución persistente de
            tareas.
    """

    def __init__(self, database: Database) -> None:
        """Conserva el pool usado por cada consulta y actualización de metadatos.

        Args:
            database: Pool que proporciona una transacción independiente para cada llamada
                run.
        """
        self.database = database

    def list_models(self, *, local_only: bool = False) -> list[dict[str, Any]]:
        """Proyecta artefactos no eliminados junto al índice y al último benchmark, poniendo
        primero el modelo activo.
        La disponibilidad local se comprueba antes de eliminar las rutas privadas de la
        respuesta.

        Args:
            local_only: Si es True, exige un directorio local existente antes de proyectar el
                modelo.

        Returns:
            modelos por actividad y fecha de creación descendentes, sin rutas del sistema de
                archivos.
        """
        rows = self.database.run(
            lambda connection: connection.execute(
                CATALOG_SNAPSHOT_CTE + """
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
        return [model_from_row(row) for row in rows if not local_only or (
            row["local_path"] and Path(row["local_path"]).is_dir()
        )]

    def local_models(self) -> list[dict[str, Any]]:
        """Filtra el catálogo por directorios de artefactos que existen en el sistema de archivos
        local.

        Returns:
            modelos locales en el orden del catálogo administrativo.
        """
        return self.list_models(local_only=True)

    def local_model(self, model_id: str) -> dict[str, Any]:
        """Resuelve un artefacto visible en el catálogo local, excluyendo registros sin
        directorio disponible.

        Args:
            model_id: UUID del artefacto persistido; no es la versión del modelo ni el nombre
                del repositorio.

        Returns:
            proyección pública del modelo local.

        Raises:
            LookupError: Si el artefacto no existe o su directorio local no está disponible.
        """
        model = next(
            (row for row in self.local_models() if row["id"] == model_id),
            None,
        )
        if model is None:
            raise LookupError("semantic_model_not_found")
        return model

    def eligible_benchmark(self, model_id: str) -> dict[str, Any] | None:
        """Busca el benchmark completo más reciente que evalúa el candidato y el modelo activo
        sobre el catálogo actual.
        Exige métricas elegibles y configuración idéntica del candidato; la activación vuelve
        a comprobarlo bajo bloqueo.

        Args:
            model_id: UUID del artefacto persistido; no es la versión del modelo ni el nombre
                del repositorio.

        Returns:
            UUID y hash del dataset de la ejecución compatible, o None si no existe.

        See Also:
            app.admin_model_lifecycle.require_activation_benchmark: Revalidación durante la
                transacción de activación.
        """
        row = self.database.run(
            lambda connection: connection.execute(
                CATALOG_SNAPSHOT_CTE + """
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
        """Comprueba que un artefacto no está activo ni participa en operaciones abiertas de
        preparación, benchmark o borrado.

        Args:
            model_id: UUID del artefacto persistido; no es la versión del modelo ni el nombre
                del repositorio.
            excluding_operation_id: UUID de la operación de borrado propia que no debe
                bloquearse a sí misma.

        Raises:
            LookupError: Si el artefacto no existe o ya está eliminado.
            RuntimeError: Si el modelo está activo o tiene operaciones abiertas distintas de
                la propia.
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
        """Localiza un artefacto no eliminado por su UUID, aunque sus archivos no estén
        disponibles localmente.

        Args:
            model_id: UUID del artefacto persistido; no es la versión del modelo ni el nombre
                del repositorio.

        Returns:
            proyección administrativa del modelo.

        Raises:
            LookupError: Si no aparece en el catálogo de artefactos no eliminados.
        """
        model = next((row for row in self.list_models() if row["id"] == model_id), None)
        if model is None:
            raise LookupError("semantic_model_not_found")
        return model

    def artifact(self, model_id: str) -> dict[str, Any]:
        """Recupera metadatos internos y ruta de un artefacto para que el trabajador pueda
        validarlo o eliminarlo.

        Args:
            model_id: UUID del artefacto persistido; no es la versión del modelo ni el nombre
                del repositorio.

        Returns:
            fila completa, incluida local_path; no se devuelve directamente al navegador.

        Raises:
            LookupError: Si el artefacto no existe o está eliminado.
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
        """Reconcilia la ubicación descubierta en disco sin reducir el tamaño registrado.
        Actualiza artefacto y ruta ausente del modelo en dos transacciones independientes; no
        reemplaza una ruta de modelo ya existente.

        Args:
            model_id: UUID del artefacto persistido; no es la versión del modelo ni el nombre
                del repositorio.
            local_path: Ruta del directorio de artefactos, ya inspeccionado por el trabajador
                de modelos.
            artifact_bytes: Tamaño comprobado de los archivos locales, en bytes.
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
        """Consulta la identidad del artefacto actualmente activo, sin comprobar cobertura ni
        disponibilidad del disco.

        Returns:
            UUID del artefacto activo o None si no lo hay.
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
        """Lista las ejecuciones de benchmark más recientes con sus métricas, configuración y
        tamaño del conjunto evaluado.

        Args:
            limit: Máximo de ejecuciones a consultar; se limita al intervalo de 1 a 200.

        Returns:
            hasta el límite normalizado de ejecuciones por fecha descendente.
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
        """Persiste el resultado de validación del artefacto y registra validated_at únicamente
        al pasar a ready.

        Args:
            model_id: UUID del artefacto persistido; no es la versión del modelo ni el nombre
                del repositorio.
            state: Estado de validación persistido; ready registra además la fecha de
                validación.
            message: Explicación segura del estado; None elimina el mensaje anterior.
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
        """Guarda el manifiesto validado y registra la versión de embeddings en una misma
        transacción.
        Al reconciliar una versión existente actualiza configuración y ubicación sin activar
        el modelo.

        Args:
            model_id: UUID del artefacto persistido; no es la versión del modelo ni el nombre
                del repositorio.
            local_path: Ruta del directorio de artefactos, ya inspeccionado por el trabajador
                de modelos.
            artifact_bytes: Tamaño comprobado de los archivos locales, en bytes.
            manifest_digest: SHA-256 del manifiesto validado de archivos del artefacto.
            dimensions: Número comprobado de componentes del vector producido por el modelo.
            metadata: Metadatos validados que se fusionan con los ya registrados para el
                artefacto.

        Returns:
            identidad estable formada por la clave normalizada del repositorio, revisión y
                sufijo :zero-shot.

        Raises:
            LookupError: Si el artefacto ha desaparecido antes de la actualización.
        """

        def mutate(connection):
            """Marca el artefacto ready y crea o reconcilia la versión correspondiente antes de
            confirmar los dos cambios.

            Args:
                connection: Conexión cedida por Database.run; el llamador confirma o revierte
                    todos sus cambios.

            Returns:
                versión de modelo asociada al artefacto validado.

            Raises:
                LookupError: Si el UUID de artefacto ya no existe.
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
