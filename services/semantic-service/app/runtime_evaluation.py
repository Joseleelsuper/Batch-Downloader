"""Evaluación semántica y lexical preparada para runtime y benchmarks."""

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
    """Representa el componente `PreparedRuntimeEvaluation`."""

    queries: list[dict[str, Any]]
    """Atributo de clase `queries` de `PreparedRuntimeEvaluation`.
    """
    semantic_rankings: list[list[str]]
    """Atributo de clase `semantic_rankings` de `PreparedRuntimeEvaluation`.
    """
    lexical_rankings: list[list[str]]
    """Atributo de clase `lexical_rankings` de `PreparedRuntimeEvaluation`.
    """
    semantic_latencies_ms: list[float]
    """Atributo de clase `semantic_latencies_ms` de `PreparedRuntimeEvaluation`.
    """
    lexical_latencies_ms: list[float]
    """Atributo de clase `lexical_latencies_ms` de `PreparedRuntimeEvaluation`.
    """
    embedding_build_ms: float
    """Atributo de clase `embedding_build_ms` de `PreparedRuntimeEvaluation`.
    """
    document_vector_bytes: int
    """Atributo de clase `document_vector_bytes` de `PreparedRuntimeEvaluation`.
    """
    index_metrics: dict[str, float | int]
    """Atributo de clase `index_metrics` de `PreparedRuntimeEvaluation`.
    """
    latency_sample_size: int
    """Atributo de clase `latency_sample_size` de `PreparedRuntimeEvaluation`.
    """
    includes_lexical: bool = True
    """Atributo de clase `includes_lexical` de `PreparedRuntimeEvaluation`.
    """


def evaluate_runtime(
    runtime: EmbeddingRuntime,
    documents: list[dict[str, Any]],
    queries: list[dict[str, Any]],
    *,
    variant: str,
    semantic_weight: float | None,
    benchmark_store: HnswBenchmarkStore | None = None,
) -> dict[str, Any]:
    """Ejecuta la operación `evaluate_runtime`.

    Args:
        runtime (EmbeddingRuntime): Valor de `runtime` utilizado por la operación.
        documents (list[dict[str, Any]]): Colección de documentos que debe procesarse.
        queries (list[dict[str, Any]]): Valor de `queries` utilizado por la operación.
        variant (str): Valor de `variant` utilizado por la operación.
        semantic_weight (float | None): Valor de `semantic_weight` utilizado por la operación.
        benchmark_store (HnswBenchmarkStore | None): Valor de `benchmark_store` utilizado por la
            operación.

    Returns:
        dict[str, Any]: Mapa con los datos producidos por la operación.
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
    """Ejecuta la operación `prepare_runtime_evaluation`.

    Args:
        runtime (EmbeddingRuntime): Valor de `runtime` utilizado por la operación.
        documents (list[dict[str, Any]]): Colección de documentos que debe procesarse.
        queries (list[dict[str, Any]]): Valor de `queries` utilizado por la operación.
        benchmark_store (HnswBenchmarkStore | None): Valor de `benchmark_store` utilizado por la
            operación.
        include_lexical (bool): Valor de `include_lexical` utilizado por la operación.
        progress (Callable[[str, int, int], None] | None): Valor de `progress` utilizado por la
            operación.

    Returns:
        PreparedRuntimeEvaluation: Resultado producido por la operación.
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
    """Ejecuta la operación `evaluate_prepared_runtime`.

    Args:
        prepared (PreparedRuntimeEvaluation): Valor de `prepared` utilizado por la operación.
        variant (str): Valor de `variant` utilizado por la operación.
        semantic_weight (float | None): Valor de `semantic_weight` utilizado por la operación.

    Returns:
        dict[str, Any]: Mapa con los datos producidos por la operación.

    Throws:
        RuntimeError: Si el estado de ejecución impide completar la operación.
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
    """Ejecuta la operación `evaluate_lexical`.

    Args:
        documents (list[dict[str, Any]]): Colección de documentos que debe procesarse.
        queries (list[dict[str, Any]]): Valor de `queries` utilizado por la operación.

    Returns:
        dict[str, Any]: Mapa con los datos producidos por la operación.
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
    """Ejecuta la operación `accelerator_memory_bytes`.

    Returns:
        int: Resultado producido por la operación.
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
    """Ejecuta la operación `inherit_index_metrics`.

    Args:
        target (dict[str, Any]): Valor de `target` utilizado por la operación.
        source (dict[str, Any]): Fuente de descarga sobre la que se actúa.
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
    """Ejecuta la operación `percentile`.

    Args:
        values (list[float]): Valor de `values` utilizado por la operación.
        quantile (float): Valor de `quantile` utilizado por la operación.

    Returns:
        float: Resultado producido por la operación.
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
    """Ejecuta la operación `score_variants`.

    Args:
        metrics (list[dict[str, Any]]): Valor de `metrics` utilizado por la operación.
        lexical_exact (float): Valor de `lexical_exact` utilizado por la operación.

    Returns:
        list[dict[str, Any]]: Colección de elementos obtenidos por la operación.
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
