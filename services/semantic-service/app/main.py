"""Configura el punto de entrada del servicio semántico.
"""
from __future__ import annotations

import asyncio
import secrets
from collections import OrderedDict
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Annotated
from uuid import UUID

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import PlainTextResponse, JSONResponse
from huggingface_hub.errors import HfHubHTTPError
from psycopg_pool import PoolTimeout

from app.admin_schemas import (
    ActivateModelRequest,
    BenchmarkModelsRequest,
    DownloadModelRequest,
)
from app.admin_store import SemanticAdminStore
from app.config import get_settings
from app.database import Database
from app.embeddings import EmbeddingRuntime
from app.huggingface_catalog import HuggingFaceCatalog
from app.schemas import SemanticSearchRequest, SemanticSearchResponse
from app.store import SemanticStore

INTERNAL_SERVICE_TOKEN_HEADER = "X-Internal-Service-Token"
"""Constante que define `INTERNAL_SERVICE_TOKEN_HEADER`.
"""

settings = get_settings()
"""Estado global asociado a `settings`.
"""
database = Database(settings)
"""Estado global asociado a `database`.
"""
store = SemanticStore(database)
"""Estado global asociado a `store`.
"""
admin_store = SemanticAdminStore(database)
"""Estado global asociado a `admin_store`.
"""
hub_catalog = HuggingFaceCatalog()
"""Estado global asociado a `hub_catalog`.
"""
runtime_cache: OrderedDict[str, EmbeddingRuntime] = OrderedDict()
"""Estado global asociado a `runtime_cache`.
"""
search_slots = asyncio.Semaphore(settings.search_concurrency)
"""Plazas de búsqueda que protegen CPU y el pool de PostgreSQL."""


@asynccontextmanager
async def lifespan(_app: FastAPI):
    """Ejecuta la operación `lifespan`.

    Args:
        _app (FastAPI): Valor de `_app` utilizado por la operación.

    Yields:
        Any: Elemento producido por la operación.
    """
    await asyncio.to_thread(database.open)
    await asyncio.to_thread(database.migrate)
    try:
        yield
    finally:
        await asyncio.to_thread(database.close)


app = FastAPI(title="Batch Downloader Semantic Service", lifespan=lifespan)
"""Estado global asociado a `app`.
"""


@app.exception_handler(PoolTimeout)
async def database_capacity_exhausted(
    _request: Request,
    _exception: PoolTimeout,
) -> JSONResponse:
    """Convierte el agotamiento del pool en una respuesta temporal explícita."""
    return JSONResponse(
        status_code=503,
        content={"code": "service_busy", "message": "Capacidad temporal agotada."},
        headers={"Retry-After": "1"},
    )


def runtime_for(model):
    """Ejecuta la operación `runtime_for`.

    Args:
        model (Any): Modelo utilizado por la operación.
    """
    runtime = runtime_cache.get(model.model_version)
    if runtime is None:
        runtime = EmbeddingRuntime(
            model,
            device=settings.device,
            cache_dir=settings.model_cache_dir,
        )
        runtime_cache[model.model_version] = runtime
        while len(runtime_cache) > 2:
            runtime_cache.popitem(last=False)
    else:
        runtime_cache.move_to_end(model.model_version)
    return runtime


async def create_admin_operation(**kwargs: object) -> dict[str, object]:
    """Crea la operación `admin_operation`.

    Args:
        **kwargs (object): Valor de `kwargs` utilizado por la operación.

    Returns:
        dict[str, object]: Mapa con los datos producidos por la operación.

    Throws:
        HTTPException: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    try:
        return await asyncio.to_thread(admin_store.create_operation, **kwargs)
    except RuntimeError as exception:
        raise HTTPException(
            status_code=409,
            detail={"code": str(exception)},
        ) from exception


async def require_internal_service_token(
    provided_token: Annotated[
        str | None,
        Header(alias=INTERNAL_SERVICE_TOKEN_HEADER),
    ] = None,
) -> None:
    """Ejecuta la operación `require_internal_service_token`.

    Args:
        provided_token (str | None): Valor de `provided_token` utilizado por la operación.

    Throws:
        HTTPException: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    expected = settings.internal_service_token.get_secret_value()
    if not expected or not secrets.compare_digest(provided_token or "", expected):
        raise HTTPException(status_code=401, detail={"code": "invalid_internal_token"})


