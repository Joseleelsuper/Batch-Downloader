from __future__ import annotations

import hashlib
import json
import time
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any

from psycopg import sql

from app.database import Database
from app.embeddings import RegisteredModel, vector_literal
from app.model_registry import model_index_name


def ensure_model_hnsw_index(
    connection: Any,
    *,
    model_version: str,
    dimensions: int,
) -> None:
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
    def __init__(self, database: Database) -> None:
        self.database = database

    def active_model(self) -> tuple[RegisteredModel, str] | None:
        def query(connection):
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
        row = self.database.run(
            lambda connection: connection.execute(
                """
                SELECT model_version
                FROM embedding_models
                WHERE lifecycle_state IN ('selected', 'active')
                ORDER BY CASE lifecycle_state WHEN 'selected' THEN 0 ELSE 1 END, created_at DESC
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
        literal = vector_literal(query_vector)

        def query(connection):
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
        changed = 0

        def mutate(connection):
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
                # A document hash is shared by every model projection. Mark all
                # published states incomplete before any new vectors are built,
                # so an active model can never serve a partially current sweep.
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
        def mutate(connection):
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
        def mutate(connection):
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
        def mutate(connection):
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
        retry_at = datetime.now(timezone.utc) + timedelta(seconds=30)
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
        def mutate(connection):
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
            if complete:
                lifecycle = connection.execute(
                    "SELECT lifecycle_state FROM embedding_models WHERE model_version = %s",
                    (model_version,),
                ).fetchone()
                if lifecycle and lifecycle["lifecycle_state"] in {"selected", "active"}:
                    connection.execute(
                        """
                        UPDATE embedding_models
                        SET active = FALSE,
                            lifecycle_state = CASE
                                WHEN active THEN 'retired' ELSE lifecycle_state END
                        WHERE active = TRUE AND model_version <> %s
                        """,
                        (model_version,),
                    )
                    connection.execute(
                        """
                        UPDATE embedding_models
                        SET active = TRUE, lifecycle_state = 'active',
                            activated_at = COALESCE(activated_at, now())
                        WHERE model_version = %s
                        """,
                        (model_version,),
                    )
                    connection.execute(
                        """
                        UPDATE semantic_index_state
                        SET activated_at = COALESCE(activated_at, now())
                        WHERE model_version = %s
                        """,
                        (model_version,),
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
        if not app_ids or not query_vectors:
            return {
                "hnswRecallAt20": 0.0,
                "hnswBuildMs": 0.0,
                "hnswIndexBytes": 0,
            }
        table_name = f"semantic_benchmark_{uuid.uuid4().hex}"
        index_name = f"{table_name}_hnsw"

        def mutate(connection):
            table = sql.Identifier(table_name)
            index = sql.Identifier(index_name)
            dimension = sql.SQL(str(dimensions))
            connection.execute(
                sql.SQL(
                    "CREATE TEMP TABLE {} ("
                    "app_id UUID PRIMARY KEY, embedding vector({}) NOT NULL"
                    ") ON COMMIT DROP"
                ).format(table, dimension)
            )
            with connection.cursor() as cursor:
                cursor.executemany(
                    sql.SQL(
                        "INSERT INTO {} (app_id, embedding) VALUES (%s, %s::vector)"
                    ).format(table),
                    [
                        (app_id, vector_literal(vector))
                        for app_id, vector in zip(
                            app_ids,
                            document_vectors,
                            strict=True,
                        )
                    ],
                )
            started = time.perf_counter()
            connection.execute(
                sql.SQL(
                    "CREATE INDEX {} ON {} "
                    "USING hnsw (embedding vector_cosine_ops)"
                ).format(index, table)
            )
            build_ms = (time.perf_counter() - started) * 1000
            connection.execute(sql.SQL("ANALYZE {}").format(table))
            index_bytes = int(
                connection.execute(
                    "SELECT pg_relation_size(%s::regclass)",
                    (index_name,),
                ).fetchone()["pg_relation_size"]
            )
            recalls: list[float] = []
            result_limit = min(cutoff, len(app_ids))
            ranking_query = sql.SQL(
                "SELECT app_id::text AS app_id FROM {} "
                "ORDER BY embedding <=> %s::vector LIMIT %s"
            ).format(table)
            for query_vector in query_vectors[:100]:
                literal = vector_literal(query_vector)
                connection.execute("SET LOCAL enable_indexscan = off")
                connection.execute("SET LOCAL enable_bitmapscan = off")
                connection.execute("SET LOCAL enable_seqscan = on")
                exact = {
                    row["app_id"]
                    for row in connection.execute(
                        ranking_query,
                        (literal, result_limit),
                    ).fetchall()
                }
                connection.execute("SET LOCAL enable_indexscan = on")
                connection.execute("SET LOCAL enable_bitmapscan = on")
                connection.execute("SET LOCAL enable_seqscan = off")
                connection.execute("SET LOCAL hnsw.ef_search = 40")
                approximate = {
                    row["app_id"]
                    for row in connection.execute(
                        ranking_query,
                        (literal, result_limit),
                    ).fetchall()
                }
                recalls.append(
                    len(exact & approximate) / len(exact) if exact else 1.0
                )
            return {
                "hnswRecallAt20": sum(recalls) / len(recalls),
                "hnswBuildMs": build_ms,
                "hnswIndexBytes": index_bytes,
            }

        return self.database.run(mutate)

    def active_documents(self) -> list[dict[str, Any]]:
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
        """Atomically activate or roll back to a version with current full coverage."""

        def mutate(connection):
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
        self.database.run(
            lambda connection: connection.execute(
                """
                INSERT INTO benchmark_runs (
                    id, dataset_hash, seed, configuration, metrics,
                    selected_model_version, report_json_path, report_csv_path,
                    report_markdown_path
                ) VALUES (%s, %s, %s, %s::jsonb, %s::jsonb, %s, %s, %s, %s)
                """,
                (
                    str(uuid.UUID(run_id)),
                    dataset_hash,
                    seed,
                    json.dumps(configuration, sort_keys=True),
                    json.dumps(metrics, sort_keys=True),
                    selected_model_version,
                    paths["json"],
                    paths["csv"],
                    paths["markdown"],
                ),
            )
        )
