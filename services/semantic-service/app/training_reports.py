"""Publica la misma ejecución de evaluación como JSON, CSV y tabla Markdown para comparación
reproducible.
"""

from __future__ import annotations

import csv
import json
from pathlib import Path
from typing import Any


def write_reports(
    metrics: list[dict[str, Any]],
    *,
    selected: str | None,
    report_dir: Path,
    run_id: str,
    dataset_hash: str,
    smoke: bool = False,
) -> dict[str, str]:
    """Escribe métricas y candidato seleccionado en tres formatos con el mismo UUID y hash de
    dataset.
    La tabla ordena por puntuación y señala cuándo la ejecución smoke impide seleccionar o
    activar modelos.

    Args:
        metrics: Métricas por variante, con identificadores, calidad, latencias y tamaño de
            índices.
        selected: Versión ganadora seleccionada o None si no hay candidato elegible.
        report_dir: Directorio de salida que se crea si todavía no existe.
        run_id: UUID que identifica los tres informes de la misma ejecución.
        dataset_hash: SHA-256 del snapshot de documentos y consultas evaluados.
        smoke: Si es True, usa un subconjunto determinista y un paso; ningún resultado será
            elegible para selección.

    Returns:
        rutas json, csv y markdown de los archivos escritos.
    """
    report_dir.mkdir(parents=True, exist_ok=True)
    json_path = report_dir / f"{run_id}.json"
    csv_path = report_dir / f"{run_id}.csv"
    markdown_path = report_dir / f"{run_id}.md"
    payload = {
        "runId": run_id,
        "datasetHash": dataset_hash,
        "selectedModelVersion": selected,
        "smoke": smoke,
        "metrics": metrics,
    }
    json_path.write_text(
        json.dumps(payload, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    fieldnames = sorted({key for row in metrics for key in row})
    with csv_path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(metrics)
    markdown = [
        "# Benchmark de búsqueda semántica",
        "",
        f"- Dataset: `{dataset_hash}`",
        f"- Modelo seleccionado: `{selected or 'ninguno; se conserva E5 zero-shot'}`",
        (
            "- Alcance: `smoke`; un paso y subconjunto determinista, sin selección ni activación."
            if smoke
            else "- Alcance: entrenamiento y evaluación completos."
        ),
        "",
        "| Variante | nDCG@10 | MRR@10 | Recall@20 | HNSW@20 | p95 ms | Score | Elegible |",
        "|---|---:|---:|---:|---:|---:|---:|:---:|",
    ]
    for row in sorted(metrics, key=lambda value: value.get("totalScore", 0), reverse=True):
        markdown.append(
            "| {variant} | {ndcg:.4f} | {mrr:.4f} | {recall:.4f} | "
            "{hnsw:.4f} | {p95:.2f} | {score:.4f} | {eligible} |".format(
                variant=row["variant"],
                ndcg=row["ndcgAt10"],
                mrr=row["mrrAt10"],
                recall=row["recallAt20"],
                hnsw=row["hnswRecallAt20"],
                p95=row["p95Ms"],
                score=row.get("totalScore", 0),
                eligible="sí" if row.get("eligible") else "no",
            )
        )
    markdown_path.write_text("\n".join(markdown) + "\n", encoding="utf-8")
    return {
        "json": str(json_path),
        "csv": str(csv_path),
        "markdown": str(markdown_path),
    }