async def require_search_capacity() -> AsyncIterator[None]:
    """Reserva una de las dos plazas de búsqueda con espera acotada."""
    try:
        await asyncio.wait_for(
            search_slots.acquire(),
            timeout=settings.search_capacity_wait_seconds,
        )
    except TimeoutError as exception:
        raise HTTPException(
            status_code=503,
            detail={"code": "service_busy"},
            headers={"Retry-After": "1"},
        ) from exception
    try:
        yield
    finally:
        search_slots.release()


@app.get("/semantic/health")
async def health() -> dict[str, object]:
    """Ejecuta la operación `health`.

    Returns:
        dict[str, object]: Mapa con los datos producidos por la operación.
    """
    database_ready = await asyncio.to_thread(database.healthy)
    active = await asyncio.to_thread(store.active_model) if database_ready else None
    search_ready = active is not None
    if active is not None:
        try:
            await asyncio.to_thread(runtime_for(active[0]).warmup)
        except Exception:
            search_ready = False
    return {
        "status": "ok" if database_ready else "degraded",
        "service": "semantic-service",
        "database": database_ready,
        "searchReady": search_ready,
        "modelVersion": active[0].model_version if search_ready and active else None,
        "indexVersion": active[1] if search_ready and active else None,
    }


@app.get(
    "/internal/v1/metrics",
    response_class=PlainTextResponse,
    dependencies=[Depends(require_internal_service_token)],
)
async def internal_metrics() -> PlainTextResponse:
    """Expone los contadores del pool en formato Prometheus."""
    lines: list[str] = []
    for key, value in database.metrics().items():
        metric = "semantic_db_pool_" + key.replace("-", "_")
        lines.extend((f"# TYPE {metric} gauge", f"{metric} {value}"))
    return PlainTextResponse(
        "\n".join(lines) + "\n",
        media_type="text/plain; version=0.0.4",
    )


