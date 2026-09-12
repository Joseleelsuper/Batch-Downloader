"""Mide HNSW en tablas temporales de PostgreSQL y guarda evidencia de las ejecuciones del
entrenador.
"""
from __future__ import annotations

import json
import time
import uuid
from typing import Any, Protocol

from psycopg import sql

from app.database import Database
from app.embeddings import vector_literal


class HnswBenchmarkStore(Protocol):
    """Contrato mínimo para medir recall aproximado, coste de construcción y tamaño de un índice
    sobre vectores preparados.

    See Also:
        app.runtime_evaluation.prepare_runtime_evaluation: Consume mediciones sin conocer las
            tablas de PostgreSQL.
    """

    def benchmark_hnsw(
        self,
        *,
        dimensions: int,
        app_ids: list[str],
        document_vectors: list[list[float]],
        query_vectors: list[list[float]],
        cutoff: int = 20,
    ) -> dict[str, float | int]:
        """Compara el ranking aproximado con el exacto sobre el mismo corpus y conjunto de
        consultas.

        Args:
            dimensions: Número positivo de componentes de los vectores, compatible con
                pgvector.
            app_ids: UUID del corpus en el mismo orden y con la misma longitud que
                document_vectors.
            document_vectors: Vectores normalizados del corpus, ordenados por app_ids.
            query_vectors: Vectores normalizados de consultas; se evalúan como máximo los
                primeros cien.
            cutoff: Tamaño positivo del ranking comparado, limitado al número de documentos;
                por defecto 20.

        Returns:
            hnswRecallAt20, hnswBuildMs en milisegundos y hnswIndexBytes en bytes.
        """


class SemanticBenchmarkStore:
    """Implementa medición HNSW aislada en una tabla temporal y persistencia de reportes de
    entrenamiento.

    See Also:
        HnswBenchmarkStore: Contrato utilizado durante la evaluación de variantes.
    """

    def __init__(self, database: Database) -> None:
        """Conserva el pool para medir índices temporales y guardar ejecuciones.

        Args:
            database: Pool que delimita las transacciones de persistencia y evaluación.
        """
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
        """Crea un índice HNSW temporal y compara hasta cien consultas con su ranking exacto
        usando ef_search=40.
        La tabla desaparece al confirmar; un corpus o consultas vacíos devuelven métricas en
        cero.

        Args:
            dimensions: Número positivo de componentes de los vectores, compatible con
                pgvector.
            app_ids: UUID del corpus en el mismo orden y con la misma longitud que
                document_vectors.
            document_vectors: Vectores normalizados del corpus, ordenados por app_ids.
            query_vectors: Vectores normalizados de consultas; se evalúan como máximo los
                primeros cien.
            cutoff: Tamaño positivo del ranking comparado, limitado al número de documentos;
                por defecto 20.

        Returns:
            recall medio, tiempo de construcción en ms y tamaño real del índice en bytes.
        """
        if not app_ids or not query_vectors:
            return {
                "hnswRecallAt20": 0.0,
                "hnswBuildMs": 0.0,
                "hnswIndexBytes": 0,
            }
        table_name = f"semantic_benchmark_{uuid.uuid4().hex}"
        index_name = f"{table_name}_hnsw"

        def mutate(connection: Any) -> dict[str, float | int]:
            """Inserta vectores en una tabla temporal y mide HNSW alternando planes exactos y
            aproximados en la misma conexión.

            Args:
                connection: Conexión de la misma transacción que contiene tabla temporal,
                    índice y consultas de medición.

            Returns:
                métricas del índice temporal antes de descartarlo al confirmar.
            """
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
        """Guarda configuración, métricas, candidato y rutas de informes sin modificar la
        selección ni activación de modelos.

        Args:
            run_id: UUID que vincula los tres formatos de informe a la misma evaluación.
            dataset_hash: Huella del snapshot de documentos y consultas.
            seed: Semilla de particiones y selección del conjunto de consultas.
            configuration: Parámetros de la ejecución, incluidos snapshot y pesos de
                comparación.
            metrics: Filas de calidad, latencias y tamaños por modelo; deben contener al menos
                una variante.
            selected_model_version: Candidato seleccionado por el entrenador o None si no hubo
                uno elegible.
            paths: Rutas de los informes json, csv y markdown ya escritos.
        """
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
