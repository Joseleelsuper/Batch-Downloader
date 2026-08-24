"""Preparación reproducible de snapshots y negativos de entrenamiento."""

from __future__ import annotations

import hashlib
import heapq
import json
from collections import defaultdict
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from app.evaluation import (
    normalized_tokens,
)

DESCRIPTION_STOPWORDS = {
    "aplicacion",
    "aplicaciones",
    "como",
    "con",
    "desde",
    "esta",
    "este",
    "para",
    "permite",
    "software",
    "the",
    "una",
    "utiliza",
}
"""Constante que define `DESCRIPTION_STOPWORDS`.
"""
EVALUATION_CANDIDATE_LIMIT = 2000
"""Constante que define `EVALUATION_CANDIDATE_LIMIT`.
"""


@dataclass
class NegativeMiningIndex:
    """Representa el componente `NegativeMiningIndex`."""

    documents_by_id: dict[str, dict[str, Any]]
    """Atributo de clase `documents_by_id` de `NegativeMiningIndex`.
    """
    tokens_by_id: dict[str, set[str]]
    """Atributo de clase `tokens_by_id` de `NegativeMiningIndex`.
    """
    aliases_by_id: dict[str, set[str]]
    """Atributo de clase `aliases_by_id` de `NegativeMiningIndex`.
    """
    postings: dict[tuple[str, str], set[str]]
    """Atributo de clase `postings` de `NegativeMiningIndex`.
    """
    fallback_by_split: dict[str, list[str]]
    """Atributo de clase `fallback_by_split` de `NegativeMiningIndex`.
    """


def split_for_app(app_id: str, seed: int) -> str:
    """Ejecuta la operación `split_for_app`.

    Args:
        app_id (str): Identificador de `app` utilizado por la operación.
        seed (int): Valor de `seed` utilizado por la operación.

    Returns:
        str: Resultado producido por la operación.
    """
    bucket = int(hashlib.sha256(f"{seed}:{app_id}".encode()).hexdigest()[:8], 16) % 100
    if bucket < 80:
        return "train"
    if bucket < 90:
        return "validation"
    return "test"


def build_query_snapshot(documents: list[dict[str, Any]], seed: int) -> list[dict[str, Any]]:
    """Construye la operación `query_snapshot`.

    Args:
        documents (list[dict[str, Any]]): Colección de documentos que debe procesarse.
        seed (int): Valor de `seed` utilizado por la operación.

    Returns:
        list[dict[str, Any]]: Colección de elementos obtenidos por la operación.
    """
    split_by_app = {
        document["app_id"]: split_for_app(document["app_id"], seed) for document in documents
    }
    by_tag: dict[tuple[str, str], set[str]] = defaultdict(set)
    by_platform_tag: dict[tuple[str, str, str], set[str]] = defaultdict(set)
    mining_index = build_negative_mining_index(documents, seed)
    rows: list[dict[str, Any]] = []
    for document in documents:
        metadata = document.get("metadata") or {}
        split = split_by_app[document["app_id"]]
        systems = {
            str(value).strip().lower()
            for value in metadata.get("operatingSystems") or []
            if str(value).strip()
        }
        for tag in metadata.get("tags") or []:
            normalized_tag = str(tag).strip().lower()
            by_tag[(split, normalized_tag)].add(document["app_id"])
            for system in systems:
                by_platform_tag[(split, system, normalized_tag)].add(document["app_id"])
    for document in documents:
        metadata = document.get("metadata") or {}
        app_id = document["app_id"]
        split = split_by_app[app_id]
        name = str(metadata.get("name") or "").strip()
        package_id = str(metadata.get("packageId") or "").strip()
        publisher = str(metadata.get("publisher") or "").strip()
        system_names = [str(value) for value in metadata.get("operatingSystems") or []]
        tags = [str(value) for value in metadata.get("tags") or []]
        description = " ".join(
            str(metadata.get(field) or "") for field in ("shortDescription", "longDescription")
        )
        description_terms = [
            token
            for token in normalized_tokens(description)
            if len(token) >= 4 and token not in DESCRIPTION_STOPWORDS
        ]
        candidates: list[tuple[str, set[str], str]] = []
        if name:
            candidates.append((name, {app_id}, "navigation-name"))
        if package_id:
            candidates.append((package_id, {app_id}, "navigation-package"))
        if publisher and name:
            candidates.append((f"{publisher} {name}", {app_id}, "publisher"))
        if tags:
            intent = " ".join(tags[:3])
            positives = set().union(*(by_tag[(split, tag.strip().lower())] for tag in tags[:2]))
            candidates.append((f"aplicación de {intent}", positives or {app_id}, "intent"))
        if description_terms:
            candidates.append(
                (
                    " ".join(dict.fromkeys(description_terms))[:160],
                    {app_id},
                    "description-intent",
                )
            )
        if system_names and tags:
            positives = by_platform_tag[
                (
                    split,
                    system_names[0].strip().lower(),
                    tags[0].strip().lower(),
                )
            ]
            candidates.append(
                (
                    f"{tags[0]} para {system_names[0]}",
                    positives or {app_id},
                    "platform-intent",
                )
            )
        for query, positives, kind in candidates:
            rows.append(
                {
                    "query": query,
                    "positiveAppId": app_id,
                    "relevantAppIds": sorted(positives),
                    "positive": document["content"],
                    "positiveAliases": sorted(
                        {
                            alias
                            for value in (name, package_id)
                            if (alias := " ".join(normalized_tokens(value)))
                        }
                    ),
                    "split": split,
                    "kind": kind,
                }
            )
    documents_by_id = {document["app_id"]: document for document in documents}
    for row in rows:
        negatives = mine_hard_negatives(
            row,
            documents,
            seed=seed,
            mining_index=mining_index,
        )
        row["hardNegativeAppIds"] = [item["app_id"] for item in negatives]
        row["hardNegatives"] = [item["content"] for item in negatives]
        positive = documents_by_id[row["positiveAppId"]]
        row["positiveContentHash"] = positive.get("content_hash")
    return rows