@app.post(
    "/internal/v1/semantic/search",
    response_model=SemanticSearchResponse,
    response_model_by_alias=True,
    dependencies=[
        Depends(require_internal_service_token),
        Depends(require_search_capacity),
    ],
    responses={401: {}, 503: {}},
)
async def semantic_search(request: SemanticSearchRequest) -> SemanticSearchResponse:
    """Ejecuta la operación `semantic_search`.

    Args:
        request (SemanticSearchRequest): Solicitud recibida por la operación.

    Returns:
        SemanticSearchResponse: Resultado producido por la operación.

    Throws:
        HTTPException: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    active = await asyncio.to_thread(store.active_model)
    if active is None:
        raise HTTPException(
            status_code=503,
            detail={"code": "semantic_index_not_ready"},
        )
    model, index_version = active
    runtime = runtime_for(model)
    try:
        vector = await asyncio.wait_for(
            asyncio.to_thread(runtime.encode_query, request.query.strip()),
            timeout=settings.search_timeout_seconds,
        )
        functional_limit = min(request.limit, settings.candidate_limit)
        rows = await asyncio.to_thread(
            store.exact_search,
            model=model,
            query_vector=vector,
            minimum_similarity=(
                request.minimum_similarity
                if request.minimum_similarity is not None
                else model.minimum_similarity
            ),
            limit=functional_limit + 1,
        )
    except TimeoutError as exception:
        raise HTTPException(
            status_code=503,
            detail={"code": "semantic_search_timeout"},
        ) from exception
    truncated = len(rows) > functional_limit
    return SemanticSearchResponse(
        candidates=rows[:functional_limit],
        modelVersion=model.model_version,
        indexVersion=index_version,
        truncated=truncated,
    )


admin_dependencies = [Depends(require_internal_service_token)]
"""Estado global asociado a `admin_dependencies`.
"""


@app.get(
    "/internal/v1/admin/semantic/overview",
    dependencies=admin_dependencies,
)
async def semantic_admin_overview() -> dict[str, object]:
    """Ejecuta la operación `semantic_admin_overview`.

    Returns:
        dict[str, object]: Mapa con los datos producidos por la operación.
    """
    return await asyncio.to_thread(
        admin_store.overview,
        settings.model_cache_dir,
        model_max_bytes=settings.model_max_bytes,
        model_min_free_bytes=settings.model_min_free_bytes,
    )


@app.get(
    "/internal/v1/admin/semantic/models",
    dependencies=admin_dependencies,
)
async def semantic_admin_models() -> list[dict[str, object]]:
    """Ejecuta la operación `semantic_admin_models`.

    Returns:
        list[dict[str, object]]: Colección de elementos obtenidos por la operación.
    """
    return await asyncio.to_thread(admin_store.models)


@app.get(
    "/internal/v1/admin/semantic/models/{model_id}",
    dependencies=admin_dependencies,
)
async def semantic_admin_model(model_id: UUID) -> dict[str, object]:
    """Ejecuta la operación `semantic_admin_model`.

    Args:
        model_id (UUID): Identificador de `model` utilizado por la operación.

    Returns:
        dict[str, object]: Mapa con los datos producidos por la operación.

    Throws:
        HTTPException: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    try:
        return await asyncio.to_thread(admin_store.model, str(model_id))
    except LookupError as exception:
        raise HTTPException(
            status_code=404,
            detail={"code": str(exception)},
        ) from exception


@app.get(
    "/internal/v1/admin/semantic/benchmarks",
    dependencies=admin_dependencies,
)
async def semantic_admin_benchmarks(
    limit: int = Query(default=50, ge=1, le=200),
) -> list[dict[str, object]]:
    """Ejecuta la operación `semantic_admin_benchmarks`.

    Args:
        limit (int): Número máximo de elementos que se recuperarán.

    Returns:
        list[dict[str, object]]: Colección de elementos obtenidos por la operación.
    """
    return await asyncio.to_thread(admin_store.benchmarks, limit)


@app.get(
    "/internal/v1/admin/semantic/hugging-face/models",
    dependencies=admin_dependencies,
)
async def semantic_admin_hub_models(
    query: str = Query(min_length=2, max_length=120),
    limit: int = Query(default=25, ge=1, le=50),
) -> list[dict[str, object]]:
    """Ejecuta la operación `semantic_admin_hub_models`.

    Args:
        query (str): Valor de `query` utilizado por la operación.
        limit (int): Número máximo de elementos que se recuperarán.

    Returns:
        list[dict[str, object]]: Colección de elementos obtenidos por la operación.

    Throws:
        HTTPException: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    try:
        return await asyncio.to_thread(hub_catalog.search, query, limit=limit)
    except HfHubHTTPError as exception:
        raise HTTPException(
            status_code=502,
            detail={"code": "hugging_face_unavailable"},
        ) from exception
    except httpx.HTTPError as exception:
        raise HTTPException(
            status_code=502,
            detail={"code": "hugging_face_unavailable"},
        ) from exception


@app.get(
    "/internal/v1/admin/semantic/hugging-face/model",
    dependencies=admin_dependencies,
)
async def semantic_admin_hub_model(
    repository: str = Query(min_length=3, max_length=200),
    revision: str | None = Query(default=None, max_length=200),
) -> dict[str, object]:
    """Ejecuta la operación `semantic_admin_hub_model`.

    Args:
        repository (str): Valor de `repository` utilizado por la operación.
        revision (str | None): Valor de `revision` utilizado por la operación.

    Returns:
        dict[str, object]: Mapa con los datos producidos por la operación.

    Throws:
        HTTPException: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    if not _valid_repository(repository):
        raise HTTPException(
            status_code=422,
            detail={"code": "invalid_hugging_face_repository"},
        )
    try:
        detail = await asyncio.to_thread(
            hub_catalog.detail,
            repository,
            revision,
        )
        return detail.as_dict()
    except HfHubHTTPError as exception:
        status = (
            404
            if getattr(exception.response, "status_code", None) == 404
            else 502
        )
        raise HTTPException(
            status_code=status,
            detail={"code": "hugging_face_model_unavailable"},
        ) from exception
    except httpx.HTTPError as exception:
        raise HTTPException(
            status_code=502,
            detail={"code": "hugging_face_model_unavailable"},
        ) from exception


