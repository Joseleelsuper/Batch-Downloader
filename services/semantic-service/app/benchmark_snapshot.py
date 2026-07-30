"""Implementa las responsabilidades del módulo `benchmark_snapshot`.
"""
from __future__ import annotations

import hashlib
import json
from collections.abc import Iterable
from pathlib import Path
from typing import Any

from app.trainer import build_query_snapshot, write_snapshot


def evaluation_snapshot(
    documents: list[dict[str, Any]],
    *,
    root: Path,
    seed: int,
) -> tuple[str, Path, list[dict[str, Any]], str]:
    """Ejecuta la operación `evaluation_snapshot`.

    Args:
        documents (list[dict[str, Any]]): Colección de documentos que debe procesarse.
        root (Path): Valor de `root` utilizado por la operación.
        seed (int): Valor de `seed` utilizado por la operación.

    Returns:
        tuple[str, Path, list[dict[str, Any]], str]: Colección de elementos obtenidos por la
            operación.
    """
    catalog_hash = catalog_snapshot_hash(documents)
    datasets_root = root / "datasets"
    if datasets_root.is_dir():
        manifests = sorted(
            datasets_root.glob("*/manifest.json"),
            key=lambda path: path.stat().st_mtime,
            reverse=True,
        )
        for manifest_path in manifests:
            cached = _cached_evaluation_snapshot(
                manifest_path,
                seed=seed,
                document_count=len(documents),
                catalog_hash=catalog_hash,
            )
            if cached is not None:
                dataset_hash, snapshot_dir, queries = cached
                return dataset_hash, snapshot_dir, queries, catalog_hash

    all_queries = build_query_snapshot(documents, seed)
    dataset_hash, snapshot_dir = write_snapshot(
        documents,
        all_queries,
        root=root,
        seed=seed,
    )
    _record_catalog_hash(snapshot_dir, catalog_hash)
    queries = _ordered_evaluation_queries(
        row for row in all_queries if row["split"] != "train"
    )
    if not queries:
        queries = _ordered_evaluation_queries(all_queries)
    return dataset_hash, snapshot_dir, queries, catalog_hash


def _cached_evaluation_snapshot(
    manifest_path: Path,
    *,
    seed: int,
    document_count: int,
    catalog_hash: str,
) -> tuple[str, Path, list[dict[str, Any]]] | None:
    """Ejecuta el paso interno `_cached_evaluation_snapshot`.

    Args:
        manifest_path (Path): Ruta de `manifest` utilizada por la operación.
        seed (int): Valor de `seed` utilizado por la operación.
        document_count (int): Valor de `document_count` utilizado por la operación.
        catalog_hash (str): Valor de `catalog_hash` utilizado por la operación.

    Returns:
        tuple[str, Path, list[dict[str, Any]]] | None: Colección de elementos obtenidos por la
            operación.
    """
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        snapshot_dir = manifest_path.parent
        if (
            int(manifest.get("seed", -1)) != seed
            or int(manifest.get("applications", -1)) != document_count
        ):
            return None
        validation_path = snapshot_dir / "validation.jsonl"
        test_path = snapshot_dir / "test.jsonl"
        documents_path = snapshot_dir / "documents.jsonl"
        if not all(
            path.is_file()
            for path in (validation_path, test_path, documents_path)
        ):
            return None
        cached_catalog_hash = str(manifest.get("catalogSnapshotHash") or "")
        if not cached_catalog_hash:
            cached_catalog_hash = _catalog_hash_from_file(documents_path)
        if cached_catalog_hash != catalog_hash:
            return None
        _record_catalog_hash(snapshot_dir, catalog_hash)
        queries = _ordered_evaluation_queries(
            [
                *_read_json_lines(validation_path),
                *_read_json_lines(test_path),
            ]
        )
        return str(manifest["datasetHash"]), snapshot_dir, queries
    except (KeyError, OSError, TypeError, ValueError, json.JSONDecodeError):
        return None


def _read_json_lines(path: Path) -> list[dict[str, Any]]:
    """Ejecuta el paso interno `_read_json_lines`.

    Args:
        path (Path): Ruta del recurso que debe procesarse.

    Returns:
        list[dict[str, Any]]: Colección de elementos obtenidos por la operación.
    """
    with path.open("r", encoding="utf-8") as source:
        return [json.loads(line) for line in source if line.strip()]


def _ordered_evaluation_queries(
    rows: Iterable[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Ejecuta el paso interno `_ordered_evaluation_queries`.

    Args:
        rows (Iterable[dict[str, Any]]): Valor de `rows` utilizado por la operación.

    Returns:
        list[dict[str, Any]]: Colección de elementos obtenidos por la operación.
    """
    return sorted(
        list(rows),
        key=lambda row: (
            str(row.get("positiveAppId") or ""),
            str(row.get("kind") or ""),
            str(row.get("query") or ""),
        ),
    )


def _catalog_hash_from_file(path: Path) -> str:
    """Ejecuta el paso interno `_catalog_hash_from_file`.

    Args:
        path (Path): Ruta del recurso que debe procesarse.

    Returns:
        str: Resultado producido por la operación.
    """
    return catalog_snapshot_hash(
        [
            {
                "app_id": row["app_id"],
                "content_hash": row["content_hash"],
            }
            for row in _read_json_lines(path)
        ]
    )


def _record_catalog_hash(
    snapshot_dir: Path,
    catalog_hash: str,
) -> None:
    """Ejecuta el paso interno `_record_catalog_hash`.

    Args:
        snapshot_dir (Path): Valor de `snapshot_dir` utilizado por la operación.
        catalog_hash (str): Valor de `catalog_hash` utilizado por la operación.
    """
    manifest_path = snapshot_dir / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("catalogSnapshotHash") == catalog_hash:
        return
    manifest["catalogSnapshotHash"] = catalog_hash
    temporary_path = snapshot_dir / "manifest.json.tmp"
    temporary_path.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    temporary_path.replace(manifest_path)


def catalog_snapshot_hash(documents: list[dict[str, Any]]) -> str:
    """Ejecuta la operación `catalog_snapshot_hash`.

    Args:
        documents (list[dict[str, Any]]): Colección de documentos que debe procesarse.

    Returns:
        str: Resultado producido por la operación.
    """
    payload = "|".join(
        f"{row['app_id']}:{row['content_hash']}"
        for row in sorted(documents, key=lambda value: value["app_id"])
    )
    return hashlib.sha256(payload.encode()).hexdigest()
