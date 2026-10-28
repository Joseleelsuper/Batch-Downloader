"""Atiende búsquedas semánticas internas con modelo activo, capacidad acotada y señal de
truncamiento.
"""
from __future__ import annotations

import asyncio
from typing import Any, Protocol

from fastapi import APIRouter, Depends, HTTPException

from app.embeddings import RegisteredModel
from app.http_context import (
    require_internal_service_token,
    require_search_capacity,
    runtime_for,
    settings,
    store,
)
from app.schemas import SemanticSearchRequest, SemanticSearchResponse

router = APIRouter()


class SearchCollection(Protocol):
    """Ofrece únicamente el modelo disponible y la búsqueda de una colección independiente."""

    def active_model(self) -> tuple[RegisteredModel, str] | None: ...

    def exact_search(
        self, *, model: RegisteredModel, query_vector: list[float],
        minimum_similarity: float, limit: int,
    ) -> tuple[list[dict[str, Any]], str]: ...


async def _search_collection(
    request: SemanticSearchRequest, collection: SearchCollection, *, timeout_code: str,
) -> dict[str, Any]:
    """Comparte codificación y límites; cada ruta conserva sus candidatos y código de timeout."""
    active = await asyncio.to_thread(collection.active_model)
    if active is None:
        raise HTTPException(status_code=503, detail={"code": "semantic_index_not_ready"})
    model, _ = active
    try:
        vector = await asyncio.wait_for(
            asyncio.to_thread(runtime_for(model).encode_query, request.query.strip()),
            timeout=settings.search_timeout_seconds,
        )
        limit = min(request.limit, settings.candidate_limit)
        rows, version = await asyncio.to_thread(
            collection.exact_search, model=model, query_vector=vector,
            minimum_similarity=request.minimum_similarity
            if request.minimum_similarity is not None else model.minimum_similarity,
            limit=limit + 1,
        )
    except TimeoutError as error:
        raise HTTPException(status_code=503, detail={"code": timeout_code}) from error
    except RuntimeError as error:
        raise HTTPException(status_code=503, detail={"code": "semantic_index_not_ready"}) from error
    return {
        "candidates": rows[:limit], "modelVersion": model.model_version,
        "indexVersion": version, "truncated": len(rows) > limit,
    }


@router.post(
    "/internal/v1/semantic/bundles/search",
    dependencies=[Depends(require_internal_service_token), Depends(require_search_capacity)],
    responses={401: {}, 503: {}},
)
async def bundle_semantic_search(request: SemanticSearchRequest) -> dict:
    """Devuelve solamente candidatos de bundles; Core aplica visibilidad y filtros actuales."""
    from app.bundle_store import BundleSemanticStore

    return await _search_collection(
        request, BundleSemanticStore(store.database), timeout_code="semantic_index_not_ready",
    )


@router.post(
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
    """Codifica la consulta con el modelo activo y busca candidatos por similitud en su índice
    disponible, aunque queden aplicaciones por indexar.
    Solicita un resultado adicional para detectar truncamiento y permite a Core decidir el
    fallback
    léxico de toda la petición cuando no puede utilizar la respuesta semántica.

    Args:
        request: Consulta, límite y similitud mínima opcional; los valores se validan en el
            esquema de entrada.

    Returns:
        candidatos hasta el límite funcional, versiones del modelo e índice y señal de
            truncamiento.

    Raises:
        HTTPException: 503 si no hay vectores vigentes o se agota el tiempo de
            codificación; los guardas rechazan credencial o capacidad antes de entrar.

    See Also:
        app.store.SemanticStore.active_model: Exige correspondencia entre modelo y estado del
            índice.
        app.store.SemanticStore.exact_search: Consulta vectores con los filtros del modelo.
    """
    return SemanticSearchResponse.model_validate(await _search_collection(
        request, store, timeout_code="semantic_search_timeout",
    ))