@app.post(
    "/internal/v1/admin/semantic/downloads",
    status_code=202,
    dependencies=admin_dependencies,
)
async def semantic_admin_download(
    request: DownloadModelRequest,
    actor: Annotated[str | None, Header(alias="X-Admin-Actor")] = None,
    idempotency_key: Annotated[
        str | None,
        Header(alias="Idempotency-Key"),
    ] = None,
) -> dict[str, object]:
    """Ejecuta la operación `semantic_admin_download`.

    Args:
        request (DownloadModelRequest): Solicitud recibida por la operación.
        actor (str | None): Valor de `actor` utilizado por la operación.
        idempotency_key (str | None): Valor de `idempotency_key` utilizado por la operación.

    Returns:
        dict[str, object]: Mapa con los datos producidos por la operación.

    Throws:
        HTTPException: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    try:
        detail = await asyncio.to_thread(
            hub_catalog.detail,
            request.repository,
            request.revision,
        )
    except HfHubHTTPError as exception:
        raise HTTPException(
            status_code=502,
            detail={"code": "hugging_face_model_unavailable"},
        ) from exception
    except httpx.HTTPError as exception:
        raise HTTPException(
            status_code=502,
            detail={"code": "hugging_face_model_unavailable"},
        ) from exception
    if not detail.compatible:
        raise HTTPException(
            status_code=422,
            detail={
                "code": detail.compatibility_reason or "semantic_model_incompatible"
            },
        )
    if not detail.license and not request.acknowledge_unknown_license:
        raise HTTPException(
            status_code=409,
            detail={"code": "model_license_acknowledgement_required"},
        )
    if (
        (
            not request.query_prefix
            or not request.passage_prefix
            or detail.suggested_query_prefix is None
            or detail.suggested_passage_prefix is None
            or detail.suggested_minimum_similarity is None
        )
        and not request.acknowledge_missing_configuration
    ):
        raise HTTPException(
            status_code=409,
            detail={"code": "model_configuration_acknowledgement_required"},
        )
    if detail.estimated_bytes > settings.model_max_bytes:
        raise HTTPException(
            status_code=422,
            detail={"code": "semantic_model_too_large"},
        )
    try:
        artifact, operation = await asyncio.to_thread(
            admin_store.create_download_operation,
            repository=detail.repository,
            requested_revision=request.revision,
            resolved_revision=detail.sha,
            display_name=detail.display_name,
            metadata=detail.as_dict(),
            query_prefix=request.query_prefix,
            passage_prefix=request.passage_prefix,
            minimum_similarity=request.minimum_similarity,
            actor=_admin_actor(actor),
            idempotency_key=_idempotency(idempotency_key),
            request_payload=request.model_dump(by_alias=True),
            progress_total=detail.estimated_bytes,
        )
    except RuntimeError as exception:
        raise HTTPException(
            status_code=409,
            detail={"code": str(exception)},
        ) from exception
    if artifact["artifact_state"] == "ready" and operation["status"] == "queued":
        await asyncio.to_thread(
            admin_store.complete_operation,
            str(operation["id"]),
            {"modelId": str(artifact["id"]), "alreadyDownloaded": True},
        )
        operation = {
            **operation,
            "status": "succeeded",
        }
    return {
        "operationId": str(operation["id"]),
        "status": operation["status"],
        "modelId": str(artifact["id"]),
    }


@app.post(
    "/internal/v1/admin/semantic/benchmarks",
    status_code=202,
    dependencies=admin_dependencies,
)
async def semantic_admin_start_benchmark(
    request: BenchmarkModelsRequest,
    actor: Annotated[str | None, Header(alias="X-Admin-Actor")] = None,
    idempotency_key: Annotated[
        str | None,
        Header(alias="Idempotency-Key"),
    ] = None,
) -> dict[str, object]:
    """Ejecuta la operación `semantic_admin_start_benchmark`.

    Args:
        request (BenchmarkModelsRequest): Solicitud recibida por la operación.
        actor (str | None): Valor de `actor` utilizado por la operación.
        idempotency_key (str | None): Valor de `idempotency_key` utilizado por la operación.

    Returns:
        dict[str, object]: Mapa con los datos producidos por la operación.

    Throws:
        HTTPException: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    model_ids = [str(model_id) for model_id in request.model_ids]
    active_id = await asyncio.to_thread(admin_store.active_model_id)
    if active_id and active_id not in model_ids:
        model_ids.insert(0, active_id)
    if len(model_ids) > 4:
        raise HTTPException(
            status_code=422,
            detail={"code": "benchmark_supports_at_most_four_models"},
        )
    for model_id in model_ids:
        try:
            model = await asyncio.to_thread(admin_store.model, model_id)
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
        actor=_admin_actor(actor),
        idempotency_key=_idempotency(idempotency_key),
        request_payload={"modelIds": model_ids},
        model_id=model_ids[0],
        progress_total=len(model_ids),
        progress_unit="models",
    )
    return {"operationId": str(operation["id"]), "status": operation["status"]}


