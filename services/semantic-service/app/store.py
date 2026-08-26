"""Implementa las responsabilidades del módulo `store`.
"""
from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime, timedelta
from typing import Any

from psycopg import sql

from app.benchmark_store import SemanticBenchmarkStore
from app.database import Database
from app.embeddings import RegisteredModel, vector_literal
from app.model_registry import model_index_name


def ensure_model_hnsw_index(
    connection: Any,
    *,
    model_version: str,
    dimensions: int,
) -> None:
    """Garantiza la operación `model_hnsw_index`.

    Args:
        connection (Any): Conexión de base de datos utilizada por la operación.
        model_version (str): Valor de `model_version` utilizado por la operación.
        dimensions (int): Valor de `dimensions` utilizado por la operación.

    Throws:
        ValueError: Si los datos recibidos no cumplen las restricciones requeridas.
    """
    if not 1 <= dimensions <= 2000:
        raise ValueError(f"unsupported_hnsw_dimensions:{dimensions}")
    connection.execute(
        sql.SQL(
            "CREATE INDEX IF NOT EXISTS {} ON software_embeddings "
            "USING hnsw ((embedding::vector({})) vector_cosine_ops) "
            "WHERE model_version = {}"
        ).format(
            sql.Identifier(model_index_name(model_version)),
            sql.SQL(str(dimensions)),
            sql.Literal(model_version),
        )
    )


