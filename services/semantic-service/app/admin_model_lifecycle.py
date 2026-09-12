"""Controla preparación, publicación de benchmarks, activación y borrado transaccional de modelos
locales.
"""

from __future__ import annotations

import json
import uuid
from typing import Any

from psycopg import sql

from app.admin_rows import metric_for
from app.catalog_fingerprint import CATALOG_SNAPSHOT_CTE
from app.database import Database
from app.model_registry import model_index_name


def require_activation_benchmark(
    benchmark: Any, target: Any, model_id: str, current_id: str | None, confirm_regression: bool,
) -> None:
    """Impide activar un benchmark obsoleto, incompleto o ajeno a la configuración del candidato
    y del modelo activo.
    La regresión de puntuación exige confirmación explícita; las filas de ambos modelos
    permanecen bloqueadas por el llamador.

    Args:
        benchmark: Fila de benchmark con su configuración, métricas y huella actual del
            catálogo.
        target: Fila bloqueada del modelo candidato con su configuración actual.
        model_id: UUID del artefacto persistido; no es la versión del modelo ni el nombre del
            repositorio.
        current_id: UUID del modelo activo bloqueado o None si todavía no se ha activado
            ninguno.
        confirm_regression: Autoriza una puntuación inferior al modelo activo, sin omitir las
            demás garantías.

    Raises:
        RuntimeError: Código estable que identifica evidencia ausente, no comparable,
            obsoleta, no elegible o una regresión sin confirmar.
    """
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


