"""Mide calidad de rankings, latencia e índices sobre un corpus fijo, reutilizando embeddings
entre variantes.
"""

from __future__ import annotations

import os
import statistics
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

import numpy as np
import psutil

from app.benchmark_store import HnswBenchmarkStore
from app.embeddings import EmbeddingRuntime
from app.evaluation import (
    average_precision,
    lexical_rank,
    mean,
    ndcg,
    recall,
    reciprocal_rank,
    reciprocal_rank_fusion,
)

EVALUATION_CANDIDATE_LIMIT = 2000


@dataclass
class PreparedRuntimeEvaluation:
    """Conserva resultados de codificación para comparar pesos RRF sin volver a cargar ni
    codificar el mismo modelo.

    Attributes:
        queries: Consultas evaluadas en orden estable.
        semantic_rankings: Ranking vectorial exacto de hasta 2000 candidatos por consulta.
        lexical_rankings: Ranking léxico o lista vacía si se deshabilitó.
        semantic_latencies_ms: Tiempo de ranking más codificación medida o estimada por
            mediana, en ms.
        lexical_latencies_ms: Latencia del ranking léxico en ms.
        embedding_build_ms: Tiempo de codificación del corpus en ms.
        document_vector_bytes: Bytes ocupados por la matriz float32.
        index_metrics: Recall, tiempo de construcción y tamaño de HNSW medido.
        latency_sample_size: Hasta 100 consultas codificadas individualmente para medir
            latencia.
        includes_lexical: Indica si se pueden evaluar variantes híbridas.
    """

    queries: list[dict[str, Any]]

    semantic_rankings: list[list[str]]

    lexical_rankings: list[list[str]]

    semantic_latencies_ms: list[float]

    lexical_latencies_ms: list[float]

    embedding_build_ms: float

    document_vector_bytes: int

    index_metrics: dict[str, float | int]

    latency_sample_size: int

    includes_lexical: bool = True



def evaluate_runtime(
    runtime: EmbeddingRuntime,
    documents: list[dict[str, Any]],
    queries: list[dict[str, Any]],
    *,
    variant: str,
    semantic_weight: float | None,
    benchmark_store: HnswBenchmarkStore | None = None,
) -> dict[str, Any]:
    """Prepara embeddings y rankings del corpus y calcula las métricas de una única variante.

    Args:
        runtime: Modelo local cargado de forma diferida que produce vectores normalizados.
        documents: Documentos del catálogo con app_id, contenido, huella y metadatos
            utilizados en la evaluación.
        queries: Consultas con positivos, conjunto de relevantes, tipo y partición del
            dataset.
        variant: Nombre de la variante que identifica cada fila del informe.
        semantic_weight: Peso semántico de la fusión RRF; None evalúa exclusivamente el
            ranking semántico.
        benchmark_store: Adaptador para medir HNSW real; None omite esa medición y conserva
            sus métricas en cero.

    Returns:
        calidad, latencia, consumo de memoria y métricas del índice de esa variante.
    """
    prepared = prepare_runtime_evaluation(
        runtime,
        documents,
        queries,
        benchmark_store=benchmark_store,
    )
    return evaluate_prepared_runtime(
        prepared,
        variant=variant,
        semantic_weight=semantic_weight,
    )


