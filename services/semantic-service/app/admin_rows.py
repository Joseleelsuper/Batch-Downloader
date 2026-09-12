"""Proyecta filas internas a respuestas administrativas sin exponer rutas locales ni solicitudes
completas.
"""

from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path
from typing import Any


def model_from_row(row: dict[str, Any]) -> dict[str, Any]:
    """Construye la vista administrativa de un modelo y su último benchmark, normalizando UUID,
    fechas y contadores.

    Args:
        row: Fila de PostgreSQL con las columnas requeridas por la proyección.

    Returns:
        metadatos públicos con estados, cobertura y vigencia del benchmark; omite local_path.
    """
    metadata = row["metadata"] or {}
    metrics = row.get("benchmark_metrics") or []
    metric = metric_for(metrics, str(row["id"]))
    return {
        "id": str(row["id"]),
        "displayName": row["display_name"],
        "repository": row["hf_repository"],
        "revision": row["resolved_revision"],
        "artifactState": row["artifact_state"],
        "deploymentState": row.get("deployment_state") or "not_prepared",
        "artifactBytes": int(row["artifact_bytes"] or 0),
        "dimensions": row["dimensions"],
        "queryPrefix": row["query_prefix"],
        "passagePrefix": row["passage_prefix"],
        "minimumSimilarity": float(row["minimum_similarity"]),
        "metadata": metadata,
        "validationMessage": row["validation_message"],
        "modelVersion": row.get("model_version"),
        "active": bool(row.get("active")),
        "createdAt": iso_value(row["created_at"]),
        "downloadedAt": iso_value(row["downloaded_at"]),
        "validatedAt": iso_value(row["validated_at"]),
        "activatedAt": iso_value(row.get("activated_at")),
        "index": {
            "indexVersion": row.get("index_version"),
            "snapshotHash": row.get("snapshot_hash"),
            "expected": int(row.get("expected_documents") or 0),
            "indexed": int(row.get("indexed_documents") or 0),
            "complete": bool(row.get("complete")),
            "builtAt": iso_value(row.get("built_at")),
        },
        "lastBenchmark": (
            {
                "id": str(row["benchmark_id"]),
                "datasetHash": row["benchmark_dataset_hash"],
                "scope": row["benchmark_scope"],
                "hardwareFingerprint": row["hardware_fingerprint"],
                "metric": metric,
                "current": (
                    row["benchmark_scope"] == "full"
                    and (row.get("benchmark_configuration") or {}).get("catalogSnapshotHash")
                    == row.get("current_catalog_snapshot_hash")
                ),
                "createdAt": iso_value(row["benchmark_created_at"]),
            }
            if row.get("benchmark_id")
            else None
        ),
    }


def operation_from_row(row: dict[str, Any]) -> dict[str, Any]:
    """Expone estado y progreso de una operación con los modelos relacionados y su resultado, sin
    copiar el payload de entrada.

    Args:
        row: Fila de PostgreSQL con las columnas requeridas por la proyección.

    Returns:
        contrato de operación con fechas ISO-8601, reserva y error seguro.
    """
    request_payload = dict(row["request_payload"] or {})
    related_model_ids = [
        str(value)
        for value in (
            request_payload.get("modelIds") or ([row["model_id"]] if row["model_id"] else [])
        )
    ]
    return {
        "id": str(row["id"]),
        "kind": row["operation_kind"],
        "status": row["status"],
        "phase": row["phase"],
        "modelId": str(row["model_id"]) if row["model_id"] else None,
        "modelIds": related_model_ids,
        "modelVersion": row["model_version"],
        "repository": row["repository"],
        "revision": row["resolved_revision"],
        "progress": {
            "current": int(row["progress_current"]),
            "total": int(row["progress_total"]),
            "unit": row["progress_unit"],
        },
        "message": row["safe_message"],
        "errorCode": row["error_code"],
        "result": row["result_payload"] or {},
        "actor": row["actor"],
        "attempts": row["attempts"],
        "leaseOwner": row["lease_owner"],
        "leaseUntil": iso_value(row["lease_until"]),
        "createdAt": iso_value(row["created_at"]),
        "startedAt": iso_value(row["started_at"]),
        "updatedAt": iso_value(row["updated_at"]),
        "finishedAt": iso_value(row["finished_at"]),
    }


def metric_for(
    metrics: list[dict[str, Any]],
    model_id: str | None,
) -> dict[str, Any] | None:
    """Selecciona la primera métrica asociada al UUID de un modelo dentro del benchmark.

    Args:
        metrics: Resultados por modelo, con elegibilidad y puntuaciones comparables.
        model_id: UUID del artefacto persistido; no es la versión del modelo ni el nombre del
            repositorio.

    Returns:
        métrica del modelo o None si no hay identidad o coincidencia.
    """
    if not model_id:
        return None
    return next((metric for metric in metrics if metric.get("modelId") == model_id), None)


def iso_value(value: datetime | None) -> str | None:
    """Convierte un instante opcional a ISO-8601 y asigna UTC a valores sin zona.

    Args:
        value: Instante opcional; si carece de zona horaria se interpreta como UTC.

    Returns:
        fecha con desplazamiento horario o None para un campo ausente.
    """
    if value is None:
        return None
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    return value.isoformat()


def directory_bytes(path: str | Path) -> int:
    """Suma el tamaño lógico de archivos descendientes de un directorio sin incluir el tamaño de
    las carpetas.

    Args:
        path: Directorio cuyos archivos descendientes se suman; no reserva ni libera espacio.

    Returns:
        suma en bytes; las rutas inexistentes producen cero.

    Raises:
        OSError: Si no se puede inspeccionar un archivo durante el recorrido.
    """
    root = Path(path)
    return sum(entry.stat().st_size for entry in root.rglob("*") if entry.is_file())
