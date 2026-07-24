from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class SemanticSearchRequest(BaseModel):
    query: str = Field(min_length=1, max_length=500)
    limit: int = Field(default=2000, ge=1, le=2001)
    minimum_similarity: float | None = Field(
        default=None,
        alias="minimumSimilarity",
        ge=-1,
        le=1,
    )

    model_config = ConfigDict(populate_by_name=True)


class SemanticCandidate(BaseModel):
    app_id: str = Field(alias="appId")
    rank: int
    similarity: float

    model_config = ConfigDict(populate_by_name=True)


class SemanticSearchResponse(BaseModel):
    candidates: list[SemanticCandidate]
    model_version: str = Field(alias="modelVersion")
    index_version: str = Field(alias="indexVersion")
    rrf_weight: float = Field(alias="rrfWeight", gt=0, le=10)
    truncated: bool

    model_config = ConfigDict(populate_by_name=True)
