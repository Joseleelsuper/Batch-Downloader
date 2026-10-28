"""Proyección independiente de bundles; no modifica documentos ni cobertura de aplicaciones."""

from __future__ import annotations

import hashlib
from datetime import datetime
from typing import Any

from app.database import Database
from app.embeddings import RegisteredModel, vector_literal


class BundleSemanticStore:
    def __init__(self, database: Database) -> None:
        self.database = database

    def checkpoint(self, version: str) -> int | None:
        def query(connection: Any):
            row = connection.execute(
                "SELECT source_revision FROM bundle_index_state WHERE model_version=%s", (version,)
            ).fetchone()
            return int(row["source_revision"]) if row else None

        return self.database.run(query)

    def upsert_page(self, documents: list[dict[str, Any]], version: str, seen: datetime) -> None:
        def mutate(connection: Any):
            for document in documents:
                values = (document["bundleId"], document["contentHash"], document["content"], seen)
                connection.execute(
                    """
                    INSERT INTO bundle_semantic_documents(bundle_id,content_hash,content,seen_at)
                    VALUES (%s,%s,%s,%s)
                    ON CONFLICT(bundle_id) DO UPDATE SET content_hash=EXCLUDED.content_hash,
                      content=EXCLUDED.content,seen_at=EXCLUDED.seen_at
                    """,
                    values,
                )
                connection.execute(
                    """
                    INSERT INTO bundle_embedding_jobs(bundle_id,model_version,content_hash)
                    SELECT %s,%s,%s WHERE NOT EXISTS(SELECT 1 FROM bundle_embeddings
                      WHERE bundle_id=%s AND model_version=%s AND content_hash=%s)
                    ON CONFLICT(bundle_id,model_version,content_hash) DO UPDATE
                    SET status='queued',available_at=now(),lease_until=NULL
                    """,
                    (document["bundleId"], version, document["contentHash"]) * 2,
                )
            self._refresh(connection, version)

        self.database.run(mutate)

    def finish_sweep(self, seen: datetime, version: str) -> None:
        def mutate(connection: Any):
            connection.execute("DELETE FROM bundle_semantic_documents WHERE seen_at<%s", (seen,))
            self._refresh(connection, version)

        self.database.run(mutate)

    def claim_jobs(self, version: str, limit: int, lease: int) -> list[dict[str, Any]]:
        def mutate(connection: Any):
            return connection.execute(
                """
                WITH claimed AS (
                  SELECT j.id FROM bundle_embedding_jobs j
                  JOIN bundle_semantic_documents d ON d.bundle_id=j.bundle_id
                  WHERE j.model_version=%s AND j.content_hash=d.content_hash
                    AND j.available_at<=now() AND (j.status IN ('queued','failed')
                      OR (j.status='processing' AND j.lease_until<now()))
                  ORDER BY j.id LIMIT %s FOR UPDATE OF j SKIP LOCKED
                ) UPDATE bundle_embedding_jobs j SET status='processing',attempts=attempts+1,
                    lease_until=now()+(%s * interval '1 second')
                  FROM claimed, bundle_semantic_documents d
                  WHERE j.id=claimed.id AND d.bundle_id=j.bundle_id RETURNING j.*,d.content
                """,
                (version, limit, lease),
            ).fetchall()

        return self.database.run(mutate)

    def complete_jobs(
        self, version: str, jobs: list[dict[str, Any]], vectors: list[list[float]]
    ) -> None:
        def mutate(connection: Any):
            for job, vector in zip(jobs, vectors, strict=True):
                connection.execute(
                    """
                    INSERT INTO bundle_embeddings(bundle_id,model_version,content_hash,embedding)
                    VALUES (%s,%s,%s,%s::vector) ON CONFLICT(bundle_id,model_version) DO UPDATE SET
                      content_hash=EXCLUDED.content_hash,embedding=EXCLUDED.embedding
                    """,
                    (job["bundle_id"], version, job["content_hash"], vector_literal(vector)),
                )
                connection.execute(
                    "UPDATE bundle_embedding_jobs SET status='completed',lease_until=NULL,"
                    "last_error=NULL WHERE id=%s",
                    (job["id"],),
                )
            self._refresh(connection, version)

        self.database.run(mutate)

    def fail_jobs(self, jobs: list[dict[str, Any]], error: str) -> None:
        def mutate(connection: Any):
            for job in jobs:
                connection.execute(
                    "UPDATE bundle_embedding_jobs SET status='failed',lease_until=NULL,"
                    "last_error=%s,available_at=now()+interval '30 seconds' WHERE id=%s",
                    (error[:100], job["id"]),
                )

        self.database.run(mutate)

    def acknowledge(self, version: str, revision: int) -> bool:
        def mutate(connection: Any):
            self._refresh(connection, version)
            result = connection.execute(
                "UPDATE bundle_index_state SET source_revision=%s WHERE model_version=%s "
                "AND expected_documents=indexed_documents",
                (revision, version),
            )
            return result.rowcount == 1

        return self.database.run(mutate)

    @staticmethod
    def _refresh(connection: Any, version: str) -> None:
        rows = connection.execute(
            """
            SELECT d.bundle_id,d.content_hash,e.content_hash AS indexed_hash
            FROM bundle_semantic_documents d LEFT JOIN bundle_embeddings e
              ON e.bundle_id=d.bundle_id AND e.model_version=%s ORDER BY d.bundle_id
            """,
            (version,),
        ).fetchall()
        indexed = sum(row["content_hash"] == row["indexed_hash"] for row in rows)
        fingerprint = "|".join(
            f"{row['bundle_id']}:{row['content_hash']}:{row['indexed_hash']}" for row in rows
        )
        index_version = hashlib.sha256(f"{version}:{fingerprint}".encode()).hexdigest()[:20]
        connection.execute(
            """
            INSERT INTO bundle_index_state(model_version,index_version,
              expected_documents,indexed_documents) VALUES (%s,%s,%s,%s)
            ON CONFLICT(model_version) DO UPDATE SET index_version=EXCLUDED.index_version,
              expected_documents=EXCLUDED.expected_documents,
              indexed_documents=EXCLUDED.indexed_documents,built_at=now()
            """,
            (version, index_version, len(rows), indexed),
        )

    def active_model(self) -> tuple[RegisteredModel, str] | None:
        def query(connection: Any):
            row = connection.execute(
                "SELECT m.*,s.index_version FROM embedding_models m JOIN bundle_index_state s "
                "ON s.model_version=m.model_version WHERE s.indexed_documents>0 LIMIT 1"
            ).fetchone()
            return (RegisteredModel.from_row(row), row["index_version"]) if row else None

        return self.database.run(query)

    def exact_search(
        self,
        *,
        model: RegisteredModel,
        query_vector: list[float],
        minimum_similarity: float,
        limit: int,
    ):
        literal = vector_literal(query_vector)

        def query(connection: Any):
            connection.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY")
            state = connection.execute(
                "SELECT index_version FROM bundle_index_state WHERE model_version=%s",
                (model.model_version,),
            ).fetchone()
            if state is None:
                raise RuntimeError("semantic_index_not_ready")
            rows = connection.execute(
                """
                SELECT e.bundle_id,1-(e.embedding <=> %s::vector) AS similarity
                FROM bundle_embeddings e JOIN bundle_semantic_documents d
                  ON d.bundle_id=e.bundle_id AND d.content_hash=e.content_hash
                WHERE e.model_version=%s AND 1-(e.embedding <=> %s::vector)>=%s
                ORDER BY e.embedding <=> %s::vector,e.bundle_id LIMIT %s
                """,
                (literal, model.model_version, literal, minimum_similarity, literal, limit),
            ).fetchall()
            return [
                {
                    "bundleId": str(row["bundle_id"]),
                    "rank": i + 1,
                    "similarity": float(row["similarity"]),
                }
                for i, row in enumerate(rows)
            ], state["index_version"]

        return self.database.run(query)
