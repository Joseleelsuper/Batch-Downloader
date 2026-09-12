"""Compara modelos locales sin entrenarlos y persiste evidencia reproducible para decidir una
activación administrativa.
"""

from __future__ import annotations

import csv
import gc
import hashlib
import json
import platform
import time
import uuid
from collections.abc import Callable
from pathlib import Path
from typing import Any

import torch

from app.admin_store import SemanticAdminStore
from app.benchmark_snapshot import evaluation_snapshot
from app.config import get_settings
from app.database import Database
from app.embeddings import EmbeddingRuntime
from app.runtime_evaluation import (
    evaluate_prepared_runtime,
    prepare_runtime_evaluation,
)


def run_admin_benchmark(
    *,
    operation_id: str,
    model_ids: list[str],
) -> dict[str, Any]:
    """Evalúa todos los modelos ready con el mismo catálogo y consultas, midiendo carga,
    calentamiento, ranking e índice HNSW.
    Publica informes y evidencia con huellas de catálogo, configuración y entorno; comprueba
    cancelación entre modelos.

    Args:
        operation_id: UUID del trabajo administrativo al que se vinculan progreso y resultado.
        model_ids: UUID de los artefactos ready que se comparan en el orden indicado.

    Returns:
        UUID, dataset, modelos comparados y rutas de informes.

    Raises:
        RuntimeError: Si hay menos de dos documentos, no hay consultas o un artefacto no está
            listo.
        InterruptedError: Si se solicita cancelar antes del siguiente modelo.
    """
    settings = get_settings()
    database = Database(settings)
    database.open()
    database.verify_schema()
    admin = SemanticAdminStore(database)
    from app.store import SemanticStore

    semantic = SemanticStore(database)
    try:
        documents = semantic.active_documents()
        if len(documents) < 2:
            raise RuntimeError("semantic_benchmark_requires_two_documents")
        admin.operations.update_operation(
            operation_id,
            phase="benchmarking",
            message="Preparando el dataset reproducible",
        )
        dataset_hash, snapshot_dir, queries, catalog_snapshot_hash = evaluation_snapshot(
            documents,
            root=Path(settings.model_cache_dir),
            seed=settings.trainer_seed,
        )
        if not queries:
            raise RuntimeError("semantic_benchmark_queries_required")
        metrics: list[dict[str, Any]] = []
        model_configurations: dict[str, dict[str, Any]] = {}
        total = len(model_ids)
        for index, model_id in enumerate(model_ids, start=1):
            if admin.operations.cancel_requested(operation_id):
                raise InterruptedError("semantic_operation_cancelled")
            completed_before_model = (index - 1) * len(queries)
            total_query_work = total * len(queries)
            admin.operations.update_operation(
                operation_id,
                phase="benchmarking",
                current=completed_before_model,
                total=total_query_work,
                unit="queries",
                message=f"Cargando el modelo {index} de {total}",
            )
            artifact = admin.models.artifact(model_id)
            model_version = artifact.get("model_version")
            if not model_version or artifact["artifact_state"] != "ready":
                raise RuntimeError("semantic_model_not_ready")
            runtime = EmbeddingRuntime(
                semantic.model(model_version),
                device=settings.device,
                cache_dir=settings.model_cache_dir,
                batch_size=settings.index_batch_size,
            )
            started = time.perf_counter()
            runtime.load()
            load_ms = (time.perf_counter() - started) * 1000
            started = time.perf_counter()
            runtime.warmup()
            warmup_ms = (time.perf_counter() - started) * 1000
            prepared = prepare_runtime_evaluation(
                runtime,
                documents,
                queries,
                benchmark_store=semantic.benchmarks,
                include_lexical=False,
                progress=_benchmark_progress_callback(
                    admin=admin,
                    operation_id=operation_id,
                    completed_before_model=completed_before_model,
                    total_query_work=total_query_work,
                    model_index=index,
                    model_total=total,
                ),
            )
            metric = evaluate_prepared_runtime(
                prepared,
                variant=f"{artifact['hf_repository']}:semantic",
                semantic_weight=None,
            )
            metric.update(
                {
                    "modelId": model_id,
                    "modelKey": artifact["model_key"],
                    "modelVersion": model_version,
                    "repository": artifact["hf_repository"],
                    "stage": "base",
                    "scope": "full",
                    "eligible": True,
                    "minimumSimilarity": float(artifact["minimum_similarity"]),
                    "loadMs": load_ms,
                    "warmupMs": warmup_ms,
                    "dimensions": int(artifact["dimensions"]),
                    "artifactBytes": int(artifact["artifact_bytes"] or 0),
                }
            )
            metrics.append(metric)
            model_configurations[model_id] = {
                "repository": artifact["hf_repository"],
                "revision": artifact["resolved_revision"],
                "queryPrefix": artifact["query_prefix"],
                "passagePrefix": artifact["passage_prefix"],
                "minimumSimilarity": float(artifact["minimum_similarity"]),
            }
            admin.operations.update_operation(
                operation_id,
                phase="benchmarking",
                current=index * len(queries),
                total=total_query_work,
                unit="queries",
                message=f"Evaluado {index} de {total}",
            )
            del prepared, runtime
            gc.collect()
        _score(metrics)
        run_id = str(uuid.uuid4())
        hardware = _hardware()
        hardware_fingerprint = hashlib.sha256(
            json.dumps(hardware, sort_keys=True).encode()
        ).hexdigest()
        paths = _write_reports(
            metrics,
            report_dir=Path(settings.reports_dir),
            run_id=run_id,
            dataset_hash=dataset_hash,
            hardware=hardware,
        )
        admin.lifecycle.save_benchmark_run(
            run_id=run_id,
            operation_id=operation_id,
            model_ids=model_ids,
            dataset_hash=dataset_hash,
            seed=settings.trainer_seed,
            configuration={
                "snapshotDirectory": str(snapshot_dir),
                "catalogSnapshotHash": catalog_snapshot_hash,
                "modelConfigurations": model_configurations,
                "weights": {"quality": 0.7, "latency": 0.2, "memory": 0.1},
                "runtime": "semantic",
                "hardware": hardware,
            },
            metrics=metrics,
            hardware_fingerprint=hardware_fingerprint,
            document_count=len(documents),
            query_count=len(queries),
            paths=paths,
        )
        return {
            "runId": run_id,
            "datasetHash": dataset_hash,
            "modelIds": model_ids,
            "reports": paths,
        }
    finally:
        database.close()


