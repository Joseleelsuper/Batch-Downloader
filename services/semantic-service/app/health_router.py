"""Publica sondas del API y métricas protegidas de PostgreSQL y de los workers semánticos."""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse, PlainTextResponse

from app.healthcheck import directory_writable
from app.heartbeat import WorkerHeartbeatStatus
from app.http_context import (
    database,
    heartbeat_store,
    require_internal_service_token,
    runtime_for,
    settings,
    store,
)

router = APIRouter()


@router.get("/semantic/health")
async def health() -> dict[str, object]:
    """Comprueba base de datos, carga del modelo activo y latidos de indexador y trabajador de
    modelos.
    Los fallos al calentar el modelo o consultar latidos degradan sus señales sin propagar
    esos errores.

    Returns:
        estado operativo y capacidades; searchReady se informa por separado del estado global.
    """
    database_ready = await asyncio.to_thread(database.healthy)
    active = await asyncio.to_thread(store.active_model) if database_ready else None
    search_ready = active is not None
    if active is not None:
        try:
            await asyncio.to_thread(runtime_for(active[0]).warmup)
        except Exception:
            search_ready = False
    workers: dict[str, WorkerHeartbeatStatus] = {}
    if database_ready:
        try:
            workers = await asyncio.to_thread(worker_heartbeat_statuses)
        except Exception:
            workers = {}
    workers_ready = bool(workers) and all(status.healthy for status in workers.values())
    return {
        "status": "ok" if database_ready and workers_ready else "degraded",
        "service": "semantic-service",
        "database": database_ready,
        "searchReady": search_ready,
        "modelVersion": active[0].model_version if search_ready and active else None,
        "indexVersion": active[1] if search_ready and active else None,
        "workers": {role: status.as_dict() for role, status in workers.items()},
    }


@router.get("/semantic/health/live")
async def health_live() -> dict[str, object]:
    """Confirma que el proceso HTTP y el bucle de eventos siguen respondiendo.

    Returns:
        estado ok del proceso, sin consultar base de datos ni modelos.
    """
    return {"status": "ok", "service": "semantic-service"}


@router.get("/semantic/health/ready")
async def health_ready() -> JSONResponse:
    """Comprueba en paralelo PostgreSQL y escritura en el directorio de caché local.

    Returns:
        200 cuando ambos recursos están listos; 503 con las capacidades que fallaron.
    """
    database_ready, cache_ready = await asyncio.gather(
        asyncio.to_thread(database.healthy),
        asyncio.to_thread(directory_writable, settings.model_cache_dir),
    )
    ready = database_ready and cache_ready
    return JSONResponse(
        status_code=200 if ready else 503,
        content={
            "status": "ok" if ready else "degraded",
            "service": "semantic-service",
            "database": database_ready,
            "modelCacheWritable": cache_ready,
        },
    )


@router.get(
    "/internal/v1/metrics",
    response_class=PlainTextResponse,
    dependencies=[Depends(require_internal_service_token)],
)
async def internal_metrics() -> PlainTextResponse:
    """Expone métricas del pool y latidos por rol tras validar la credencial interna.

    Returns:
        texto Prometheus con salud, antigüedad y fallos consecutivos, sin datos de consultas.
    """
    lines: list[str] = []
    for key, value in database.metrics().items():
        metric = "semantic_db_pool_" + key.replace("-", "_")
        lines.extend((f"# TYPE {metric} gauge", f"{metric} {value}"))
    lines.extend(
        (
            "# TYPE semantic_worker_healthy gauge",
            "# TYPE semantic_worker_consecutive_failures gauge",
            "# TYPE semantic_worker_heartbeat_age_seconds gauge",
        )
    )
    for role, status in worker_heartbeat_statuses().items():
        labels = f'{{role="{role}"}}'
        lines.extend(
            (
                f"semantic_worker_healthy{labels} {1 if status.healthy else 0}",
                f"semantic_worker_consecutive_failures{labels} "
                f"{status.consecutive_failures}",
            )
        )
        if status.age_seconds is not None:
            lines.extend(
                (
                    f"semantic_worker_heartbeat_age_seconds{labels} "
                    f"{status.age_seconds}",
                )
            )
    return PlainTextResponse(
        "\n".join(lines) + "\n",
        media_type="text/plain; version=0.0.4",
    )


def worker_heartbeat_statuses() -> dict[str, WorkerHeartbeatStatus]:
    """Consulta por separado los latidos de indexer y model-worker aplicando vigencia y umbral de
    fallos configurados.

    Returns:
        estado de cada rol; las lecturas no forman una instantánea transaccional conjunta.
    """
    return {
        role: heartbeat_store.status(
            role,
            max_age_seconds=settings.worker_heartbeat_stale_seconds,
            failure_threshold=settings.worker_failure_threshold,
        )
        for role in ("indexer", "model-worker")
    }


