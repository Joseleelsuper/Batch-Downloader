"""Sondas HTTP y metricas protegidas del servicio semantico."""
from __future__ import annotations

import asyncio
from pathlib import Path

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse, PlainTextResponse

from app.heartbeat import WorkerHeartbeatStatus
from app.http_context import (
    current_model_manifest,
    database,
    heartbeat_store,
    require_internal_service_token,
    settings,
    store,
)
from app.model_validation import model_directory_ready

router = APIRouter()


@router.get("/semantic/health")
async def health() -> dict[str, object]:
    database_ready = await asyncio.to_thread(database.healthy)
    status = await asyncio.to_thread(store.semantic_status) if database_ready else None
    descriptor = None
    artifact_ready = False
    try:
        descriptor = current_model_manifest()
        artifact_ready = model_directory_ready(
            Path(settings.model_dir),
            manifest_name=settings.model_manifest_name,
        )
    except Exception:
        pass

    active = await asyncio.to_thread(store.active_model) if database_ready else None
    active_model = (
        active[0]
        if active is not None
        and descriptor is not None
        and active[0].model_version == descriptor.model_version
        else None
    )
    # La sonda administrativa debe ser barata: cargar y ejecutar el modelo aquí puede superar
    # el timeout de Core en un arranque en frío. La búsqueda valida el runtime al usarlo.
    search_ready = active_model is not None and artifact_ready

    worker: dict[str, object] = {
        "present": False,
        "healthy": False,
        "reason": "database_unavailable",
        "ageSeconds": None,
        "lastSuccessAgeSeconds": None,
        "lastErrorAgeSeconds": None,
        "lastErrorCode": None,
        "consecutiveFailures": 0,
    }
    if database_ready:
        try:
            heartbeat = worker_heartbeat_statuses().get("indexer")
            worker = heartbeat.as_dict() if heartbeat else {}
        except Exception:
            worker["reason"] = "unavailable"
    index: dict[str, object] = {
        "indexVersion": None,
        "expected": 0,
        "indexed": 0,
        "complete": False,
        "builtAt": None,
    }
    index.update((status or {}).get("index") or {})
    return {
        "status": "ok" if database_ready and search_ready else "degraded",
        "service": "semantic-service",
        "database": database_ready,
        "searchReady": search_ready,
        "model": {
            "version": descriptor.model_version if descriptor else None,
            "dimensions": descriptor.dimensions if descriptor else None,
            "artifactReady": artifact_ready,
        },
        "index": index,
        "indexer": worker,
    }


@router.get("/semantic/health/live")
async def health_live() -> dict[str, str]:
    return {"status": "ok", "service": "semantic-service"}


@router.get("/semantic/health/ready")
async def health_ready() -> JSONResponse:
    database_ready, model_ready = await asyncio.gather(
        asyncio.to_thread(database.healthy),
        asyncio.to_thread(
            model_directory_ready,
            Path(settings.model_dir),
            manifest_name=settings.model_manifest_name,
        ),
    )
    ready = database_ready and model_ready
    return JSONResponse(
        status_code=200 if ready else 503,
        content={
            "status": "ok" if ready else "degraded",
            "service": "semantic-service",
            "database": database_ready,
            "modelReady": model_ready,
        },
    )


@router.get(
    "/internal/v1/metrics",
    response_class=PlainTextResponse,
    dependencies=[Depends(require_internal_service_token)],
)
async def internal_metrics() -> PlainTextResponse:
    lines: list[str] = []
    for key, value in database.metrics().items():
        metric = "semantic_db_pool_" + key.replace("-", "_")
        lines.extend((f"# TYPE {metric} gauge", f"{metric} {value}"))
    lines.extend((
        "# TYPE semantic_worker_healthy gauge",
        "# TYPE semantic_worker_consecutive_failures gauge",
        "# TYPE semantic_worker_heartbeat_age_seconds gauge",
    ))
    for role, status in worker_heartbeat_statuses().items():
        labels = f'{{role="{role}"}}'
        lines.extend((
            f"semantic_worker_healthy{labels} {1 if status.healthy else 0}",
            f"semantic_worker_consecutive_failures{labels} {status.consecutive_failures}",
        ))
        if status.age_seconds is not None:
            lines.append(f"semantic_worker_heartbeat_age_seconds{labels} {status.age_seconds}")
    return PlainTextResponse(
        "\n".join(lines) + "\n",
        media_type="text/plain; version=0.0.4",
    )


def worker_heartbeat_statuses() -> dict[str, WorkerHeartbeatStatus]:
    return {
        "indexer": heartbeat_store.status(
            "indexer",
            max_age_seconds=settings.worker_heartbeat_stale_seconds,
            failure_threshold=settings.worker_failure_threshold,
        )
    }
