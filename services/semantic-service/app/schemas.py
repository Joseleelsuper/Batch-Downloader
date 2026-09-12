"""Valida el contrato interno de consulta semántica y su respuesta de candidatos, modelo e
índice.
"""
from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class SemanticSearchRequest(BaseModel):
    """Define la consulta cuyo ranking semántico puede consumir Core sin perder la señal de
    truncamiento.

    Attributes:
        query: Texto de 1 a 500 caracteres.
        limit: Máximo de candidatos, entre 1 y 20000.
        minimum_similarity: Umbral coseno opcional entre -1 y 1; None permite el valor
            configurado.
        model_config: Acepta nombres Python o alias camelCase.

    See Also:
        app.search_router.semantic_search: Aplica el modelo activo y la capacidad del
            servicio.
    """
    query: str = Field(min_length=1, max_length=500)

    limit: int = Field(default=20000, ge=1, le=20000)

    minimum_similarity: float | None = Field(
        default=None,
        alias="minimumSimilarity",
        ge=-1,
        le=1,
    )


    model_config = ConfigDict(populate_by_name=True)



class SemanticCandidate(BaseModel):
    """Transporta identidad y relevancia de una aplicación; Core aplica sus filtros autoritativos
    y el orden final.

    Attributes:
        app_id: UUID del catálogo, serializado como appId.
        rank: Posición semántica empezando en uno.
        similarity: Similitud coseno medida frente a la consulta.
        model_config: Acepta nombre Python o alias del contrato.
    """
    app_id: str = Field(alias="appId")

    rank: int

    similarity: float


    model_config = ConfigDict(populate_by_name=True)



class SemanticSearchResponse(BaseModel):
    """Vincula los candidatos a una versión completa de modelo e índice e indica si el conjunto
    fue recortado.

    Attributes:
        candidates: Resultados hasta el límite solicitado.
        model_version: Identidad del modelo utilizado.
        index_version: Huella de la versión del índice consultado.
        truncated: Quedaron candidatos aceptables fuera del límite.
        model_config: Acepta nombres Python o alias camelCase.
    """
    candidates: list[SemanticCandidate]

    model_version: str = Field(alias="modelVersion")

    index_version: str = Field(alias="indexVersion")

    truncated: bool


    model_config = ConfigDict(populate_by_name=True)

