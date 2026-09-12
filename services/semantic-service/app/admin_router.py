"""Traduce peticiones administrativas de Core a consultas y operaciones persistentes protegidas
por token interno.
Las consultas bloqueantes se ejecutan en un hilo; el trabajador realiza preparación, benchmark
y activación fuera de la petición HTTP.
"""
from __future__ import annotations

import asyncio
from typing import Any
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query

from app.admin_request_context import AdminContext
from app.admin_schemas import (
    ActivateModelRequest,
    BenchmarkModelsRequest,
)
from app.http_context import (
    admin_store,
    require_internal_service_token,
    runtime_for,
    settings,
    store,
)

router = APIRouter(dependencies=[Depends(require_internal_service_token)])


async def create_admin_operation(**kwargs: Any) -> dict[str, Any]:
    """Crea o recupera una operación sin bloquear el bucle HTTP y traduce conflictos de
    idempotencia a 409.

    Args:
        kwargs: Argumentos del contrato SemanticOperationStore.create_operation, ya
            normalizados por la ruta.

    Returns:
        fila de operación persistida o recuperada.

    Raises:
        HTTPException: 409 si el almacén rechaza la solicitud por conflicto.

    See Also:
        app.admin_operation_store.SemanticOperationStore.create_operation: Contrato de
            creación y deduplicación.
    """
    try:
        return await asyncio.to_thread(admin_store.operations.create_operation, **kwargs)
    except RuntimeError as exception:
        raise HTTPException(
            status_code=409,
            detail={"code": str(exception)},
        ) from exception


@router.get(
    "/internal/v1/admin/semantic/overview",
)
async def semantic_admin_overview() -> dict[str, object]:
    """Resume modelo local activo, estado del índice, cola y presupuesto de disco del proceso.

    Returns:
        estado operativo administrativo con límites de almacenamiento en bytes.
    """
    return await asyncio.to_thread(
        admin_store.overview,
        settings.model_cache_dir,
        model_max_bytes=settings.model_max_bytes,
        model_min_free_bytes=settings.model_min_free_bytes,
    )


@router.get(
    "/internal/v1/admin/semantic/models",
)
async def semantic_admin_models() -> list[dict[str, object]]:
    """Lista modelos con directorio de artefactos disponible en el servidor, sin exponer esa ruta
    local.

    Returns:
        catálogo local con estados y últimas métricas.
    """
    return await asyncio.to_thread(admin_store.models.local_models)


@router.get(
    "/internal/v1/admin/semantic/models/{model_id}",
)
async def semantic_admin_model(model_id: UUID) -> dict[str, object]:
    """Consulta un modelo local por UUID y traduce su ausencia a una respuesta 404.

    Args:
        model_id: UUID del artefacto sobre el que se actúa; None para operaciones de
            repositorio.

    Returns:
        vista administrativa del modelo disponible.

    Raises:
        HTTPException: 404 si el artefacto no existe o carece de directorio local.
    """
    try:
        return await asyncio.to_thread(admin_store.models.local_model, str(model_id))
    except LookupError as exception:
        raise HTTPException(
            status_code=404,
            detail={"code": str(exception)},
        ) from exception


@router.get(
    "/internal/v1/admin/semantic/benchmarks",
)
async def semantic_admin_benchmarks(
    limit: int = Query(default=50, ge=1, le=200),
) -> list[dict[str, object]]:
    """Consulta el historial reciente de evaluaciones completas de modelos.

    Args:
        limit: Número de ejecuciones entre 1 y 200; FastAPI rechaza valores fuera del
            intervalo.

    Returns:
        benchmarks por fecha descendente.
    """
    return await asyncio.to_thread(admin_store.models.benchmarks, limit)


@router.post(
    "/internal/v1/admin/semantic/benchmarks",
    status_code=202,
)
async def semantic_admin_start_benchmark(
    request: BenchmarkModelsRequest,
    context: AdminContext,
) -> dict[str, object]:
    """Encola una comparación de hasta cuatro artefactos ready e incorpora el modelo activo si no
    estaba seleccionado.

    Args:
        request: UUID de modelos elegidos; el activo se añade al principio para asegurar
            comparación.
        context: Actor auditado y clave de idempotencia normalizados por la dependencia común.

    Returns:
        UUID y estado de la operación aceptada, nueva o recuperada.

    Raises:
        HTTPException: 404 para un modelo inexistente, 409 para modelos no listos o
            idempotencia incompatible y 422 si resultan más de cuatro.
    """
    model_ids = [str(model_id) for model_id in request.model_ids]
    active_id = await asyncio.to_thread(admin_store.models.active_model_id)
    if active_id and active_id not in model_ids:
        model_ids.insert(0, active_id)
    if len(model_ids) > 4:
        raise HTTPException(
            status_code=422,
            detail={"code": "benchmark_supports_at_most_four_models"},
        )
    for model_id in model_ids:
        try:
            model = await asyncio.to_thread(admin_store.models.model, model_id)
        except LookupError as exception:
            raise HTTPException(
                status_code=404,
                detail={"code": str(exception)},
            ) from exception
        if model["artifactState"] != "ready":
            raise HTTPException(
                status_code=409,
                detail={"code": "semantic_model_not_ready"},
            )
    operation = await create_admin_operation(
        kind="benchmark",
        actor=context.actor,
        idempotency_key=context.idempotency_key,
        request_payload={"modelIds": model_ids},
        model_id=model_ids[0],
        progress_total=len(model_ids),
        progress_unit="models",
    )
    return {"operationId": str(operation["id"]), "status": operation["status"]}