@app.post(
    "/internal/v1/admin/semantic/models/{model_id}/prepare",
    status_code=202,
    dependencies=admin_dependencies,
)
async def semantic_admin_prepare(
    model_id: UUID,
    actor: Annotated[str | None, Header(alias="X-Admin-Actor")] = None,
    idempotency_key: Annotated[
        str | None,
        Header(alias="Idempotency-Key"),
    ] = None,
) -> dict[str, object]:
    """Ejecuta la operación `semantic_admin_prepare`.

    Args:
        model_id (UUID): Identificador de `model` utilizado por la operación.
        actor (str | None): Valor de `actor` utilizado por la operación.
        idempotency_key (str | None): Valor de `idempotency_key` utilizado por la operación.

    Returns:
        dict[str, object]: Mapa con los datos producidos por la operación.

    Throws:
        HTTPException: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    try:
        model = await asyncio.to_thread(admin_store.model, str(model_id))
    except LookupError as exception:
        raise HTTPException(status_code=404, detail={"code": str(exception)}) from exception
    if model["active"]:
        raise HTTPException(
            status_code=409,
            detail={"code": "semantic_active_model_cannot_be_prepared"},
        )
    benchmark = await asyncio.to_thread(
        admin_store.eligible_benchmark,
        str(model_id),
    )
    if not benchmark:
        raise HTTPException(
            status_code=409,
            detail={"code": "semantic_benchmark_required_or_stale"},
        )
    operation = await create_admin_operation(
        kind="prepare",
        actor=_admin_actor(actor),
        idempotency_key=_idempotency(idempotency_key),
        request_payload={"modelId": str(model_id)},
        model_id=str(model_id),
        model_version=model.get("modelVersion"),
        progress_unit="documents",
    )
    return {"operationId": str(operation["id"]), "status": operation["status"]}


@app.post(
    "/internal/v1/admin/semantic/models/{model_id}/activate",
    status_code=202,
    dependencies=admin_dependencies,
)
async def semantic_admin_activate(
    model_id: UUID,
    request: ActivateModelRequest,
    actor: Annotated[str | None, Header(alias="X-Admin-Actor")] = None,
    idempotency_key: Annotated[
        str | None,
        Header(alias="Idempotency-Key"),
    ] = None,
) -> dict[str, object]:
    """Ejecuta la operación `semantic_admin_activate`.

    Args:
        model_id (UUID): Identificador de `model` utilizado por la operación.
        request (ActivateModelRequest): Solicitud recibida por la operación.
        actor (str | None): Valor de `actor` utilizado por la operación.
        idempotency_key (str | None): Valor de `idempotency_key` utilizado por la operación.

    Returns:
        dict[str, object]: Mapa con los datos producidos por la operación.

    Throws:
        HTTPException: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    try:
        model = await asyncio.to_thread(admin_store.model, str(model_id))
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
        actor=_admin_actor(actor),
        idempotency_key=_idempotency(idempotency_key),
        request_payload=payload,
        model_id=str(model_id),
        model_version=model.get("modelVersion"),
    )
    return {"operationId": str(operation["id"]), "status": operation["status"]}


