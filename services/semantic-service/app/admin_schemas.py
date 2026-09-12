"""Valida identidades y precondiciones de las operaciones administrativas solicitadas desde Core."""
from __future__ import annotations

from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator


class AdminModel(BaseModel):
    """Comparte aceptación de nombres Python y alias camelCase entre solicitudes y respuestas
    administrativas.

    Attributes:
        model_config: populate_by_name mantiene ambos formatos de entrada.
    """
    model_config = ConfigDict(populate_by_name=True)



class BenchmarkModelsRequest(AdminModel):
    """Identifica de dos a cuatro artefactos distintos que deben compararse en la misma
    ejecución.

    Attributes:
        model_ids: UUID únicos; la ruta añade el activo si falta sin superar cuatro modelos.
    """
    model_ids: list[UUID] = Field(alias="modelIds", min_length=2, max_length=4)


    @field_validator("model_ids")
    @classmethod
    def unique_models(cls, values: list[UUID]) -> list[UUID]:
        """Rechaza UUID repetidos para no comparar dos veces el mismo artefacto ni alterar el
        número de candidatos.

        Args:
            values: UUID ya validados y en el orden enviado por el administrador.

        Returns:
            la lista original sin reordenarla.

        Raises:
            ValueError: Si la lista contiene un UUID repetido.
        """
        if len(set(values)) != len(values):
            raise ValueError("model_ids_must_be_unique")
        return values


class ActivateModelRequest(AdminModel):
    """Conserva evidencia y precondiciones para cambiar el modelo activo sin aceptar una decisión
    administrativa obsoleta.

    Attributes:
        benchmark_run_id: UUID del benchmark completo que justifica la activación.
        expected_current_model_id: Activo observado por el solicitante; None exige ausencia de
            activo.
        confirm_regression: Permite una puntuación inferior, sin omitir controles de
            elegibilidad ni cobertura.
    """
    benchmark_run_id: UUID = Field(alias="benchmarkRunId")

    expected_current_model_id: UUID | None = Field(
        default=None,
        alias="expectedCurrentModelId",
    )

    confirm_regression: bool = Field(default=False, alias="confirmRegression")



class SemanticOperationResponse(AdminModel):
    """Confirma la identidad del trabajo persistente que el cliente puede consultar o recuperar
    después.

    Attributes:
        operation_id: UUID de la operación nueva o deduplicada.
        status: Estado permitido de cola, ejecución, cancelación o resultado terminal.
    """
    operation_id: UUID = Field(alias="operationId")

    status: Literal[
        "queued",
        "running",
        "cancel_requested",
        "cancelled",
        "succeeded",
        "failed",
    ] = "queued"