@router.post(
    "/internal/v1/admin/semantic/models/{model_id}/prepare",
    status_code=202,
)
async def semantic_admin_prepare(
    model_id: UUID,
    context: AdminContext,
) -> dict[str, object]:
    """Encola la construcción del índice de un candidato inactivo con benchmark vigente y
    elegible.

    Args:
        model_id: UUID del artefacto sobre el que se actúa; None para operaciones de
            repositorio.
        context: Actor auditado y clave de idempotencia normalizados por la dependencia común.

    Returns:
        UUID y estado de la operación aceptada.

    Raises:
        HTTPException: 404 para un modelo inexistente; 409 si ya está activo, falta evidencia
            válida o hay conflicto de idempotencia.
    """
    try:
        model = await asyncio.to_thread(admin_store.models.model, str(model_id))
    except LookupError as exception:
        raise HTTPException(status_code=404, detail={"code": str(exception)}) from exception
    if model["active"]:
        raise HTTPException(
            status_code=409,
            detail={"code": "semantic_active_model_cannot_be_prepared"},
        )
    benchmark = await asyncio.to_thread(
        admin_store.models.eligible_benchmark,
        str(model_id),
    )
    if not benchmark:
        raise HTTPException(
            status_code=409,
            detail={"code": "semantic_benchmark_required_or_stale"},
        )
    operation = await create_admin_operation(
        kind="prepare",
        actor=context.actor,
        idempotency_key=context.idempotency_key,
        request_payload={"modelId": str(model_id)},
        model_id=str(model_id),
        model_version=model.get("modelVersion"),
        progress_unit="documents",
    )
    return {"operationId": str(operation["id"]), "status": operation["status"]}


@router.post(
    "/internal/v1/admin/semantic/models/{model_id}/activate",
    status_code=202,
)
async def semantic_admin_activate(
    model_id: UUID,
    request: ActivateModelRequest,
    context: AdminContext,
) -> dict[str, object]:
    """Encola la activación de un modelo preparado conservando benchmark, modelo anterior
    esperado y confirmación de regresión.

    Args:
        model_id: UUID del artefacto sobre el que se actúa; None para operaciones de
            repositorio.
        request: Evidencia y precondiciones que el trabajador comprobará atómicamente antes de
            activar.
        context: Actor auditado y clave de idempotencia normalizados por la dependencia común.

    Returns:
        UUID y estado de la operación aceptada.

    Raises:
        HTTPException: 404 si falta el modelo; 409 si no está preparado o la clave identifica
            otra solicitud.
    """
    try:
        model = await asyncio.to_thread(admin_store.models.model, str(model_id))
    except LookupError as exception:
        raise HTTPException(status_code=404, detail={"code": str(exception)}) from exception
    if model["deploymentState"] not in {"ready", "active"}:
        raise HTTPException(
            status_code=409,
            detail={"code": "semantic_model_not_prepared"},
        )
    payload = request.model_dump(by_alias=True, mode="json")
    operation = await create_admin_operation(
        kind="activate",
        actor=context.actor,
        idempotency_key=context.idempotency_key,
        request_payload=payload,
        model_id=str(model_id),
        model_version=model.get("modelVersion"),
    )
    return {"operationId": str(operation["id"]), "status": operation["status"]}


@router.delete(
    "/internal/v1/admin/semantic/models/{model_id}",
    status_code=202,
)
async def semantic_admin_delete(
    model_id: UUID,
    context: AdminContext,
) -> dict[str, object]:
    """Encola el borrado de un modelo inactivo después de comprobar que no participa en otra
    operación abierta.

    Args:
        model_id: UUID del artefacto sobre el que se actúa; None para operaciones de
            repositorio.
        context: Actor auditado y clave de idempotencia normalizados por la dependencia común.

    Returns:
        UUID y estado de la operación aceptada.

    Raises:
        HTTPException: 404 si no existe; 409 si está activo, tiene trabajo abierto o falla la
            idempotencia.
    """
    try:
        model = await asyncio.to_thread(admin_store.models.model, str(model_id))
    except LookupError as exception:
        raise HTTPException(status_code=404, detail={"code": str(exception)}) from exception
    if model["active"]:
        raise HTTPException(
            status_code=409,
            detail={"code": "active_semantic_model_cannot_be_deleted"},
        )
    try:
        await asyncio.to_thread(
            admin_store.models.assert_model_deletable,
            str(model_id),
        )
    except RuntimeError as exception:
        raise HTTPException(
            status_code=409,
            detail={"code": str(exception)},
        ) from exception
    operation = await create_admin_operation(
        kind="delete",
        actor=context.actor,
        idempotency_key=context.idempotency_key,
        request_payload={"modelId": str(model_id)},
        model_id=str(model_id),
        model_version=model.get("modelVersion"),
    )
    return {"operationId": str(operation["id"]), "status": operation["status"]}


