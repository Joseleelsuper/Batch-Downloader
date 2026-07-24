from __future__ import annotations

import math
import re
from collections.abc import Iterable


def reciprocal_rank(ranked: list[str], relevant: set[str], cutoff: int) -> float:
    for index, app_id in enumerate(ranked[:cutoff], start=1):
        if app_id in relevant:
            return 1.0 / index
    return 0.0


def average_precision(ranked: list[str], relevant: set[str], cutoff: int) -> float:
    if not relevant:
        return 0.0
    hits = 0
    precision_sum = 0.0
    for index, app_id in enumerate(ranked[:cutoff], start=1):
        if app_id not in relevant:
            continue
        hits += 1
        precision_sum += hits / index
    return precision_sum / min(len(relevant), cutoff)


def recall(ranked: list[str], relevant: set[str], cutoff: int) -> float:
    if not relevant:
        return 0.0
    return len(set(ranked[:cutoff]) & relevant) / len(relevant)


def ndcg(ranked: list[str], relevant: set[str], cutoff: int) -> float:
    dcg = sum(
        1.0 / math.log2(index + 2)
        for index, app_id in enumerate(ranked[:cutoff])
        if app_id in relevant
    )
    ideal = sum(
        1.0 / math.log2(index + 2)
        for index in range(min(len(relevant), cutoff))
    )
    return dcg / ideal if ideal else 0.0


def mean(values: Iterable[float]) -> float:
    materialized = list(values)
    return sum(materialized) / len(materialized) if materialized else 0.0


def lexical_rank(query: str, documents: list[dict]) -> list[str]:
    tokens = normalized_tokens(query)

    def score(document: dict) -> tuple[float, str]:
        metadata = document.get("metadata") or {}
        name = str(metadata.get("name") or "").lower()
        package_id = str(metadata.get("packageId") or "").lower()
        publisher = str(metadata.get("publisher") or "").lower()
        content = str(document.get("content") or "").lower()
        normalized_query = " ".join(tokens)
        value = 0.0
        if name == normalized_query:
            value += 10000
        if package_id == query.lower().strip():
            value += 10000
        if normalized_query and name.startswith(normalized_query):
            value += 9000
        value += sum(100 for token in tokens if token in name)
        value += sum(40 for token in tokens if token in publisher)
        value += sum(10 for token in tokens if token in content)
        return value, document["app_id"]

    scored = [score(document) for document in documents]
    return [
        app_id
        for value, app_id in sorted(scored, key=lambda item: (-item[0], item[1]))
        if value > 0
    ]


def reciprocal_rank_fusion(
    lexical: list[str],
    semantic: list[str],
    *,
    semantic_weight: float,
    k: int = 60,
) -> list[str]:
    scores: dict[str, float] = {}
    for rank, app_id in enumerate(lexical, start=1):
        scores[app_id] = scores.get(app_id, 0.0) + 1.0 / (k + rank)
    for rank, app_id in enumerate(semantic, start=1):
        scores[app_id] = scores.get(app_id, 0.0) + semantic_weight / (k + rank)
    return sorted(scores, key=lambda app_id: (-scores[app_id], app_id))


def normalized_tokens(value: str) -> list[str]:
    return [
        token
        for token in re.sub(r"[^\w]+", " ", value.lower(), flags=re.UNICODE).split()
        if len(token) >= 2
    ]
