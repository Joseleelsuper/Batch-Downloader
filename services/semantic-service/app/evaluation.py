"""Implementa las responsabilidades del módulo `evaluation`.
"""
from __future__ import annotations

import math
import re
from collections.abc import Iterable


def reciprocal_rank(ranked: list[str], relevant: set[str], cutoff: int) -> float:
    """Ejecuta la operación `reciprocal_rank`.

    Args:
        ranked (list[str]): Valor de `ranked` utilizado por la operación.
        relevant (set[str]): Valor de `relevant` utilizado por la operación.
        cutoff (int): Valor de `cutoff` utilizado por la operación.

    Returns:
        float: Resultado producido por la operación.
    """
    for index, app_id in enumerate(ranked[:cutoff], start=1):
        if app_id in relevant:
            return 1.0 / index
    return 0.0


def average_precision(ranked: list[str], relevant: set[str], cutoff: int) -> float:
    """Ejecuta la operación `average_precision`.

    Args:
        ranked (list[str]): Valor de `ranked` utilizado por la operación.
        relevant (set[str]): Valor de `relevant` utilizado por la operación.
        cutoff (int): Valor de `cutoff` utilizado por la operación.

    Returns:
        float: Resultado producido por la operación.
    """
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
    """Ejecuta la operación `recall`.

    Args:
        ranked (list[str]): Valor de `ranked` utilizado por la operación.
        relevant (set[str]): Valor de `relevant` utilizado por la operación.
        cutoff (int): Valor de `cutoff` utilizado por la operación.

    Returns:
        float: Resultado producido por la operación.
    """
    if not relevant:
        return 0.0
    return len(set(ranked[:cutoff]) & relevant) / len(relevant)


def ndcg(ranked: list[str], relevant: set[str], cutoff: int) -> float:
    """Ejecuta la operación `ndcg`.

    Args:
        ranked (list[str]): Valor de `ranked` utilizado por la operación.
        relevant (set[str]): Valor de `relevant` utilizado por la operación.
        cutoff (int): Valor de `cutoff` utilizado por la operación.

    Returns:
        float: Resultado producido por la operación.
    """
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
    """Ejecuta la operación `mean`.

    Args:
        values (Iterable[float]): Valor de `values` utilizado por la operación.

    Returns:
        float: Resultado producido por la operación.
    """
    materialized = list(values)
    return sum(materialized) / len(materialized) if materialized else 0.0


def lexical_rank(query: str, documents: list[dict]) -> list[str]:
    """Ejecuta la operación `lexical_rank`.

    Args:
        query (str): Valor de `query` utilizado por la operación.
        documents (list[dict]): Colección de documentos que debe procesarse.

    Returns:
        list[str]: Colección de elementos obtenidos por la operación.
    """
    tokens = normalized_tokens(query)

    def score(document: dict) -> tuple[float, str]:
        """Ejecuta la operación `score`.

        Args:
            document (dict): Documento que debe procesarse.

        Returns:
            tuple[float, str]: Resultado producido por la operación.
        """
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
    """Ejecuta la operación `reciprocal_rank_fusion`.

    Args:
        lexical (list[str]): Valor de `lexical` utilizado por la operación.
        semantic (list[str]): Valor de `semantic` utilizado por la operación.
        semantic_weight (float): Valor de `semantic_weight` utilizado por la operación.
        k (int): Valor de `k` utilizado por la operación.

    Returns:
        list[str]: Colección de elementos obtenidos por la operación.
    """
    scores: dict[str, float] = {}
    for rank, app_id in enumerate(lexical, start=1):
        scores[app_id] = scores.get(app_id, 0.0) + 1.0 / (k + rank)
    for rank, app_id in enumerate(semantic, start=1):
        scores[app_id] = scores.get(app_id, 0.0) + semantic_weight / (k + rank)
    return sorted(scores, key=lambda app_id: (-scores[app_id], app_id))


def normalized_tokens(value: str) -> list[str]:
    """Ejecuta la operación `normalized_tokens`.

    Args:
        value (str): Valor que debe procesarse.

    Returns:
        list[str]: Colección de elementos obtenidos por la operación.
    """
    return [
        token
        for token in re.sub(r"[^\w]+", " ", value.lower(), flags=re.UNICODE).split()
        if len(token) >= 2
    ]
