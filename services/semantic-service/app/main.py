from __future__ import annotations

import asyncio
import secrets
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException

from app.config import get_settings
from app.database import Database
from app.embeddings import EmbeddingRuntime
from app.schemas import SemanticSearchRequest, SemanticSearchResponse
from app.store import SemanticStore

INTERNAL_SERVICE_TOKEN_HEADER = "X-Internal-Service-Token"

settings = get_settings()
database = Database(settings)
store = SemanticStore(database)
runtime_cache: dict[str, EmbeddingRuntime] = {}


@asynccontextmanager
async def lifespan(_app: FastAPI):
    await asyncio.to_thread(database.open)
    await asyncio.to_thread(database.migrate)
    try:
        yield
    finally:
        await asyncio.to_thread(database.close)


app = FastAPI(title="Batch Downloader Semantic Service", lifespan=lifespan)


def runtime_for(model):
    runtime = runtime_cache.get(model.model_version)
    if runtime is None:
        runtime = EmbeddingRuntime(
            model,
            device=settings.device,
            cache_dir=settings.model_cache_dir,
        )
        runtime_cache.clear()
        runtime_cache[model.model_version] = runtime
    return runtime


async def require_internal_service_token(
    provided_token: Annotated[
        str | None,
        Header(alias=INTERNAL_SERVICE_TOKEN_HEADER),
    ] = None,
) -> None:
    expected = settings.internal_service_token.get_secret_value()
    if not expected or not secrets.compare_digest(provided_token or "", expected):
        raise HTTPException(status_code=401, detail={"code": "invalid_internal_token"})


@app.get("/semantic/health")
async def health() -> dict[str, object]:
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


@app.post(
    "/internal/v1/semantic/search",
    response_model=SemanticSearchResponse,
    response_model_by_alias=True,
    dependencies=[Depends(require_internal_service_token)],
    responses={401: {}, 503: {}},
)
async def semantic_search(request: SemanticSearchRequest) -> SemanticSearchResponse:
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
                else settings.minimum_similarity
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
        rrfWeight=model.rrf_weight,
        truncated=truncated,
    )