def build_negative_mining_index(
    documents: list[dict[str, Any]],
    seed: int,
) -> NegativeMiningIndex:
    """Construye la operación `negative_mining_index`.

    Args:
        documents (list[dict[str, Any]]): Colección de documentos que debe procesarse.
        seed (int): Valor de `seed` utilizado por la operación.

    Returns:
        NegativeMiningIndex: Resultado de `build_negative_mining_index`.
    """
    documents_by_id = {document["app_id"]: document for document in documents}
    tokens_by_id: dict[str, set[str]] = {}
    aliases_by_id: dict[str, set[str]] = {}
    postings: dict[tuple[str, str], set[str]] = defaultdict(set)
    fallback_by_split: dict[str, list[str]] = defaultdict(list)
    for document in documents:
        app_id = document["app_id"]
        split = split_for_app(app_id, seed)
        metadata = document.get("metadata") or {}
        tokens = set(
            normalized_tokens(
                " ".join(
                    [
                        str(document.get("content") or ""),
                        str(metadata.get("publisher") or ""),
                        " ".join(str(tag) for tag in metadata.get("tags") or []),
                    ]
                )
            )
        )
        aliases = {
            alias
            for field in ("name", "packageId")
            if (alias := " ".join(normalized_tokens(str(metadata.get(field) or ""))))
        }
        tokens_by_id[app_id] = tokens
        aliases_by_id[app_id] = aliases
        fallback_by_split[split].append(app_id)
        for token in tokens:
            postings[(split, token)].add(app_id)
    for split, app_ids in fallback_by_split.items():
        app_ids.sort(
            key=lambda app_id: hashlib.sha256(f"{seed}:{split}:{app_id}".encode()).hexdigest()
        )
    return NegativeMiningIndex(
        documents_by_id=documents_by_id,
        tokens_by_id=tokens_by_id,
        aliases_by_id=aliases_by_id,
        postings=postings,
        fallback_by_split=dict(fallback_by_split),
    )


