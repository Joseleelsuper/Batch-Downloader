"""Define métricas de recuperación y rankings de referencia para comparar candidatos semánticos
con las mismas consultas.
"""
from __future__ import annotations

import math
import re
from collections.abc import Iterable


def reciprocal_rank(ranked: list[str], relevant: set[str], cutoff: int) -> float:
    """Mide la inversa de la posición del primer resultado relevante dentro del corte solicitado.

    Args:
        ranked: UUID de aplicaciones en orden de relevancia; el ranking debe estar libre de
            duplicados.
        relevant: UUID de aplicaciones consideradas relevantes para la consulta.
        cutoff: Número positivo de posiciones superiores que se tienen en cuenta.

    Returns:
        1/posición con posiciones desde uno, o cero cuando no hay acierto.
    """
    for index, app_id in enumerate(ranked[:cutoff], start=1):
        if app_id in relevant:
            return 1.0 / index
    return 0.0


def average_precision(ranked: list[str], relevant: set[str], cutoff: int) -> float:
    """Promedia la precisión acumulada en cada acierto, normalizada por los relevantes que caben
    en el corte.

    Args:
        ranked: UUID de aplicaciones en orden de relevancia; el ranking debe estar libre de
            duplicados.
        relevant: UUID de aplicaciones consideradas relevantes para la consulta.
        cutoff: Número positivo de posiciones superiores que se tienen en cuenta.

    Returns:
        precisión media entre cero y uno; cero si el conjunto relevante está vacío.
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
    """Mide qué proporción de todos los documentos relevantes aparece entre los primeros
    resultados.

    Args:
        ranked: UUID de aplicaciones en orden de relevancia; el ranking debe estar libre de
            duplicados.
        relevant: UUID de aplicaciones consideradas relevantes para la consulta.
        cutoff: Número positivo de posiciones superiores que se tienen en cuenta.

    Returns:
        fracción recuperada o cero cuando no hay relevantes.
    """
    if not relevant:
        return 0.0
    return len(set(ranked[:cutoff]) & relevant) / len(relevant)


def ndcg(ranked: list[str], relevant: set[str], cutoff: int) -> float:
    """Compara la ganancia descontada de relevancia binaria con el mejor orden posible dentro del
    corte.

    Args:
        ranked: UUID de aplicaciones en orden de relevancia; el ranking debe estar libre de
            duplicados.
        relevant: UUID de aplicaciones consideradas relevantes para la consulta.
        cutoff: Número positivo de posiciones superiores que se tienen en cuenta.

    Returns:
        ganancia normalizada o cero si no hay posiciones relevantes posibles.
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
    """Materializa una secuencia finita y calcula su media aritmética.

    Args:
        values: Medidas numéricas cuyo promedio se necesita; admite generadores finitos.

    Returns:
        media o cero si la secuencia no contiene valores.
    """
    materialized = list(values)
    return sum(materialized) / len(materialized) if materialized else 0.0


def lexical_rank(query: str, documents: list[dict]) -> list[str]:
    """Ordena candidatos por coincidencias de nombre y paquete, prefijo y términos de editor y
    contenido.
    Sirve de referencia local del benchmark; no ejecuta las consultas de búsqueda de MySQL.

    Args:
        query: Texto que se compara con nombre, paquete, editor y contenido de los documentos.
        documents: Documentos del catálogo con app_id, contenido, huella y metadatos
            utilizados en la evaluación.

    Returns:
        UUID con puntuación positiva, por puntuación descendente y UUID en los empates.
    """
    tokens = normalized_tokens(query)

    def score(document: dict) -> tuple[float, str]:
        """Puntúa una aplicación priorizando nombre o paquete exactos y prefijos antes que
        coincidencias de términos.

        Args:
            document: Aplicación con contenido y metadatos a comparar con la consulta del
                cierre.

        Returns:
            puntuación y UUID que permite desempatar de forma estable.
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
    """Suma contribuciones inversas de rango de los resultados léxicos y semánticos para producir
    un orden combinado.

    Args:
        lexical: UUID del ranking léxico en orden de relevancia.
        semantic: UUID del ranking semántico en orden de relevancia.
        semantic_weight: Peso semántico de la fusión RRF; None evalúa exclusivamente el
            ranking semántico.
        k: Constante positiva que suaviza diferencias de posición; por defecto 60.

    Returns:
        unión de candidatos por puntuación descendente, con UUID como desempate.
    """
    scores: dict[str, float] = {}
    for rank, app_id in enumerate(lexical, start=1):
        scores[app_id] = scores.get(app_id, 0.0) + 1.0 / (k + rank)
    for rank, app_id in enumerate(semantic, start=1):
        scores[app_id] = scores.get(app_id, 0.0) + semantic_weight / (k + rank)
    return sorted(scores, key=lambda app_id: (-scores[app_id], app_id))


def normalized_tokens(value: str) -> list[str]:
    """Convierte texto a minúsculas, separa caracteres no alfanuméricos Unicode y descarta
    términos de menos de dos caracteres.

    Args:
        value: Texto de consulta, nombre, paquete o contenido que se desea comparar
            léxicamente.

    Returns:
        términos en su orden original, conservando repeticiones.
    """
    return [
        token
        for token in re.sub(r"[^\w]+", " ", value.lower(), flags=re.UNICODE).split()
        if len(token) >= 2
    ]
