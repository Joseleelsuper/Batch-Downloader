"""Implementa las responsabilidades del módulo `admin_schemas`.
"""
from __future__ import annotations

from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator


class AdminModel(BaseModel):
    """Representa los datos validados de `AdminModel`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `AdminModel`.
    """


class DownloadModelRequest(AdminModel):
    """Representa una solicitud de `DownloadModel`.
    """
    repository: str = Field(min_length=3, max_length=200)
    """Campo declarado `repository` de `DownloadModelRequest`.
    """
    revision: str | None = Field(default=None, max_length=200)
    """Campo declarado `revision` de `DownloadModelRequest`.
    """
    query_prefix: str = Field(default="", alias="queryPrefix", max_length=100)
    """Campo declarado `query_prefix` de `DownloadModelRequest`.
    """
    passage_prefix: str = Field(default="", alias="passagePrefix", max_length=100)
    """Campo declarado `passage_prefix` de `DownloadModelRequest`.
    """
    minimum_similarity: float = Field(
        default=0.0,
        alias="minimumSimilarity",
        ge=-1,
        le=1,
    )
    """Campo declarado `minimum_similarity` de `DownloadModelRequest`.
    """
    acknowledge_unknown_license: bool = Field(
        default=False,
        alias="acknowledgeUnknownLicense",
    )
    """Campo declarado `acknowledge_unknown_license` de `DownloadModelRequest`.
    """
    acknowledge_missing_configuration: bool = Field(
        default=False,
        alias="acknowledgeMissingConfiguration",
    )
    """Campo declarado `acknowledge_missing_configuration` de `DownloadModelRequest`.
    """

    @field_validator("repository")
    @classmethod
    def validate_repository(cls, value: str) -> str:
        """Valida la operación `repository`.

        Args:
            value (str): Valor que debe procesarse.

        Returns:
            str: Resultado producido por la operación.

        Throws:
            ValueError: Si los datos recibidos no cumplen las restricciones requeridas.
        """
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
    """Representa una solicitud de `BenchmarkModels`.
    """
    model_ids: list[UUID] = Field(alias="modelIds", min_length=2, max_length=4)
    """Campo declarado `model_ids` de `BenchmarkModelsRequest`.
    """

    @field_validator("model_ids")
    @classmethod
    def unique_models(cls, values: list[UUID]) -> list[UUID]:
        """Ejecuta `unique_models` dentro de `BenchmarkModelsRequest`.

        Args:
            values (list[UUID]): Valor de `values` utilizado por la operación.

        Returns:
            list[UUID]: Colección de elementos obtenidos por la operación.

        Throws:
            ValueError: Si los datos recibidos no cumplen las restricciones requeridas.
        """
        if len(set(values)) != len(values):
            raise ValueError("model_ids_must_be_unique")
        return values


class ActivateModelRequest(AdminModel):
    """Representa una solicitud de `ActivateModel`.
    """
    benchmark_run_id: UUID = Field(alias="benchmarkRunId")
    """Campo declarado `benchmark_run_id` de `ActivateModelRequest`.
    """
    expected_current_model_id: UUID | None = Field(
        default=None,
        alias="expectedCurrentModelId",
    )
    """Campo declarado `expected_current_model_id` de `ActivateModelRequest`.
    """
    confirm_regression: bool = Field(default=False, alias="confirmRegression")
    """Campo declarado `confirm_regression` de `ActivateModelRequest`.
    """


class SemanticOperationResponse(AdminModel):
    """Representa una respuesta de `SemanticOperation`.
    """
    operation_id: UUID = Field(alias="operationId")
    """Campo declarado `operation_id` de `SemanticOperationResponse`.
    """
    status: Literal[
        "queued",
        "running",
        "cancel_requested",
        "cancelled",
        "succeeded",
        "failed",
    ] = "queued"
    """Atributo de clase `status` de `SemanticOperationResponse`.
    """