def mine_hard_negatives(
    query_row: dict[str, Any],
    documents: list[dict[str, Any]],
    *,
    seed: int,
    limit: int = 3,
    mining_index: NegativeMiningIndex | None = None,
) -> list[dict[str, Any]]:
    """Ejecuta la operación `mine_hard_negatives`.

    Args:
        query_row (dict[str, Any]): Valor de `query_row` utilizado por la operación.
        documents (list[dict[str, Any]]): Colección de documentos que debe procesarse.
        seed (int): Valor de `seed` utilizado por la operación.
        limit (int): Número máximo de elementos que se recuperarán.
        mining_index (NegativeMiningIndex | None): Valor de `mining_index` utilizado por la
            operación.

    Returns:
        list[dict[str, Any]]: Colección de elementos obtenidos por la operación.
    """
    query = str(query_row["query"])
    query_tokens = set(normalized_tokens(query))
    relevant = set(query_row["relevantAppIds"])
    positive_aliases = set(query_row.get("positiveAliases") or [])
    split = query_row["split"]
    normalized_query = " ".join(normalized_tokens(query))
    index = mining_index or build_negative_mining_index(documents, seed)
    overlap_by_id: dict[str, int] = defaultdict(int)
    for token in query_tokens:
        for app_id in index.postings.get((split, token), set()):
            overlap_by_id[app_id] += 1
    candidate_ids = set(overlap_by_id)
    fallback_target = max(32, limit * 8)
    if len(candidate_ids) < fallback_target:
        for app_id in index.fallback_by_split.get(split, []):
            candidate_ids.add(app_id)
            if len(candidate_ids) >= fallback_target:
                break
    candidates: list[tuple[float, str, dict[str, Any]]] = []
    for app_id in candidate_ids:
        if app_id in relevant:
            continue
        aliases = index.aliases_by_id[app_id]
        if normalized_query and normalized_query in aliases or aliases & positive_aliases:
            continue
        candidate_tokens = index.tokens_by_id[app_id]
        overlap = overlap_by_id.get(app_id, 0)
        union = len(query_tokens | candidate_tokens)
        lexical_score = overlap * 100.0 + (overlap / union if union else 0.0)
        deterministic_tie = hashlib.sha256(f"{seed}:{query}:{app_id}".encode()).hexdigest()
        candidates.append(
            (
                lexical_score,
                deterministic_tie,
                index.documents_by_id[app_id],
            )
        )
    hardest = heapq.nsmallest(
        limit,
        candidates,
        key=lambda item: (-item[0], item[1]),
    )
    return [document for _, _, document in hardest]


def write_snapshot(
    documents: list[dict[str, Any]],
    queries: list[dict[str, Any]],
    *,
    root: Path,
    seed: int,
) -> tuple[str, Path]:
    """Ejecuta la operación `write_snapshot`.

    Args:
        documents (list[dict[str, Any]]): Colección de documentos que debe procesarse.
        queries (list[dict[str, Any]]): Valor de `queries` utilizado por la operación.
        root (Path): Valor de `root` utilizado por la operación.
        seed (int): Valor de `seed` utilizado por la operación.

    Returns:
        tuple[str, Path]: Resultado producido por la operación.
    """
    digest = hashlib.sha256()
    digest.update(f"seed:{seed}\n".encode())
    for record_type, records in (("document", documents), ("query", queries)):
        for record in records:
            digest.update(record_type.encode())
            digest.update(b":")
            digest.update(
                json.dumps(
                    record,
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                    default=str,
                ).encode()
            )
            digest.update(b"\n")
    dataset_hash = digest.hexdigest()
    snapshot_dir = root / "datasets" / dataset_hash
    snapshot_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = snapshot_dir / "manifest.json"
    if manifest_path.exists():
        return dataset_hash, snapshot_dir
    with (snapshot_dir / "documents.jsonl").open(
        "w",
        encoding="utf-8",
    ) as output:
        for document in documents:
            output.write(json.dumps(document, ensure_ascii=False, default=str) + "\n")
    for split in ("train", "validation", "test"):
        with (snapshot_dir / f"{split}.jsonl").open("w", encoding="utf-8") as output:
            for row in queries:
                if row["split"] == split:
                    output.write(json.dumps(row, ensure_ascii=False) + "\n")
    manifest_path.write_text(
        json.dumps(
            {
                "datasetHash": dataset_hash,
                "seed": seed,
                "applications": len(documents),
                "queries": len(queries),
                "createdAt": datetime.now(UTC).isoformat(),
            },
            indent=2,
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    return dataset_hash, snapshot_dir
