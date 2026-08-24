"""Preparación, activación, benchmark y borrado de modelos semánticos."""

from __future__ import annotations

import json
import uuid
from typing import Any

from psycopg import sql

from app.admin_model_store import SemanticModelStore
from app.admin_rows import metric_for
from app.model_registry import model_index_name


class SemanticModelLifecycleStore(SemanticModelStore):
    """Casos de uso mutables del ciclo de vida de un modelo semántico."""

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
            benchmark_model_ids = {str(value) for value in (benchmark["model_ids"] or [])}
            if current_id and current_id not in benchmark_model_ids:
                raise RuntimeError("semantic_benchmark_not_comparable_to_active")
            configuration = benchmark["configuration"] or {}
            if configuration.get("catalogSnapshotHash") != benchmark["snapshot_hash"]:
                raise RuntimeError("semantic_benchmark_stale")
            evaluated_configuration = (configuration.get("modelConfigurations") or {}).get(model_id)
            current_configuration = {
                "repository": target["hf_repository"],
                "revision": target["hf_revision"],
                "queryPrefix": target["query_prefix"],
                "passagePrefix": target["passage_prefix"],
                "minimumSimilarity": float(target["minimum_similarity"]),
            }
            if evaluated_configuration != current_configuration:
                raise RuntimeError("semantic_benchmark_configuration_stale")
            target_metric = metric_for(benchmark["metrics"], model_id)
            active_metric = metric_for(benchmark["metrics"], current_id)
            if (
                not target_metric
                or not target_metric.get("eligible")
                or (current_id and (not active_metric or not active_metric.get("eligible")))
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
            if not coverage or not coverage["complete"] or expected == 0 or indexed != expected:
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
