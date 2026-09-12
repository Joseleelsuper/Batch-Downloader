"""Reutiliza snapshots de evaluación solo cuando coinciden semilla, tamaño y huella del catálogo
activo.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Iterable
from pathlib import Path
from typing import Any

from app.training_dataset import build_query_snapshot, write_snapshot


def evaluation_snapshot(
    documents: list[dict[str, Any]],
    *,
    root: Path,
    seed: int,
) -> tuple[str, Path, list[dict[str, Any]], str]:
    """Busca el snapshot compatible más reciente o genera consultas nuevas y registra su huella
    del catálogo.
    Usa validación y prueba; si el conjunto generado carece de ambas, recurre a todas sus
    consultas.

    Args:
        documents: Corpus activo con UUID, hash de contenido y metadatos, común a todos los
            modelos comparados.
        root: Directorio que contiene datasets/<hash> para reutilizar snapshots compatibles.
        seed: Semilla de particiones y selección del conjunto de consultas.

    Returns:
        hash del dataset, directorio, consultas ordenadas y huella del catálogo.
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
    queries = _ordered_evaluation_queries(row for row in all_queries if row["split"] != "train")
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
    """Verifica manifiesto, archivos de partición y huella antes de recuperar un snapshot
    existente.
    Los manifiestos históricos sin huella se completan a partir de sus documentos; los errores
    de lectura invalidan la caché.

    Args:
        manifest_path: Ruta del manifiesto candidato a reutilizar.
        seed: Semilla de particiones y selección del conjunto de consultas.
        document_count: Número actual de documentos que debe coincidir con el manifiesto.
        catalog_hash: Huella actual de UUID y hashes del contenido ordenados por identidad.

    Returns:
        dataset, directorio y consultas compatibles o None si falta información, difiere o no
            puede leerse.
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
        if not all(path.is_file() for path in (validation_path, test_path, documents_path)):
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
    except KeyError, OSError, TypeError, ValueError, json.JSONDecodeError:
        return None


def _read_json_lines(path: Path) -> list[dict[str, Any]]:
    """Carga objetos JSON de las líneas no vacías de un archivo UTF-8.

    Args:
        path: Archivo JSONL o directorio local que se inspecciona según el contrato del
            método.

    Returns:
        registros en su orden de escritura; propaga errores de lectura o JSON.
    """
    with path.open("r", encoding="utf-8") as source:
        return [json.loads(line) for line in source if line.strip()]


def _ordered_evaluation_queries(
    rows: Iterable[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Ordena consultas por aplicación positiva, tipo y texto para repetir la evaluación en el
    mismo orden.

    Args:
        rows: Consultas del conjunto de evaluación, posiblemente recibidas como generador.

    Returns:
        lista materializada con orden estable.
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
    """Calcula la huella del catálogo utilizando únicamente identidad y hash de cada documento
    almacenado.

    Args:
        path: Archivo JSONL o directorio local que se inspecciona según el contrato del
            método.

    Returns:
        SHA-256 compatible con el catálogo actual.
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
    """Completa la huella de catálogo en el manifiesto mediante archivo temporal y reemplazo; si
    ya coincide conserva el archivo.

    Args:
        snapshot_dir: Directorio del snapshot cuyo manifiesto se completa con la huella del
            catálogo.
        catalog_hash: Huella actual de UUID y hashes del contenido ordenados por identidad.
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
    """Calcula SHA-256 de UUID y hash de contenido en orden de aplicación para detectar cambios
    del corpus.

    Args:
        documents: Corpus activo con UUID, hash de contenido y metadatos, común a todos los
            modelos comparados.

    Returns:
        huella compatible con la CTE SQL del catálogo, incluido el corpus vacío.

    See Also:
        app.catalog_fingerprint.CATALOG_SNAPSHOT_CTE: Equivalente SQL utilizado dentro de
            transacciones.
    """
    payload = "|".join(
        f"{row['app_id']}:{row['content_hash']}"
        for row in sorted(documents, key=lambda value: value["app_id"])
    )
    return hashlib.sha256(payload.encode()).hexdigest()