@app.delete(
    "/internal/v1/admin/semantic/models/{model_id}",
    status_code=202,
    dependencies=admin_dependencies,
)
async def semantic_admin_delete(
    model_id: UUID,
    actor: Annotated[str | None, Header(alias="X-Admin-Actor")] = None,
    idempotency_key: Annotated[
        str | None,
        Header(alias="Idempotency-Key"),
    ] = None,
) -> dict[str, object]:
    """Ejecuta la operación `semantic_admin_delete`.

    Args:
        model_id (UUID): Identificador de `model` utilizado por la operación.
        actor (str | None): Valor de `actor` utilizado por la operación.
        idempotency_key (str | None): Valor de `idempotency_key` utilizado por la operación.

    Returns:
        dict[str, object]: Mapa con los datos producidos por la operación.

    Throws:
        HTTPException: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    try:
        model = await asyncio.to_thread(admin_store.model, str(model_id))
    except LookupError as exception:
        raise HTTPException(status_code=404, detail={"code": str(exception)}) from exception
    if model["active"]:
        raise HTTPException(
            status_code=409,
            detail={"code": "active_semantic_model_cannot_be_deleted"},
        )
    try:
        await asyncio.to_thread(
            admin_store.assert_model_deletable,
            str(model_id),
        )
    except RuntimeError as exception:
        raise HTTPException(
            status_code=409,
            detail={"code": str(exception)},
        ) from exception
    operation = await create_admin_operation(
        kind="delete",
        actor=_admin_actor(actor),
        idempotency_key=_idempotency(idempotency_key),
        request_payload={"modelId": str(model_id)},
        model_id=str(model_id),
        model_version=model.get("modelVersion"),
    )
    return {"operationId": str(operation["id"]), "status": operation["status"]}


@app.get(
    "/internal/v1/admin/semantic/operations",
    dependencies=admin_dependencies,
)
async def semantic_admin_operations(
    limit: int = Query(default=100, ge=1, le=250),
    active: bool = False,
) -> list[dict[str, object]]:
    """Ejecuta la operación `semantic_admin_operations`.

    Args:
        limit (int): Número máximo de elementos que se recuperarán.
        active (bool): Valor de `active` utilizado por la operación.

    Returns:
        list[dict[str, object]]: Colección de elementos obtenidos por la operación.
    """
    return await asyncio.to_thread(
        admin_store.operations,
        limit=limit,
        active_only=active,
    )


@app.get(
    "/internal/v1/admin/semantic/operations/{operation_id}",
    dependencies=admin_dependencies,
)
async def semantic_admin_operation(operation_id: UUID) -> dict[str, object]:
    """Ejecuta la operación `semantic_admin_operation`.

    Args:
        operation_id (UUID): Identificador de `operation` utilizado por la operación.

    Returns:
        dict[str, object]: Mapa con los datos producidos por la operación.

    Throws:
        HTTPException: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    try:
        return await asyncio.to_thread(admin_store.operation, str(operation_id))
    except LookupError as exception:
        raise HTTPException(status_code=404, detail={"code": str(exception)}) from exception