def _benchmark_progress_callback(
    *,
    admin: SemanticAdminStore,
    operation_id: str,
    completed_before_model: int,
    total_query_work: int,
    model_index: int,
    model_total: int,
) -> Callable[[str, int, int], None]:
    """Adapta el progreso local de un modelo al contador global de consultas de la operación.

    Args:
        admin: Composición de almacenes administrativos para actualizar el trabajo en curso.
        operation_id: UUID del trabajo administrativo al que se vinculan progreso y resultado.
        completed_before_model: Consultas ya evaluadas por modelos anteriores de esta misma
            ejecución.
        total_query_work: Total de consultas multiplicado por el número de modelos comparados.
        model_index: Posición del modelo actual, empezando en uno.
        model_total: Número total de modelos que se comparan.

    Returns:
        callback que suma trabajo previo y publica un mensaje seguro de fase.
    """

    def update(stage: str, current: int, stage_total: int) -> None:
        """Actualiza progreso global y mensaje manteniendo la operación en fase benchmarking.

        Args:
            stage: Fase de preparación o ranking informada por la evaluación del runtime.
            current: Consultas completadas dentro de la fase del modelo actual.
            stage_total: Consultas previstas en la etapa comunicada por el runtime.
        """
        admin.operations.update_operation(
            operation_id,
            phase="benchmarking",
            current=completed_before_model + current,
            total=total_query_work,
            unit="queries",
            message=(
                f"Evaluando el modelo {model_index} de {model_total}: "
                f"{_progress_message(stage, current, stage_total)}"
            ),
        )

    return update


def _progress_message(stage: str, current: int, total: int) -> str:
    """Convierte las fases de embeddings y ranking en una descripción legible para
    administración.

    Args:
        stage: Fase de preparación o ranking informada por la evaluación del runtime.
        current: Consultas completadas dentro de la fase del modelo actual.
        total: Consultas previstas para la fase actual.

    Returns:
        mensaje de preparación o contador de consultas ordenadas.
    """
    if stage == "embedding-documents":
        return "creando los embeddings del catálogo"
    if stage == "embedding-queries":
        return "creando los embeddings de las consultas"
    return f"ordenando consultas {current} de {total}"


