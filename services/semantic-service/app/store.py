"""Persistencia minima del catalogo, embeddings y estado del indice."""
from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime, timedelta
from typing import Any

from app.catalog_coverage import model_catalog_coverage
from app.database import Database
from app.embeddings import RegisteredModel, vector_literal
from app.model_validation import ModelDescriptor


def invalidate_complete_indexes(connection: Any) -> None:
    """Retira disponibilidad mientras se reconstruyen vectores."""
    connection.execute("UPDATE semantic_index_state SET complete = FALSE, built_at = now()")
    connection.execute("UPDATE embedding_models SET active = FALSE")


class SemanticStore:
    """Mantiene una sola version de modelo y la proyeccion indexable del catalogo."""

    def __init__(self, database: Database) -> None:
        self.database = database

    def ensure_model(self, descriptor: ModelDescriptor) -> RegisteredModel:
        """Registra la carpeta actual y elimina embeddings de una version anterior."""
        def mutate(connection: Any) -> RegisteredModel:
            row = connection.execute("SELECT * FROM embedding_models LIMIT 1").fetchone()
            same = row and (
                row["model_version"] == descriptor.model_version
                and int(row["dimensions"]) == descriptor.dimensions
                and row["query_prefix"] == descriptor.query_prefix
                and row["passage_prefix"] == descriptor.passage_prefix
                and float(row["minimum_similarity"]) == descriptor.minimum_similarity
            )
            if not same:
                connection.execute("DELETE FROM embedding_models")
                connection.execute(
                    """
                    INSERT INTO embedding_models(
                        model_version, dimensions, query_prefix, passage_prefix,
                        minimum_similarity, active
                    ) VALUES (%s, %s, %s, %s, %s, FALSE)
                    """,
                    (
                        descriptor.model_version,
                        descriptor.dimensions,
                        descriptor.query_prefix,
                        descriptor.passage_prefix,
                        descriptor.minimum_similarity,
                    ),
                )
                row = connection.execute("SELECT * FROM embedding_models LIMIT 1").fetchone()
            return RegisteredModel.from_row(row)

        return self.database.run(mutate)

    def active_model(self) -> tuple[RegisteredModel, str] | None:
        def query(connection: Any):
            row = connection.execute(
                """
                SELECT m.*, s.index_version
                FROM embedding_models m
                JOIN semantic_index_state s ON s.model_version = m.model_version
                WHERE m.active AND s.complete
                LIMIT 1
                """
            ).fetchone()
            return None if not row else (RegisteredModel.from_row(row), row["index_version"])

        return self.database.run(query)

    def semantic_status(self) -> dict[str, Any]:
        """Devuelve solo datos seguros para la pantalla administrativa de estado."""
        def query(connection: Any) -> dict[str, Any]:
            row = connection.execute(
                """
                SELECT m.model_version, m.dimensions, m.active,
                       s.index_version, s.expected_documents, s.indexed_documents,
                       s.complete, s.built_at
                FROM embedding_models m
                LEFT JOIN semantic_index_state s ON s.model_version = m.model_version
                LIMIT 1
                """
            ).fetchone()
            if not row:
                return {
                    "model": None,
                    "index": {"expected": 0, "indexed": 0, "complete": False},
                }
            return {
                "model": {
                    "version": row["model_version"],
                    "dimensions": int(row["dimensions"]),
                    "active": bool(row["active"]),
                },
                "index": {
                    "indexVersion": row["index_version"],
                    "expected": int(row["expected_documents"] or 0),
                    "indexed": int(row["indexed_documents"] or 0),
                    "complete": bool(row["complete"]),
                    "builtAt": row["built_at"],
                },
            }

        return self.database.run(query)

    def exact_search(
        self,
        *,
        model: RegisteredModel,
        query_vector: list[float],
        minimum_similarity: float,
        limit: int,
    ) -> list[dict[str, Any]]:
        literal = vector_literal(query_vector)

        def query(connection: Any):
            rows = connection.execute(
                """
                SELECT e.app_id::text AS app_id,
                       1 - (e.embedding <=> %s::vector) AS similarity
                FROM software_embeddings e
                JOIN semantic_documents d ON d.app_id = e.app_id
                WHERE e.model_version = %s
                  AND e.content_hash = d.content_hash
                  AND d.active
                  AND 1 - (e.embedding <=> %s::vector) >= %s
                ORDER BY e.embedding <=> %s::vector, e.app_id
                LIMIT %s
                """,
                (literal, model.model_version, literal, minimum_similarity, literal, limit),
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

        def mutate(connection: Any) -> None:
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
                    INSERT INTO semantic_documents(
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
                        json.dumps(document.get("metadata") or {}, ensure_ascii=False),
                        seen_at,
                    ),
                )
                connection.execute(
                    """
                    INSERT INTO embedding_jobs(
                        app_id, model_version, content_hash, status, available_at, updated_at
                    )
                    SELECT %s, %s, %s, 'queued', now(), now()
                    WHERE NOT EXISTS(
                        SELECT 1 FROM software_embeddings
                        WHERE app_id = %s AND model_version = %s AND content_hash = %s
                    )
                    ON CONFLICT(app_id, model_version, content_hash) DO UPDATE SET
                        status = CASE WHEN embedding_jobs.status = 'completed'
                            THEN embedding_jobs.status ELSE 'queued' END,
                        available_at = now(), lease_owner = NULL, lease_until = NULL,
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
                invalidate_complete_indexes(connection)

        self.database.run(mutate)
        return changed

    def finish_sweep(self, seen_at: datetime) -> int:
        def mutate(connection: Any) -> int:
            result = connection.execute(
                "DELETE FROM semantic_documents WHERE seen_at < %s", (seen_at,)
            )
            if result.rowcount:
                invalidate_complete_indexes(connection)
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
        def mutate(connection: Any):
            return connection.execute(
                """
                WITH claimed AS (
                    SELECT j.id
                    FROM embedding_jobs j
                    JOIN semantic_documents d ON d.app_id = j.app_id
                    WHERE j.model_version = %s AND d.active
                      AND d.content_hash = j.content_hash
                      AND ((j.status IN ('queued', 'failed') AND j.available_at <= now())
                           OR (j.status = 'processing' AND j.lease_until < now()))
                    ORDER BY j.id
                    FOR UPDATE SKIP LOCKED
                    LIMIT %s
                )
                UPDATE embedding_jobs j
                SET status = 'processing', attempts = attempts + 1,
                    lease_owner = %s,
                    lease_until = now() + (%s * interval '1 second'), updated_at = now()
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
        def mutate(connection: Any) -> None:
            for job, embedding in zip(jobs, embeddings, strict=True):
                connection.execute(
                    """
                    INSERT INTO software_embeddings(
                        app_id, model_version, content_hash, embedding, generated_at
                    ) VALUES (%s, %s, %s, %s::vector, now())
                    ON CONFLICT(app_id, model_version) DO UPDATE SET
                        content_hash = EXCLUDED.content_hash,
                        embedding = EXCLUDED.embedding, generated_at = now()
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
        def mutate(connection: Any) -> dict[str, Any]:
            coverage = model_catalog_coverage(connection, model_version)
            expected = int(coverage["expected"] or 0)
            indexed = int(coverage["indexed"] or 0)
            complete = expected > 0 and expected == indexed
            index_version = hashlib.sha256(
                f"{model_version}:{coverage['snapshot_hash']}".encode()
            ).hexdigest()[:20]
            connection.execute(
                """
                INSERT INTO semantic_index_state(
                    model_version, index_version, snapshot_hash, expected_documents,
                    indexed_documents, complete, built_at, activated_at
                ) VALUES (%s, %s, %s, %s, %s, %s, now(),
                          CASE WHEN %s THEN now() ELSE NULL END)
                ON CONFLICT(model_version) DO UPDATE SET
                    index_version = EXCLUDED.index_version,
                    snapshot_hash = EXCLUDED.snapshot_hash,
                    expected_documents = EXCLUDED.expected_documents,
                    indexed_documents = EXCLUDED.indexed_documents,
                    complete = EXCLUDED.complete,
                    built_at = now(),
                    activated_at = CASE WHEN EXCLUDED.complete THEN now() ELSE NULL END
                """,
                (
                    model_version,
                    index_version,
                    coverage["snapshot_hash"],
                    expected,
                    indexed,
                    complete,
                    complete,
                ),
            )
            connection.execute(
                """
                UPDATE embedding_models
                SET active = %s,
                    activated_at = CASE WHEN %s THEN now() ELSE NULL END
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