@app.delete(
    "/internal/v1/admin/semantic/operations/{operation_id}",
    dependencies=admin_dependencies,
)
async def semantic_admin_cancel_operation(operation_id: UUID) -> dict[str, object]:
    """Ejecuta la operación `semantic_admin_cancel_operation`.

    Args:
        operation_id (UUID): Identificador de `operation` utilizado por la operación.

    Returns:
        dict[str, object]: Mapa con los datos producidos por la operación.

    Throws:
        HTTPException: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    try:
        return await asyncio.to_thread(admin_store.request_cancel, str(operation_id))
    except LookupError as exception:
        raise HTTPException(status_code=404, detail={"code": str(exception)}) from exception
    except RuntimeError as exception:
        raise HTTPException(status_code=409, detail={"code": str(exception)}) from exception


@app.post(
    "/internal/v1/admin/semantic/operations/{operation_id}/retry",
    status_code=202,
    dependencies=admin_dependencies,
)
async def semantic_admin_retry_operation(
    operation_id: UUID,
    actor: Annotated[str | None, Header(alias="X-Admin-Actor")] = None,
    idempotency_key: Annotated[
        str | None,
        Header(alias="Idempotency-Key"),
    ] = None,
) -> dict[str, object]:
    """Ejecuta la operación `semantic_admin_retry_operation`.

    Args:
        operation_id (UUID): Identificador de `operation` utilizado por la operación.
        actor (str | None): Valor de `actor` utilizado por la operación.
        idempotency_key (str | None): Valor de `idempotency_key` utilizado por la operación.

    Returns:
        dict[str, object]: Mapa con los datos producidos por la operación.

    Throws:
        HTTPException: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    try:
        operation = await asyncio.to_thread(
            admin_store.retry_operation,
            str(operation_id),
            actor=_admin_actor(actor),
            idempotency_key=_idempotency(idempotency_key),
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


@app.post(
    "/internal/v1/admin/semantic/models/{model_id}/warm",
    dependencies=admin_dependencies,
)
async def semantic_admin_warm_model(model_id: UUID) -> dict[str, object]:
    """Ejecuta la operación `semantic_admin_warm_model`.

    Args:
        model_id (UUID): Identificador de `model` utilizado por la operación.

    Returns:
        dict[str, object]: Mapa con los datos producidos por la operación.

    Throws:
        HTTPException: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    try:
        artifact = await asyncio.to_thread(admin_store.artifact, str(model_id))
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


def _admin_actor(value: str | None) -> str:
    """Ejecuta el paso interno `_admin_actor`.

    Args:
        value (str | None): Valor que debe procesarse.

    Returns:
        str: Resultado producido por la operación.
    """
    normalized = (value or "admin").strip()
    return normalized[:120] or "admin"


def _idempotency(value: str | None) -> str | None:
    """Ejecuta el paso interno `_idempotency`.

    Args:
        value (str | None): Valor que debe procesarse.

    Returns:
        str | None: Resultado producido por la operación.
    """
    normalized = (value or "").strip()
    return normalized[:200] or None


def _valid_repository(repository: str) -> bool:
    """Ejecuta el paso interno `_valid_repository`.

    Args:
        repository (str): Valor de `repository` utilizado por la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    parts = repository.strip().split("/")
    allowed = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._")
    return len(parts) == 2 and all(part and set(part) <= allowed for part in parts)