class SemanticModelLifecycleStore:
    """Aplica transiciones de modelo e índice con sus invariantes y completa la operación
    administrativa dentro de la misma transacción.

    See Also:
        app.admin_operation_store.SemanticOperationStore: Entrega las operaciones reservadas
            al trabajador.
        app.model_worker.SemanticModelWorker: Coordina archivos e inferencia fuera de las
            transacciones.
    """

    def __init__(self, database: Database) -> None:
        """Usa el pool compartido para confirmar o revertir juntas las transiciones de cada caso
        de uso.

        Args:
            database: Pool que proporciona una transacción independiente para cada llamada
                run.
        """
        self.database = database

    def mark_preparing(self, model_id: str) -> str:
        """Marca el despliegue preparing y selecciona los modelos inactivos para su indexación
        sin retirar el activo.

        Args:
            model_id: UUID del artefacto persistido; no es la versión del modelo ni el nombre
                del repositorio.

        Returns:
            versión registrada que debe indexarse.

        Raises:
            LookupError: Si el artefacto aún no tiene una versión de embeddings registrada.
        """

        def mutate(connection):
            """Actualiza el estado de preparación y obtiene su versión con la misma sentencia
            SQL.

            Args:
                connection: Conexión cedida por Database.run; el llamador confirma o revierte
                    todos sus cambios.

            Returns:
                versión del modelo a preparar.

            Raises:
                LookupError: Si el artefacto carece de registro de embeddings.
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
        """Termina la preparación como ready para modelos inactivos; conserva active si ya sirven
        búsquedas.

        Args:
            model_version: Identidad inmutable de la revisión y variante del modelo
                registrado.
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
        """Reconstruye el estado tras una interrupción usando actividad, existencia y completitud
        del índice persistido.

        Args:
            model_id: UUID del artefacto persistido; no es la versión del modelo ni el nombre
                del repositorio.
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
        """Marca fallida la preparación de modelos inactivos y conserva el estado del modelo que
        sigue atendiendo consultas.

        Args:
            model_id: UUID del artefacto persistido; no es la versión del modelo ni el nombre
                del repositorio.
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
        """Guarda una ejecución completa y finaliza la operación running con sus informes en la
        misma transacción.

        Args:
            run_id: UUID de la ejecución de benchmark que se guardará de forma inmutable.
            operation_id: UUID de la operación administrativa cuyo estado y resultado se
                completan.
            model_ids: UUID de todos los artefactos comparados en la misma ejecución.
            dataset_hash: Huella del conjunto de evaluación con el que se obtuvieron las
                métricas.
            seed: Semilla utilizada para repetir la selección y evaluación del conjunto de
                datos.
            configuration: Configuración de evaluación, incluida la huella del catálogo y de
                cada modelo.
            metrics: Resultados por modelo, con elegibilidad y puntuaciones comparables.
            hardware_fingerprint: Identidad del entorno de medición que acompaña a latencias y
                tiempos.
            document_count: Número de documentos incluidos en la evaluación completa.
            query_count: Número de consultas usadas para calcular las métricas.
            paths: Rutas de los informes json, csv y markdown ya generados por el benchmark.
        """
        result = {
            "runId": run_id,
            "datasetHash": dataset_hash,
            "modelIds": model_ids,
            "reports": paths,
        }

        def mutate(connection):
            """Inserta métricas de esquema 2 y confirma progreso, resultado y liberación de la
            reserva de la operación.

            Args:
                connection: Conexión cedida por Database.run; el llamador confirma o revierte
                    todos sus cambios.
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
        """Activa candidato e índice solo si coinciden el modelo anterior esperado, benchmark
        vigente y cobertura completa.
        Bloquea el candidato y el activo, retira el anterior y confirma el resultado de la
        operación en la misma transacción.

        Args:
            model_id: UUID del artefacto persistido; no es la versión del modelo ni el nombre
                del repositorio.
            operation_id: UUID de la operación administrativa cuyo estado y resultado se
                completan.
            benchmark_run_id: UUID del benchmark completo utilizado como evidencia de
                activación.
            expected_current_model_id: UUID del modelo activo observado por quien solicita
                activar; None exige que no haya uno.
            confirm_regression: Autoriza una puntuación inferior al modelo activo, sin omitir
                las demás garantías.

        Returns:
            identidades del modelo e índice activos con cobertura esperada e indexada.

        Raises:
            LookupError: Si el artefacto candidato no está listo.
            RuntimeError: Si cambió el activo, falta evidencia válida o la cobertura está
                incompleta.

        See Also:
            require_activation_benchmark: Condiciones de comparación y regresión.
        """

        def mutate(connection):
            """Revalida la activación con filas bloqueadas antes de cambiar el modelo activo y
            completar la operación.

            Args:
                connection: Conexión cedida por Database.run; el llamador confirma o revierte
                    todos sus cambios.

            Returns:
                resultado de activación que se persiste junto con el cambio de modelo.

            Raises:
                LookupError: Si el candidato no está listo.
                RuntimeError: Si falla la comparación del activo, el benchmark o la cobertura
                    del índice.
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
                CATALOG_SNAPSHOT_CTE + """
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
            require_activation_benchmark(
                benchmark, target, model_id, current_id, confirm_regression,
            )
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
        """Reserva el borrado eliminando índices y registro de embeddings, pero conserva la ruta
        para borrar archivos después.
        La marca delete_in_progress permite distinguir este estado intermedio de un fallo
        ordinario.

        Args:
            model_id: UUID del artefacto persistido; no es la versión del modelo ni el nombre
                del repositorio.
            excluding_operation_id: UUID de la operación de borrado propia que no debe
                bloquearse a sí misma.

        Returns:
            UUID, versión y ruta local necesaria para la limpieza externa.

        Raises:
            LookupError: Si el artefacto no existe.
            RuntimeError: Si el modelo está activo o participa en otra operación abierta.
        """

        def mutate(connection):
            """Bloquea el artefacto, impide borrar modelos en uso y marca el inicio del borrado
            después de retirar su índice.

            Args:
                connection: Conexión cedida por Database.run; el llamador confirma o revierte
                    todos sus cambios.

            Returns:
                identidad y ubicación que debe limpiar el trabajador.

            Raises:
                LookupError: Si el artefacto no existe.
                RuntimeError: Si está activo o tiene otra operación abierta.
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
        """Confirma el borrado del artefacto después de limpiar sus archivos y completa la
        operación en la misma transacción.

        Args:
            model_id: UUID del artefacto persistido; no es la versión del modelo ni el nombre
                del repositorio.
            operation_id: UUID de la operación administrativa cuyo estado y resultado se
                completan.
            model_version: Identidad inmutable de la revisión y variante del modelo
                registrado.

        Returns:
            UUID y versión eliminados, sin conservar la ruta local.

        Raises:
            RuntimeError: Si el artefacto ya no conserva la marca delete_in_progress esperada.
        """

        def mutate(connection):
            """Exige la reserva de borrado antes de marcar deleted y registrar el resultado final
            de la operación.

            Args:
                connection: Conexión cedida por Database.run; el llamador confirma o revierte
                    todos sus cambios.

            Returns:
                identidad del modelo eliminado.

            Raises:
                RuntimeError: Si el estado del artefacto cambió antes de confirmar el borrado.
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