class SemanticStore:
    """Gestiona el almacenamiento de `Semantic`.
    """
    def __init__(self, database: Database) -> None:
        """Inicializa una instancia de `SemanticStore`.

        Args:
            database (Database): Acceso a la base de datos utilizado por la operación.
        """
        self.database = database
        """Estado de instancia asociado a `database`.
        """
        self.benchmarks = SemanticBenchmarkStore(database)
        """Transacciones aisladas de medición y persistencia de benchmarks."""

    def active_model(self) -> tuple[RegisteredModel, str] | None:
        """Ejecuta `active_model` dentro de `SemanticStore`.

        Returns:
            tuple[RegisteredModel, str] | None: Resultado producido por la operación.
        """
        def query(connection):
            """Ejecuta la consulta definida para la operación.

            Args:
                connection (Any): Conexión de base de datos utilizada por la operación.
            """
            row = connection.execute(
                """
                SELECT m.*, s.index_version
                FROM embedding_models m
                JOIN semantic_index_state s ON s.model_version = m.model_version
                WHERE m.active = TRUE AND s.complete = TRUE
                ORDER BY m.activated_at DESC NULLS LAST
                LIMIT 1
                """
            ).fetchone()
            if not row:
                return None
            return RegisteredModel.from_row(row), row["index_version"]

        return self.database.run(query)

    def model(self, model_version: str) -> RegisteredModel:
        """Ejecuta `model` dentro de `SemanticStore`.

        Args:
            model_version (str): Valor de `model_version` utilizado por la operación.

        Returns:
            RegisteredModel: Resultado producido por la operación.

        Throws:
            LookupError: Si no existe el elemento solicitado.
        """
        row = self.database.run(
            lambda connection: connection.execute(
                "SELECT * FROM embedding_models WHERE model_version = %s",
                (model_version,),
            ).fetchone()
        )
        if not row:
            raise LookupError("embedding_model_not_registered")
        return RegisteredModel.from_row(row)

    def selected_model_version(self, fallback: str) -> str:
        """Ejecuta `selected_model_version` dentro de `SemanticStore`.

        Args:
            fallback (str): Valor de `fallback` utilizado por la operación.

        Returns:
            str: Resultado producido por la operación.
        """
        row = self.database.run(
            lambda connection: connection.execute(
                """
                SELECT model_version
                FROM embedding_models
                WHERE active = TRUE OR lifecycle_state = 'selected'
                ORDER BY CASE WHEN active THEN 0 ELSE 1 END, created_at DESC
                LIMIT 1
                """
            ).fetchone()
        )
        return row["model_version"] if row else fallback

    def exact_search(
        self,
        *,
        model: RegisteredModel,
        query_vector: list[float],
        minimum_similarity: float,
        limit: int,
    ) -> list[dict[str, Any]]:
        """Ejecuta `exact_search` dentro de `SemanticStore`.

        Args:
            model (RegisteredModel): Modelo utilizado por la operación.
            query_vector (list[float]): Valor de `query_vector` utilizado por la operación.
            minimum_similarity (float): Valor de `minimum_similarity` utilizado por la operación.
            limit (int): Número máximo de elementos que se recuperarán.

        Returns:
            list[dict[str, Any]]: Colección de elementos obtenidos por la operación.
        """
        literal = vector_literal(query_vector)

        def query(connection):
            """Ejecuta la consulta definida para la operación.

            Args:
                connection (Any): Conexión de base de datos utilizada por la operación.
            """
            connection.execute("SET LOCAL enable_indexscan = off")
            connection.execute("SET LOCAL enable_bitmapscan = off")
            rows = connection.execute(
                """
                SELECT e.app_id::text AS app_id,
                       1 - (e.embedding <=> %s::vector) AS similarity
                FROM software_embeddings e
                JOIN semantic_documents d ON d.app_id = e.app_id
                WHERE e.model_version = %s
                  AND e.content_hash = d.content_hash
                  AND d.active = TRUE
                  AND 1 - (e.embedding <=> %s::vector) >= %s
                ORDER BY e.embedding <=> %s::vector, e.app_id
                LIMIT %s
                """,
                (
                    literal,
                    model.model_version,
                    literal,
                    minimum_similarity,
                    literal,
                    limit,
                ),
            ).fetchall()
            return [
                {
                    "appId": row["app_id"],
                    "rank": index + 1,
                    "similarity": float(row["similarity"]),
                }
                for index, row in enumerate(rows)
            ]

        return self.database.run(query)

    def upsert_document_page(
        self,
        documents: list[dict[str, Any]],
        *,
        model_version: str,
        seen_at: datetime,
    ) -> int:
        """Ejecuta `upsert_document_page` dentro de `SemanticStore`.

        Args:
            documents (list[dict[str, Any]]): Colección de documentos que debe procesarse.
            model_version (str): Valor de `model_version` utilizado por la operación.
            seen_at (datetime): Instante asociado a `seen`.

        Returns:
            int: Resultado producido por la operación.
        """
        changed = 0

        def mutate(connection):
            """Aplica la mutación definida para el escenario.

            Args:
                connection (Any): Conexión de base de datos utilizada por la operación.
            """
            nonlocal changed
            for document in documents:
                existing = connection.execute(
                    "SELECT content_hash FROM semantic_documents WHERE app_id = %s",
                    (document["appId"],),
                ).fetchone()
                if not existing or existing["content_hash"] != document["contentHash"]:
                    changed += 1
                connection.execute(
                    """
                    INSERT INTO semantic_documents (
                        app_id, content_hash, content, metadata, active, seen_at, updated_at
                    ) VALUES (%s, %s, %s, %s::jsonb, TRUE, %s, now())
                    ON CONFLICT (app_id) DO UPDATE SET
                        content_hash = EXCLUDED.content_hash,
                        content = EXCLUDED.content,
                        metadata = EXCLUDED.metadata,
                        active = TRUE,
                        seen_at = EXCLUDED.seen_at,
                        updated_at = CASE
                            WHEN semantic_documents.content_hash <> EXCLUDED.content_hash
                            THEN now() ELSE semantic_documents.updated_at END
                    """,
                    (
                        document["appId"],
                        document["contentHash"],
                        document["content"],
                        json.dumps(document["metadata"], ensure_ascii=False),
                        seen_at,
                    ),
                )
                connection.execute(
                    """
                    INSERT INTO embedding_jobs (
                        app_id, model_version, content_hash, status, available_at, updated_at
                    )
                    SELECT %s, %s, %s, 'queued', now(), now()
                    WHERE NOT EXISTS (
                        SELECT 1 FROM software_embeddings
                        WHERE app_id = %s
                          AND model_version = %s
                          AND content_hash = %s
                    )
                    ON CONFLICT (app_id, model_version, content_hash) DO UPDATE SET
                        status = CASE
                            WHEN embedding_jobs.status = 'completed' THEN embedding_jobs.status
                            ELSE 'queued'
                        END,
                        available_at = now(),
                        lease_owner = NULL,
                        lease_until = NULL,
                        updated_at = now()
                    """,
                    (
                        document["appId"],
                        model_version,
                        document["contentHash"],
                        document["appId"],
                        model_version,
                        document["contentHash"],
                    ),
                )
            if changed:
                # El hash de documento se comparte entre todas las proyecciones.
                # Marca como incompletos los estados publicados antes de construir
                # vectores para no servir un barrido parcialmente actualizado.
                connection.execute(
                    """
                    UPDATE embedding_models model
                    SET deployment_state = CASE
                            WHEN model.active THEN 'active'
                            ELSE 'stale'
                        END
                    WHERE EXISTS (
                        SELECT 1
                        FROM semantic_index_state state
                        WHERE state.model_version = model.model_version
                          AND state.complete = TRUE
                    )
                    """
                )
                connection.execute(
                    """
                    UPDATE semantic_index_state
                    SET complete = FALSE, built_at = now()
                    WHERE complete = TRUE
                    """
                )

        self.database.run(mutate)
        return changed

    def finish_sweep(self, seen_at: datetime) -> int:
        """Ejecuta `finish_sweep` dentro de `SemanticStore`.

        Args:
            seen_at (datetime): Instante asociado a `seen`.

        Returns:
            int: Resultado producido por la operación.
        """
        def mutate(connection):
            """Aplica la mutación definida para el escenario.

            Args:
                connection (Any): Conexión de base de datos utilizada por la operación.
            """
            result = connection.execute(
                """
                DELETE FROM semantic_documents
                WHERE seen_at < %s
                """,
                (seen_at,),
            )
            if result.rowcount:
                connection.execute(
                    """
                    UPDATE embedding_models model
                    SET deployment_state = CASE
                            WHEN model.active THEN 'active'
                            ELSE 'stale'
                        END
                    WHERE EXISTS (
                        SELECT 1
                        FROM semantic_index_state state
                        WHERE state.model_version = model.model_version
                          AND state.complete = TRUE
                    )
                    """
                )
                connection.execute(
                    """
                    UPDATE semantic_index_state
                    SET complete = FALSE, built_at = now()
                    WHERE complete = TRUE
                    """
                )
            return result.rowcount

        return self.database.run(mutate)

    def claim_jobs(
        self,
        *,
        model_version: str,
        owner: str,
        limit: int,
        lease_seconds: int,
    ) -> list[dict[str, Any]]:
        """Reserva la operación `jobs`.

        Args:
            model_version (str): Valor de `model_version` utilizado por la operación.
            owner (str): Valor de `owner` utilizado por la operación.
            limit (int): Número máximo de elementos que se recuperarán.
            lease_seconds (int): Valor de `lease_seconds` utilizado por la operación.

        Returns:
            list[dict[str, Any]]: Colección de elementos obtenidos por la operación.
        """
        def mutate(connection):
            """Aplica la mutación definida para el escenario.

            Args:
                connection (Any): Conexión de base de datos utilizada por la operación.
            """
            return connection.execute(
                """
                WITH claimed AS (
                    SELECT j.id
                    FROM embedding_jobs j
                    JOIN semantic_documents d ON d.app_id = j.app_id
                    WHERE j.model_version = %s
                      AND d.active = TRUE
                      AND d.content_hash = j.content_hash
                      AND (
                        (j.status IN ('queued', 'failed') AND j.available_at <= now())
                        OR (j.status = 'processing' AND j.lease_until < now())
                      )
                    ORDER BY j.id
                    FOR UPDATE SKIP LOCKED
                    LIMIT %s
                )
                UPDATE embedding_jobs j
                SET status = 'processing',
                    attempts = attempts + 1,
                    lease_owner = %s,
                    lease_until = now() + (%s * interval '1 second'),
                    updated_at = now()
                FROM claimed, semantic_documents d
                WHERE j.id = claimed.id AND d.app_id = j.app_id
                RETURNING j.id, j.app_id::text AS app_id, j.content_hash, d.content
                """,
                (model_version, limit, owner, lease_seconds),
            ).fetchall()

        return self.database.run(mutate)

    def complete_jobs(
        self,
        *,
        model_version: str,
        jobs: list[dict[str, Any]],
        embeddings: list[list[float]],
    ) -> None:
        """Ejecuta `complete_jobs` dentro de `SemanticStore`.

        Args:
            model_version (str): Valor de `model_version` utilizado por la operación.
            jobs (list[dict[str, Any]]): Valor de `jobs` utilizado por la operación.
            embeddings (list[list[float]]): Valor de `embeddings` utilizado por la operación.
        """
        def mutate(connection):
            """Aplica la mutación definida para el escenario.

            Args:
                connection (Any): Conexión de base de datos utilizada por la operación.
            """
            for job, embedding in zip(jobs, embeddings, strict=True):
                connection.execute(
                    """
                    INSERT INTO software_embeddings (
                        app_id, model_version, content_hash, embedding, generated_at
                    ) VALUES (%s, %s, %s, %s::vector, now())
                    ON CONFLICT (app_id, model_version) DO UPDATE SET
                        content_hash = EXCLUDED.content_hash,
                        embedding = EXCLUDED.embedding,
                        generated_at = now()
                    """,
                    (
                        job["app_id"],
                        model_version,
                        job["content_hash"],
                        vector_literal(embedding),
                    ),
                )
                connection.execute(
                    """
                    UPDATE embedding_jobs
                    SET status = 'completed', lease_owner = NULL, lease_until = NULL,
                        last_error = NULL, updated_at = now()
                    WHERE id = %s
                    """,
                    (job["id"],),
                )

        self.database.run(mutate)

    def fail_jobs(self, jobs: list[dict[str, Any]], error: str) -> None:
        """Ejecuta `fail_jobs` dentro de `SemanticStore`.

        Args:
            jobs (list[dict[str, Any]]): Valor de `jobs` utilizado por la operación.
            error (str): Error que debe registrarse o propagarse.
        """
        retry_at = datetime.now(UTC) + timedelta(seconds=30)
        self.database.run(
            lambda connection: [
                connection.execute(
                    """
                    UPDATE embedding_jobs
                    SET status = 'failed', available_at = %s, lease_owner = NULL,
                        lease_until = NULL, last_error = %s, updated_at = now()
                    WHERE id = %s
                    """,
                    (retry_at, error[:500], job["id"]),
                )
                for job in jobs
            ]
        )

    def coverage_and_promote(self, model_version: str) -> dict[str, Any]:
        """Ejecuta `coverage_and_promote` dentro de `SemanticStore`.

        Args:
            model_version (str): Valor de `model_version` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.
        """

        def mutate(connection):
            """Aplica la mutación definida para el escenario.

            Args:
                connection (Any): Conexión de base de datos utilizada por la operación.

            Throws:
                LookupError: Si no existe el elemento solicitado.
            """
            coverage = connection.execute(
                """
                SELECT COUNT(*) FILTER (WHERE d.active) AS expected,
                       COUNT(*) FILTER (
                           WHERE d.active AND e.content_hash = d.content_hash
                       ) AS indexed,
                       encode(
                           digest(
                               COALESCE(
                                   string_agg(
                                       d.app_id::text || ':' || d.content_hash,
                                       '|' ORDER BY d.app_id
                                   ) FILTER (WHERE d.active),
                                   ''
                               ),
                               'sha256'
                           ),
                           'hex'
                       ) AS snapshot_hash
                FROM semantic_documents d
                LEFT JOIN software_embeddings e
                  ON e.app_id = d.app_id AND e.model_version = %s
                """,
                (model_version,),
            ).fetchone()
            expected = int(coverage["expected"] or 0)
            indexed = int(coverage["indexed"] or 0)
            complete = expected > 0 and expected == indexed
            index_version = hashlib.sha256(
                f"{model_version}:{coverage['snapshot_hash']}".encode()
            ).hexdigest()[:20]
            if complete:
                model_row = connection.execute(
                    "SELECT dimensions FROM embedding_models WHERE model_version = %s",
                    (model_version,),
                ).fetchone()
                if not model_row:
                    raise LookupError("embedding_model_not_registered")
                ensure_model_hnsw_index(
                    connection,
                    model_version=model_version,
                    dimensions=int(model_row["dimensions"]),
                )
            connection.execute(
                """
                INSERT INTO semantic_index_state (
                    model_version, index_version, snapshot_hash,
                    expected_documents, indexed_documents, complete, built_at
                ) VALUES (%s, %s, %s, %s, %s, %s, now())
                ON CONFLICT (model_version) DO UPDATE SET
                    index_version = EXCLUDED.index_version,
                    snapshot_hash = EXCLUDED.snapshot_hash,
                    expected_documents = EXCLUDED.expected_documents,
                    indexed_documents = EXCLUDED.indexed_documents,
                    complete = EXCLUDED.complete,
                    built_at = now()
                """,
                (
                    model_version,
                    index_version,
                    coverage["snapshot_hash"],
                    expected,
                    indexed,
                    complete,
                ),
            )
            connection.execute(
                """
                UPDATE embedding_models
                SET deployment_state = CASE
                        WHEN active THEN 'active'
                        WHEN %s THEN 'ready'
                        ELSE 'preparing'
                    END,
                    lifecycle_state = CASE
                        WHEN active THEN 'active'
                        WHEN lifecycle_state = 'selected' AND %s THEN 'registered'
                        ELSE lifecycle_state
                    END
                WHERE model_version = %s
                """,
                (complete, complete, model_version),
            )
            return {
                "expected": expected,
                "indexed": indexed,
                "complete": complete,
                "indexVersion": index_version,
            }

        return self.database.run(mutate)

    def benchmark_hnsw(
        self,
        *,
        dimensions: int,
        app_ids: list[str],
        document_vectors: list[list[float]],
        query_vectors: list[list[float]],
        cutoff: int = 20,
    ) -> dict[str, float | int]:
        """Ejecuta `benchmark_hnsw` dentro de `SemanticStore`.

        Args:
            dimensions (int): Valor de `dimensions` utilizado por la operación.
            app_ids (list[str]): Colección de identificadores de `app`.
            document_vectors (list[list[float]]): Valor de `document_vectors` utilizado por la
                operación.
            query_vectors (list[list[float]]): Valor de `query_vectors` utilizado por la operación.
            cutoff (int): Valor de `cutoff` utilizado por la operación.

        Returns:
            dict[str, float | int]: Mapa con los datos producidos por la operación.
        """
        return self.benchmarks.benchmark_hnsw(
            dimensions=dimensions,
            app_ids=app_ids,
            document_vectors=document_vectors,
            query_vectors=query_vectors,
            cutoff=cutoff,
        )

    def active_documents(self) -> list[dict[str, Any]]:
        """Ejecuta `active_documents` dentro de `SemanticStore`.

        Returns:
            list[dict[str, Any]]: Colección de elementos obtenidos por la operación.
        """
        return self.database.run(
            lambda connection: connection.execute(
                """
                SELECT app_id::text AS app_id, content_hash, content, metadata
                FROM semantic_documents
                WHERE active = TRUE
                ORDER BY app_id
                """
            ).fetchall()
        )

    def register_trained_model(
        self,
        *,
        base: RegisteredModel,
        model_version: str,
        artifact_path: str,
        dataset_hash: str,
        training_config: dict[str, Any],
    ) -> None:
        """Ejecuta `register_trained_model` dentro de `SemanticStore`.

        Args:
            base (RegisteredModel): Valor de `base` utilizado por la operación.
            model_version (str): Valor de `model_version` utilizado por la operación.
            artifact_path (str): Ruta de `artifact` utilizada por la operación.
            dataset_hash (str): Valor de `dataset_hash` utilizado por la operación.
            training_config (dict[str, Any]): Valor de `training_config` utilizado por la operación.
        """
        self.database.run(
            lambda connection: connection.execute(
                """
                INSERT INTO embedding_models (
                    model_version, model_key, hf_repository, hf_revision, dimensions,
                    query_prefix, passage_prefix, artifact_path, dataset_hash,
                    training_config, lifecycle_state
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s::jsonb, 'registered')
                ON CONFLICT (model_version) DO NOTHING
                """,
                (
                    model_version,
                    base.model_key,
                    base.hf_repository,
                    base.hf_revision,
                    base.dimensions,
                    base.query_prefix,
                    base.passage_prefix,
                    artifact_path,
                    dataset_hash,
                    json.dumps(training_config, sort_keys=True),
                ),
            )
        )

    def select_model(self, model_version: str, *, rrf_weight: float = 1.0) -> None:
        """Ejecuta `select_model` dentro de `SemanticStore`.

        Args:
            model_version (str): Valor de `model_version` utilizado por la operación.
            rrf_weight (float): Valor de `rrf_weight` utilizado por la operación.
        """
        self.database.run(
            lambda connection: connection.execute(
                """
                UPDATE embedding_models
                SET lifecycle_state = CASE
                    WHEN model_version = %s THEN 'selected'
                    WHEN lifecycle_state = 'selected' THEN 'registered'
                    ELSE lifecycle_state END,
                    rrf_weight = CASE
                        WHEN model_version = %s THEN %s
                        ELSE rrf_weight END
                WHERE model_version = %s OR lifecycle_state = 'selected'
                """,
                (model_version, model_version, rrf_weight, model_version),
            )
        )

    def activate_complete_model(
        self,
        model_version: str,
        *,
        rrf_weight: float | None = None,
    ) -> dict[str, Any]:
        """Ejecuta `activate_complete_model` dentro de `SemanticStore`.

        Args:
            model_version (str): Valor de `model_version` utilizado por la operación.
            rrf_weight (float | None): Valor de `rrf_weight` utilizado por la operación.

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
            model = connection.execute(
                """
                SELECT dimensions, rrf_weight
                FROM embedding_models
                WHERE model_version = %s
                """,
                (model_version,),
            ).fetchone()
            if not model:
                raise LookupError("embedding_model_not_registered")
            coverage = connection.execute(
                """
                SELECT COUNT(*) FILTER (WHERE d.active) AS expected,
                       COUNT(*) FILTER (
                           WHERE d.active AND e.content_hash = d.content_hash
                       ) AS indexed,
                       encode(
                           digest(
                               COALESCE(
                                   string_agg(
                                       d.app_id::text || ':' || d.content_hash,
                                       '|' ORDER BY d.app_id
                                   ) FILTER (WHERE d.active),
                                   ''
                               ),
                               'sha256'
                           ),
                           'hex'
                       ) AS snapshot_hash
                FROM semantic_documents d
                LEFT JOIN software_embeddings e
                  ON e.app_id = d.app_id AND e.model_version = %s
                """,
                (model_version,),
            ).fetchone()
            expected = int(coverage["expected"] or 0)
            indexed = int(coverage["indexed"] or 0)
            if expected == 0 or indexed != expected:
                raise RuntimeError(
                    f"model_coverage_incomplete:{indexed}/{expected}"
                )
            index_version = hashlib.sha256(
                f"{model_version}:{coverage['snapshot_hash']}".encode()
            ).hexdigest()[:20]
            ensure_model_hnsw_index(
                connection,
                model_version=model_version,
                dimensions=int(model["dimensions"]),
            )
            connection.execute(
                """
                INSERT INTO semantic_index_state (
                    model_version, index_version, snapshot_hash,
                    expected_documents, indexed_documents, complete,
                    built_at, activated_at
                ) VALUES (%s, %s, %s, %s, %s, TRUE, now(), now())
                ON CONFLICT (model_version) DO UPDATE SET
                    index_version = EXCLUDED.index_version,
                    snapshot_hash = EXCLUDED.snapshot_hash,
                    expected_documents = EXCLUDED.expected_documents,
                    indexed_documents = EXCLUDED.indexed_documents,
                    complete = TRUE,
                    built_at = now(),
                    activated_at = now()
                """,
                (
                    model_version,
                    index_version,
                    coverage["snapshot_hash"],
                    expected,
                    indexed,
                ),
            )
            connection.execute(
                """
                UPDATE embedding_models
                SET active = FALSE,
                    lifecycle_state = CASE
                        WHEN active THEN 'retired'
                        WHEN lifecycle_state = 'selected' THEN 'registered'
                        ELSE lifecycle_state END
                WHERE model_version <> %s
                  AND (active = TRUE OR lifecycle_state = 'selected')
                """,
                (model_version,),
            )
            connection.execute(
                """
                UPDATE embedding_models
                SET active = TRUE,
                    lifecycle_state = 'active',
                    activated_at = now(),
                    rrf_weight = %s
                WHERE model_version = %s
                """,
                (
                    rrf_weight
                    if rrf_weight is not None
                    else float(model["rrf_weight"]),
                    model_version,
                ),
            )
            return {
                "modelVersion": model_version,
                "indexVersion": index_version,
                "expected": expected,
                "indexed": indexed,
                "rrfWeight": (
                    rrf_weight
                    if rrf_weight is not None
                    else float(model["rrf_weight"])
                ),
            }

        return self.database.run(mutate)

    def save_benchmark_run(
        self,
        *,
        run_id: str,
        dataset_hash: str,
        seed: int,
        configuration: dict[str, Any],
        metrics: list[dict[str, Any]],
        selected_model_version: str | None,
        paths: dict[str, str],
    ) -> None:
        """Guarda la operación `benchmark_run`.

        Args:
            run_id (str): Identificador de `run` utilizado por la operación.
            dataset_hash (str): Valor de `dataset_hash` utilizado por la operación.
            seed (int): Valor de `seed` utilizado por la operación.
            configuration (dict[str, Any]): Valor de `configuration` utilizado por la operación.
            metrics (list[dict[str, Any]]): Valor de `metrics` utilizado por la operación.
            selected_model_version (str | None): Valor de `selected_model_version` utilizado por la
                operación.
            paths (dict[str, str]): Valor de `paths` utilizado por la operación.
        """
        self.benchmarks.save_benchmark_run(
            run_id=run_id,
            dataset_hash=dataset_hash,
            seed=seed,
            configuration=configuration,
            metrics=metrics,
            selected_model_version=selected_model_version,
            paths=paths,
        )
