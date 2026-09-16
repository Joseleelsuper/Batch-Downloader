"""Atiende búsquedas semánticas internas con modelo activo, capacidad acotada y señal de
truncamiento.
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, Depends, HTTPException

from app.http_context import (
    require_internal_service_token,
    require_search_capacity,
    runtime_for,
    settings,
    store,
)
from app.schemas import SemanticCandidate, SemanticSearchRequest, SemanticSearchResponse

router = APIRouter()


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
    completo.
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
        HTTPException: 503 si no hay índice activo completo o se agota el tiempo de
            codificación; los guardas rechazan credencial o capacidad antes de entrar.

    See Also:
        app.store.SemanticStore.active_model: Exige correspondencia entre modelo y estado del
            índice.
        app.store.SemanticStore.exact_search: Consulta vectores con los filtros del modelo.
    """
    active = await asyncio.to_thread(store.active_model)
    if active is None:
        raise HTTPException(
            status_code=503,
            detail={"code": "semantic_index_not_ready"},
        )
    model, index_version = active
    try:
        runtime = runtime_for(model)
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
    except RuntimeError as exception:
        raise HTTPException(
            status_code=503,
            detail={"code": "semantic_index_not_ready"},
        ) from exception
    truncated = len(rows) > functional_limit
    return SemanticSearchResponse(
        candidates=[
            SemanticCandidate.model_validate(row)
            for row in rows[:functional_limit]
        ],
        modelVersion=model.model_version,
        indexVersion=index_version,
        truncated=truncated,
    )

