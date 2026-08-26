"""Implementa las responsabilidades del módulo `schemas`.
"""
from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class SemanticSearchRequest(BaseModel):
    """Representa una solicitud de `SemanticSearch`.
    """
    query: str = Field(min_length=1, max_length=500)
    """Campo declarado `query` de `SemanticSearchRequest`.
    """
    limit: int = Field(default=20000, ge=1, le=20000)
    """Campo declarado `limit` de `SemanticSearchRequest`.
    """
    minimum_similarity: float | None = Field(
        default=None,
        alias="minimumSimilarity",
        ge=-1,
        le=1,
    )
    """Campo declarado `minimum_similarity` de `SemanticSearchRequest`.
    """

    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `SemanticSearchRequest`.
    """


class SemanticCandidate(BaseModel):
    """Representa los datos validados de `SemanticCandidate`.
    """
    app_id: str = Field(alias="appId")
    """Campo declarado `app_id` de `SemanticCandidate`.
    """
    rank: int
    """Campo declarado `rank` de `SemanticCandidate`.
    """
    similarity: float
    """Campo declarado `similarity` de `SemanticCandidate`.
    """

    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `SemanticCandidate`.
    """


class SemanticSearchResponse(BaseModel):
    """Representa una respuesta de `SemanticSearch`.
    """
    candidates: list[SemanticCandidate]
    """Campo declarado `candidates` de `SemanticSearchResponse`.
    """
    model_version: str = Field(alias="modelVersion")
    """Campo declarado `model_version` de `SemanticSearchResponse`.
    """
    index_version: str = Field(alias="indexVersion")
    """Campo declarado `index_version` de `SemanticSearchResponse`.
    """
    truncated: bool
    """Campo declarado `truncated` de `SemanticSearchResponse`.
    """

    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `SemanticSearchResponse`.
    """