def _score(metrics: list[dict[str, Any]]) -> None:
    """Normaliza calidad, latencia y memoria dentro de la comparación y añade puntuación
    70/20/10; marca como recomendado el primer máximo.

    Args:
        metrics: Filas de calidad, latencias y tamaños por modelo; deben contener al menos una
            variante.
    """
    dimensions = (
        ([metric["ndcgAt10"] for metric in metrics], "qualityNormalized", False),
        ([metric["p95Ms"] for metric in metrics], "latencyNormalized", True),
        (
            [metric["rssBytes"] + metric["vramBytes"] + metric["indexBytes"] for metric in metrics],
            "memoryNormalized",
            True,
        ),
    )
    for values, key, inverse in dimensions:
        low, high = min(values), max(values)
        for metric, value in zip(metrics, values, strict=True):
            normalized = 1.0 if high == low else (value - low) / (high - low)
            metric[key] = 1.0 - normalized if inverse and high != low else normalized
    for metric in metrics:
        metric["totalScore"] = (
            0.70 * metric["qualityNormalized"]
            + 0.20 * metric["latencyNormalized"]
            + 0.10 * metric["memoryNormalized"]
        )
    winner = max(metrics, key=lambda row: row["totalScore"])
    for metric in metrics:
        metric["recommended"] = metric is winner


def _hardware() -> dict[str, Any]:
    """Describe el entorno de ejecución que acompaña a las métricas para comparar resultados en
    condiciones equivalentes.

    Returns:
        plataforma, procesador, versiones de Python y PyTorch, dispositivo y nombre CUDA si
            existe.
    """
    return {
        "platform": platform.platform(),
        "processor": platform.processor(),
        "python": platform.python_version(),
        "torch": torch.__version__,
        "device": get_settings().device,
        "cuda": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
    }


def _write_reports(
    metrics: list[dict[str, Any]],
    *,
    report_dir: Path,
    run_id: str,
    dataset_hash: str,
    hardware: dict[str, Any],
) -> dict[str, str]:
    """Escribe la comparación completa en JSON, CSV y Markdown con las mismas métricas y huella
    del dataset.

    Args:
        metrics: Filas de calidad, latencias y tamaños por modelo; deben contener al menos una
            variante.
        report_dir: Directorio de los informes que se crea si todavía no existe.
        run_id: UUID que vincula los tres formatos de informe a la misma evaluación.
        dataset_hash: Huella del snapshot de documentos y consultas.
        hardware: Plataforma, versiones y dispositivo con los que se obtuvieron las
            mediciones.

    Returns:
        rutas de los tres informes; la tabla se ordena por puntuación descendente.
    """
    report_dir.mkdir(parents=True, exist_ok=True)
    json_path = report_dir / f"{run_id}.json"
    csv_path = report_dir / f"{run_id}.csv"
    markdown_path = report_dir / f"{run_id}.md"
    json_path.write_text(
        json.dumps(
            {
                "runId": run_id,
                "datasetHash": dataset_hash,
                "scope": "full",
                "hardware": hardware,
                "metrics": metrics,
            },
            indent=2,
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    fields = sorted({key for metric in metrics for key in metric})
    with csv_path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=fields)
        writer.writeheader()
        writer.writerows(metrics)
    lines = [
        "# Comparativa de modelos semánticos",
        "",
        f"- Dataset: `{dataset_hash}`",
        "- Alcance: `full`",
        "",
        "| Modelo | nDCG@10 | MRR@10 | Recall@20 | p95 ms | Score |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for metric in sorted(metrics, key=lambda row: row["totalScore"], reverse=True):
        lines.append(
            "| {repository} | {ndcg:.4f} | {mrr:.4f} | {recall:.4f} | "
            "{p95:.2f} | {score:.4f} |".format(
                repository=metric["repository"],
                ndcg=metric["ndcgAt10"],
                mrr=metric["mrrAt10"],
                recall=metric["recallAt20"],
                p95=metric["p95Ms"],
                score=metric["totalScore"],
            )
        )
    markdown_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return {
        "json": str(json_path),
        "csv": str(csv_path),
        "markdown": str(markdown_path),
    }
