"""Persistencia de informes producidos por el entrenamiento semántico."""

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
    """Ejecuta la operación `write_reports`.

    Args:
        metrics (list[dict[str, Any]]): Valor de `metrics` utilizado por la operación.
        selected (str | None): Valor de `selected` utilizado por la operación.
        report_dir (Path): Valor de `report_dir` utilizado por la operación.
        run_id (str): Identificador de `run` utilizado por la operación.
        dataset_hash (str): Valor de `dataset_hash` utilizado por la operación.
        smoke (bool): Valor de `smoke` utilizado por la operación.

    Returns:
        dict[str, str]: Mapa con los datos producidos por la operación.
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
