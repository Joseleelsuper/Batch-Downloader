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
    with path.open("r", encoding="utf-8") as source:
        return [json.loads(line) for line in source if line.strip()]


def _ordered_evaluation_queries(
    rows: Iterable[dict[str, Any]],
) -> list[dict[str, Any]]:
    return sorted(
        list(rows),
        key=lambda row: (
            str(row.get("positiveAppId") or ""),
            str(row.get("kind") or ""),
            str(row.get("query") or ""),
        ),
    )


def _catalog_hash_from_file(path: Path) -> str:
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
    payload = "|".join(
        f"{row['app_id']}:{row['content_hash']}"
        for row in sorted(documents, key=lambda value: value["app_id"])
    )
    return hashlib.sha256(payload.encode()).hexdigest()