def prepare_runtime_evaluation(
    runtime: EmbeddingRuntime,
    documents: list[dict[str, Any]],
    queries: list[dict[str, Any]],
    *,
    benchmark_store: HnswBenchmarkStore | None = None,
    include_lexical: bool = True,
    progress: Callable[[str, int, int], None] | None = None,
) -> PreparedRuntimeEvaluation:
    """Codifica corpus y consultas, calcula rankings exactos y opcionalmente mide HNSW real.
    Mide individualmente hasta 100 consultas y usa su mediana para estimar la codificación de
    las restantes; las latencias no equivalen a tráfico concurrente.

    Args:
        runtime: Modelo local cargado de forma diferida que produce vectores normalizados.
        documents: Documentos del catálogo con app_id, contenido, huella y metadatos
            utilizados en la evaluación.
        queries: Consultas con positivos, conjunto de relevantes, tipo y partición del
            dataset.
        benchmark_store: Adaptador para medir HNSW real; None omite esa medición y conserva
            sus métricas en cero.
        include_lexical: Si es False, evita preparar rankings léxicos y prohíbe evaluar
            variantes híbridas.
        progress: Callback opcional con fase, unidades completadas y total de consultas.

    Returns:
        resultados reutilizables por variantes semánticas e híbridas.
    """
    if progress is not None:
        progress("embedding-documents", 0, len(queries))
    index_started = time.perf_counter()
    document_vectors = np.asarray(
        runtime.encode_documents([document["content"] for document in documents]),
        dtype=np.float32,
    )
    embedding_build_ms = (time.perf_counter() - index_started) * 1000
    app_ids = [document["app_id"] for document in documents]
    semantic_rankings: list[list[str]] = []
    lexical_rankings: list[list[str]] = []
    semantic_latencies_ms: list[float] = []
    lexical_latencies_ms: list[float] = []
    if progress is not None:
        progress("embedding-queries", 0, len(queries))
    query_vectors = np.asarray(
        runtime.encode_queries([query["query"] for query in queries]),
        dtype=np.float32,
    )
    latency_sample_size = min(100, len(queries))
    query_encoding_latencies_ms: list[float] = []
    for query in queries[:latency_sample_size]:
        before = time.perf_counter()
        runtime.encode_query(query["query"])
        query_encoding_latencies_ms.append((time.perf_counter() - before) * 1000)
    fallback_encoding_latency = (
        statistics.median(query_encoding_latencies_ms) if query_encoding_latencies_ms else 0.0
    )
    progress_interval = max(1, len(queries) // 100)
    for query_index, query in enumerate(queries):
        before = time.perf_counter()
        query_vector = query_vectors[query_index]
        scores = document_vectors @ query_vector
        semantic = [
            app_ids[index]
            for index in np.argsort(-scores, kind="stable")[:EVALUATION_CANDIDATE_LIMIT].tolist()
        ]
        ranking_latency = (time.perf_counter() - before) * 1000
        semantic_latencies_ms.append(
            (
                query_encoding_latencies_ms[query_index]
                if query_index < latency_sample_size
                else fallback_encoding_latency
            )
            + ranking_latency
        )
        semantic_rankings.append(semantic)
        if include_lexical:
            before = time.perf_counter()
            lexical = lexical_rank(query["query"], documents)[:EVALUATION_CANDIDATE_LIMIT]
            lexical_latencies_ms.append((time.perf_counter() - before) * 1000)
            lexical_rankings.append(lexical)
        else:
            lexical_latencies_ms.append(0.0)
            lexical_rankings.append([])
        completed = query_index + 1
        if progress is not None and (
            completed == len(queries) or completed % progress_interval == 0
        ):
            progress("ranking", completed, len(queries))
    index_metrics: dict[str, float | int] = {
        "hnswRecallAt20": 0.0,
        "hnswBuildMs": 0.0,
        "hnswIndexBytes": 0,
    }
    if benchmark_store is not None:
        index_metrics = benchmark_store.benchmark_hnsw(
            dimensions=runtime.registered.dimensions,
            app_ids=app_ids,
            document_vectors=document_vectors.tolist(),
            query_vectors=query_vectors.tolist(),
        )
    return PreparedRuntimeEvaluation(
        queries=queries,
        semantic_rankings=semantic_rankings,
        lexical_rankings=lexical_rankings,
        semantic_latencies_ms=semantic_latencies_ms,
        lexical_latencies_ms=lexical_latencies_ms,
        embedding_build_ms=embedding_build_ms,
        document_vector_bytes=int(document_vectors.nbytes),
        index_metrics=index_metrics,
        latency_sample_size=latency_sample_size,
        includes_lexical=include_lexical,
    )


def evaluate_prepared_runtime(
    prepared: PreparedRuntimeEvaluation,
    *,
    variant: str,
    semantic_weight: float | None,
) -> dict[str, Any]:
    """Calcula calidad y coste de una variante usando rankings preparados, añadiendo el coste RRF
    si es híbrida.

    Args:
        prepared: Rankings, latencias y mediciones reutilizables producidas para el mismo
            conjunto de consultas.
        variant: Nombre de la variante que identifica cada fila del informe.
        semantic_weight: Peso semántico de la fusión RRF; None evalúa exclusivamente el
            ranking semántico.

    Returns:
        métricas de calidad, percentiles en ms, QPS agregado y tamaños en bytes.

    Raises:
        RuntimeError: Si se solicita fusión RRF sin haber preparado rankings léxicos.
    """
    rankings: list[tuple[dict[str, Any], list[str]]] = []
    latencies: list[float] = []
    for index, query in enumerate(prepared.queries):
        semantic = prepared.semantic_rankings[index]
        if semantic_weight is None:
            ranked = semantic
            latency = prepared.semantic_latencies_ms[index]
        else:
            if not prepared.includes_lexical:
                raise RuntimeError("lexical_rankings_not_prepared")
            before = time.perf_counter()
            ranked = reciprocal_rank_fusion(
                prepared.lexical_rankings[index],
                semantic,
                semantic_weight=semantic_weight,
            )
            fusion_ms = (time.perf_counter() - before) * 1000
            latency = (
                prepared.semantic_latencies_ms[index]
                + prepared.lexical_latencies_ms[index]
                + fusion_ms
            )
        rankings.append((query, ranked))
        latencies.append(latency)
    elapsed = sum(latencies) / 1000
    relevant = [(row, set(row["relevantAppIds"]), ranked) for row, ranked in rankings]
    navigational = [
        (row, ranked)
        for row, ranked in rankings
        if row["kind"] in {"navigation-name", "navigation-package"}
    ]
    index_metrics = prepared.index_metrics
    hnsw_bytes = int(index_metrics["hnswIndexBytes"])
    return {
        "variant": variant,
        "ndcgAt10": mean(ndcg(ranked, truth, 10) for _, truth, ranked in relevant),
        "mrrAt10": mean(reciprocal_rank(ranked, truth, 10) for _, truth, ranked in relevant),
        "mapAt10": mean(average_precision(ranked, truth, 10) for _, truth, ranked in relevant),
        "recallAt10": mean(recall(ranked, truth, 10) for _, truth, ranked in relevant),
        "recallAt20": mean(recall(ranked, truth, 20) for _, truth, ranked in relevant),
        "exactMrrAt1": mean(
            reciprocal_rank(ranked, {row["positiveAppId"]}, 1) for row, ranked in navigational
        ),
        "p50Ms": statistics.median(latencies) if latencies else 0.0,
        "p95Ms": percentile(latencies, 0.95),
        "p99Ms": percentile(latencies, 0.99),
        "throughputQps": len(prepared.queries) / elapsed if elapsed else 0.0,
        "embeddingBuildMs": prepared.embedding_build_ms,
        "hnswBuildMs": index_metrics["hnswBuildMs"],
        "indexBuildMs": (prepared.embedding_build_ms + float(index_metrics["hnswBuildMs"])),
        "hnswRecallAt20": index_metrics["hnswRecallAt20"],
        "vectorBytes": prepared.document_vector_bytes,
        "hnswIndexBytes": hnsw_bytes,
        "indexBytes": prepared.document_vector_bytes + hnsw_bytes,
        "rssBytes": psutil.Process(os.getpid()).memory_info().rss,
        "vramBytes": accelerator_memory_bytes(),
        "semanticWeight": semantic_weight,
        "latencySampleSize": prepared.latency_sample_size,
    }


def evaluate_lexical(
    documents: list[dict[str, Any]],
    queries: list[dict[str, Any]],
) -> dict[str, Any]:
    """Ejecuta el ranking léxico de referencia y mide calidad y latencia sobre las mismas
    consultas del benchmark.

    Args:
        documents: Documentos del catálogo con app_id, contenido, huella y metadatos
            utilizados en la evaluación.
        queries: Consultas con positivos, conjunto de relevantes, tipo y partición del
            dataset.

    Returns:
        fila de referencia lexical; las métricas de construcción y tamaño vectorial son cero.
    """
    latencies = []
    rankings = []
    started = time.perf_counter()
    for query in queries:
        before = time.perf_counter()
        ranked = lexical_rank(query["query"], documents)
        latencies.append((time.perf_counter() - before) * 1000)
        rankings.append((query, ranked))
    elapsed = time.perf_counter() - started
    truth_rows = [(row, set(row["relevantAppIds"]), ranked) for row, ranked in rankings]
    navigational = [
        (row, ranked)
        for row, ranked in rankings
        if row["kind"] in {"navigation-name", "navigation-package"}
    ]
    return {
        "variant": "lexical",
        "ndcgAt10": mean(ndcg(ranked, truth, 10) for _, truth, ranked in truth_rows),
        "mrrAt10": mean(reciprocal_rank(ranked, truth, 10) for _, truth, ranked in truth_rows),
        "mapAt10": mean(average_precision(ranked, truth, 10) for _, truth, ranked in truth_rows),
        "recallAt10": mean(recall(ranked, truth, 10) for _, truth, ranked in truth_rows),
        "recallAt20": mean(recall(ranked, truth, 20) for _, truth, ranked in truth_rows),
        "exactMrrAt1": mean(
            reciprocal_rank(ranked, {row["positiveAppId"]}, 1) for row, ranked in navigational
        ),
        "p50Ms": statistics.median(latencies) if latencies else 0.0,
        "p95Ms": percentile(latencies, 0.95),
        "p99Ms": percentile(latencies, 0.99),
        "throughputQps": len(queries) / elapsed if elapsed else 0.0,
        "embeddingBuildMs": 0.0,
        "hnswBuildMs": 0.0,
        "indexBuildMs": 0.0,
        "hnswRecallAt20": 0.0,
        "vectorBytes": 0,
        "hnswIndexBytes": 0,
        "indexBytes": 0,
        "rssBytes": psutil.Process(os.getpid()).memory_info().rss,
        "vramBytes": accelerator_memory_bytes(),
        "semanticWeight": None,
    }


def accelerator_memory_bytes() -> int:
    """Consulta memoria asignada por PyTorch al dispositivo CUDA actual cuando está disponible.

    Returns:
        bytes asignados o cero si no hay CUDA o falla la consulta.
    """
    try:
        import torch

        if torch.cuda.is_available():
            return int(torch.cuda.memory_allocated())
    except Exception:
        return 0
    return 0


def inherit_index_metrics(
    target: dict[str, Any],
    source: dict[str, Any],
) -> None:
    """Copia mediciones del índice de validación a la evaluación de confirmación para evitar
    medir de nuevo el mismo artefacto.

    Args:
        target: Fila que recibe los siete campos de construcción, recall y tamaño.
        source: Fila con mediciones previas del mismo índice.
    """
    for key in (
        "embeddingBuildMs",
        "hnswBuildMs",
        "indexBuildMs",
        "hnswRecallAt20",
        "vectorBytes",
        "hnswIndexBytes",
        "indexBytes",
    ):
        target[key] = source[key]


def percentile(values: list[float], quantile: float) -> float:
    """Selecciona el valor ordenado más próximo al cuantil, acotando el índice a la muestra
    disponible.

    Args:
        values: Muestra de latencias u otras medidas numéricas.
        quantile: Fracción del percentil; el índice resultante se limita a los extremos de la
            muestra.

    Returns:
        valor de la muestra o cero si está vacía.
    """
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, round((len(ordered) - 1) * quantile)))
    return ordered[index]