@router.get(
    "/internal/v1/admin/semantic/operations",
)
async def semantic_admin_operations(
    limit: int = Query(default=100, ge=1, le=250),
    active: bool = False,
) -> list[dict[str, object]]:
    """Devuelve el historial reciente de operaciones o únicamente las que siguen abiertas.

    Args:
        limit: Número de operaciones entre 1 y 250; el valor por defecto es 100.
        active: Si es True, devuelve únicamente operaciones pendientes, en curso o con
            cancelación solicitada.

    Returns:
        estados y progreso de operaciones ordenadas por creación descendente.
    """
    return await asyncio.to_thread(
        admin_store.operations.operations,
        limit=limit,
        active_only=active,
    )


@router.get(
    "/internal/v1/admin/semantic/operations/{operation_id}",
)
async def semantic_admin_operation(operation_id: UUID) -> dict[str, object]:
    """Permite recuperar el estado y resultado de una operación después de recargar la interfaz.

    Args:
        operation_id: UUID de la operación administrativa persistida.

    Returns:
        operación con su progreso, resultado y error seguro.

    Raises:
        HTTPException: 404 si el UUID no existe.
    """
    try:
        return await asyncio.to_thread(admin_store.operations.operation, str(operation_id))
    except LookupError as exception:
        raise HTTPException(status_code=404, detail={"code": str(exception)}) from exception


@router.delete(
    "/internal/v1/admin/semantic/operations/{operation_id}",
)
async def semantic_admin_cancel_operation(operation_id: UUID) -> dict[str, object]:
    """Solicita cancelación cooperativa o cancela trabajo aún en cola, conservando estados ya
    terminales.

    Args:
        operation_id: UUID de la operación administrativa persistida.

    Returns:
        estado de operación tras la solicitud.

    Raises:
        HTTPException: 404 si no existe; 409 durante una fase final no cancelable.
    """
    try:
        return await asyncio.to_thread(admin_store.operations.request_cancel, str(operation_id))
    except LookupError as exception:
        raise HTTPException(status_code=404, detail={"code": str(exception)}) from exception
    except RuntimeError as exception:
        raise HTTPException(status_code=409, detail={"code": str(exception)}) from exception


@router.post(
    "/internal/v1/admin/semantic/operations/{operation_id}/retry",
    status_code=202,
)
async def semantic_admin_retry_operation(
    operation_id: UUID,
    context: AdminContext,
) -> dict[str, object]:
    """Acepta un nuevo intento de trabajo fallido o cancelado con actor e idempotencia de esta
    petición.

    Args:
        operation_id: UUID de la operación administrativa persistida.
        context: Actor auditado y clave de idempotencia normalizados por la dependencia común.

    Returns:
        UUID, estado y modelo asociado al reintento.

    Raises:
        HTTPException: 404 si no existe el original; 409 si no es reintentable o la clave está
            ocupada.
    """
    try:
        operation = await asyncio.to_thread(
            admin_store.operations.retry_operation,
            str(operation_id),
            actor=context.actor,
            idempotency_key=context.idempotency_key,
        )
        return {
            "operationId": operation["id"],
            "status": operation["status"],
            "modelId": operation.get("modelId"),
        }
    except LookupError as exception:
        raise HTTPException(status_code=404, detail={"code": str(exception)}) from exception
    except RuntimeError as exception:
        raise HTTPException(status_code=409, detail={"code": str(exception)}) from exception


@router.post(
    "/internal/v1/admin/semantic/models/{model_id}/warm",
)
async def semantic_admin_warm_model(model_id: UUID) -> dict[str, object]:
    """Carga y calienta el runtime local de una versión registrada sin modificar el modelo
    activo.

    Args:
        model_id: UUID del artefacto sobre el que se actúa; None para operaciones de
            repositorio.

    Returns:
        UUID, versión y warmed=True al completar la codificación de salud.

    Raises:
        HTTPException: 404 si faltan artefacto o registro; 409 con código seguro si falla el
            calentamiento.
    """
    try:
        artifact = await asyncio.to_thread(admin_store.models.artifact, str(model_id))
        model_version = artifact.get("model_version")
        if not model_version:
            raise LookupError("semantic_model_not_registered")
        model = await asyncio.to_thread(store.model, model_version)
        await asyncio.to_thread(runtime_for(model).warmup)
        return {"modelId": str(model_id), "modelVersion": model_version, "warmed": True}
    except LookupError as exception:
        raise HTTPException(status_code=404, detail={"code": str(exception)}) from exception
    except Exception as exception:
        raise HTTPException(
            status_code=409,
            detail={"code": "semantic_model_warmup_failed"},
        ) from exception


