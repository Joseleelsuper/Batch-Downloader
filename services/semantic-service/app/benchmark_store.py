"""Persistencia aislada de benchmarks y mediciones del índice semántico."""
from __future__ import annotations

import json
import time
import uuid
from typing import Any, Protocol

from psycopg import sql

from app.database import Database
from app.embeddings import vector_literal


class HnswBenchmarkStore(Protocol):
    """Contrato mínimo requerido por la evaluación de runtimes."""

    def benchmark_hnsw(
        self,
        *,
        dimensions: int,
        app_ids: list[str],
        document_vectors: list[list[float]],
        query_vectors: list[list[float]],
        cutoff: int = 20,
    ) -> dict[str, float | int]:
        """Mide construcción y recall del índice HNSW temporal."""


class SemanticBenchmarkStore:
    """Ejecuta las transacciones exclusivas de benchmarking semántico."""

    def __init__(self, database: Database) -> None:
        """Inicializa el store con una base de datos ya configurada."""
        self.database = database

    def benchmark_hnsw(
        self,
        *,
        dimensions: int,
        app_ids: list[str],
        document_vectors: list[list[float]],
        query_vectors: list[list[float]],
        cutoff: int = 20,
    ) -> dict[str, float | int]:
        """Mide recall, tiempo de construcción y tamaño de un HNSW temporal."""
        if not app_ids or not query_vectors:
            return {
                "hnswRecallAt20": 0.0,
                "hnswBuildMs": 0.0,
                "hnswIndexBytes": 0,
            }
        table_name = f"semantic_benchmark_{uuid.uuid4().hex}"
        index_name = f"{table_name}_hnsw"

        def mutate(connection: Any) -> dict[str, float | int]:
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
        """Persiste de forma atómica la evidencia y las rutas de un benchmark."""
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