def score_variants(
    metrics: list[dict[str, Any]],
    *,
    lexical_exact: float,
) -> list[dict[str, Any]]:
    """Añade puntuación relativa 70% calidad, 20% latencia y 10% memoria a las filas recibidas.
    Solo declara elegible un modelo entrenado que no empeora navegación léxica y mejora nDCG
    frente a su variante base del mismo peso.

    Args:
        metrics: Métricas por variante, con identificadores, calidad, latencias y tamaño de
            índices.
        lexical_exact: MRR@1 de navegación de la referencia léxica que el candidato no debe
            empeorar.

    Returns:
        la misma lista, modificada con normalizaciones, puntuación y elegibilidad.
    """
    quality_values = [row["ndcgAt10"] for row in metrics]
    inverse_latency = [1.0 / max(row["p95Ms"], 0.001) for row in metrics]
    inverse_memory = [
        1.0
        / max(
            row["rssBytes"] + row["vramBytes"] + row["indexBytes"],
            1,
        )
        for row in metrics
    ]
    for rows, key in (
        (quality_values, "qualityNormalized"),
        (inverse_latency, "latencyNormalized"),
        (inverse_memory, "memoryNormalized"),
    ):
        low, high = min(rows), max(rows)
        for metric, value in zip(metrics, rows, strict=True):
            metric[key] = 1.0 if high == low else (value - low) / (high - low)
    zero_shot_quality = {
        (row["modelKey"], row.get("semanticWeight")): row["ndcgAt10"]
        for row in metrics
        if row.get("stage") == "zero-shot"
    }
    for metric in metrics:
        metric["totalScore"] = (
            0.70 * metric["qualityNormalized"]
            + 0.20 * metric["latencyNormalized"]
            + 0.10 * metric["memoryNormalized"]
        )
        metric["eligible"] = (
            metric.get("stage") == "fine-tuned"
            and metric["exactMrrAt1"] >= lexical_exact
            and metric["ndcgAt10"]
            > zero_shot_quality.get(
                (metric.get("modelKey"), metric.get("semanticWeight")),
                1.0,
            )
        )
    return metrics
