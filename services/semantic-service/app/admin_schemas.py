from __future__ import annotations

from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator


class AdminModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True)


class DownloadModelRequest(AdminModel):
    repository: str = Field(min_length=3, max_length=200)
    revision: str | None = Field(default=None, max_length=200)
    query_prefix: str = Field(default="", alias="queryPrefix", max_length=100)
    passage_prefix: str = Field(default="", alias="passagePrefix", max_length=100)
    minimum_similarity: float = Field(
        default=0.0,
        alias="minimumSimilarity",
        ge=-1,
        le=1,
    )
    acknowledge_unknown_license: bool = Field(
        default=False,
        alias="acknowledgeUnknownLicense",
    )
    acknowledge_missing_configuration: bool = Field(
        default=False,
        alias="acknowledgeMissingConfiguration",
    )

    @field_validator("repository")
    @classmethod
    def validate_repository(cls, value: str) -> str:
        normalized = value.strip()
        parts = normalized.split("/")
        if (
            len(parts) != 2
            or any(not part for part in parts)
            or any(char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._"
                   for part in parts for char in part)
        ):
            raise ValueError("repository_must_be_owner_and_name")
        return normalized


class BenchmarkModelsRequest(AdminModel):
    model_ids: list[UUID] = Field(alias="modelIds", min_length=2, max_length=4)

    @field_validator("model_ids")
    @classmethod
    def unique_models(cls, values: list[UUID]) -> list[UUID]:
        if len(set(values)) != len(values):
            raise ValueError("model_ids_must_be_unique")
        return values


class ActivateModelRequest(AdminModel):
    benchmark_run_id: UUID = Field(alias="benchmarkRunId")
    expected_current_model_id: UUID | None = Field(
        default=None,
        alias="expectedCurrentModelId",
    )
    confirm_regression: bool = Field(default=False, alias="confirmRegression")


class SemanticOperationResponse(AdminModel):
    operation_id: UUID = Field(alias="operationId")
    status: Literal[
        "queued",
        "running",
        "cancel_requested",
        "cancelled",
        "succeeded",
        "failed",
    ] = "queued"
