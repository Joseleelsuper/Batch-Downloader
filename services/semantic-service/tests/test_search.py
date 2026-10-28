"""La búsqueda conserva candidatos parciales y la versión de su instantánea de lectura."""
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from fastapi import HTTPException

from app import search_router
from app.schemas import SemanticSearchRequest


@pytest.mark.asyncio
async def test_bundle_search_returns_bundle_ids_and_snapshot(monkeypatch):
    from app.bundle_store import BundleSemanticStore

    model = SimpleNamespace(model_version="m", minimum_similarity=0.5)
    monkeypatch.setattr(BundleSemanticStore, "active_model", lambda _self: (model, "old"))
    monkeypatch.setattr(
        BundleSemanticStore,
        "exact_search",
        lambda _self, **_kwargs: (
            [{"bundleId": "bundle", "rank": 1, "similarity": 0.9}],
            "current",
        ),
    )
    monkeypatch.setattr(
        search_router, "runtime_for", lambda _: SimpleNamespace(encode_query=lambda _q: [1.0])
    )
    result = await search_router.bundle_semantic_search(SemanticSearchRequest(query="editor"))
    assert result["indexVersion"] == "current"
    assert result["candidates"][0]["bundleId"] == "bundle"
    assert "appId" not in result["candidates"][0]


@pytest.mark.asyncio
async def test_unavailable_bundle_index_does_not_use_app_candidates(monkeypatch):
    from app.bundle_store import BundleSemanticStore

    monkeypatch.setattr(BundleSemanticStore, "active_model", lambda _self: None)
    with pytest.raises(HTTPException) as error:
        await search_router.bundle_semantic_search(SemanticSearchRequest(query="editor"))
    assert error.value.status_code == 503


@pytest.mark.asyncio
async def test_search_uses_available_snapshot_version(monkeypatch: pytest.MonkeyPatch) -> None:
    model = SimpleNamespace(model_version="model", minimum_similarity=0.5)
    monkeypatch.setattr(search_router.store, "active_model", lambda: (model, "before-encoding"))
    monkeypatch.setattr(
        search_router, "runtime_for", lambda _model: SimpleNamespace(encode_query=lambda _q: [1.0]),
    )
    monkeypatch.setattr(
        search_router.store, "exact_search",
        lambda **_kwargs: ([{"appId": "indexed-app", "rank": 1, "similarity": 0.9}], "new-lot"),
    )
    result = await search_router.semantic_search(SemanticSearchRequest(query="editor"))
    assert result.index_version == "new-lot"
    assert result.candidates[0].app_id == "indexed-app"
    assert result.truncated is False


@pytest.mark.asyncio
async def test_search_without_valid_vectors_remains_unavailable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(search_router.store, "active_model", lambda: None)
    request = SemanticSearchRequest(query="editor")
    with pytest.raises(HTTPException) as error:
        await search_router.semantic_search(request)
    assert error.value.status_code == 503


@pytest.mark.asyncio
@pytest.mark.parametrize("bundle", [False, True])
async def test_each_collection_preserves_limit_threshold_and_candidate_identity(
    monkeypatch, bundle,
):
    from app.bundle_store import BundleSemanticStore

    identity = "bundleId" if bundle else "appId"
    model = SimpleNamespace(model_version="m", minimum_similarity=0.5)
    search = Mock(return_value=(
        [{identity: "first", "rank": 1, "similarity": 0.9},
         {identity: "extra", "rank": 2, "similarity": 0.8}], "snapshot",
    ))
    collection = BundleSemanticStore if bundle else search_router.store
    monkeypatch.setattr(collection, "active_model", lambda *_args: (model, "before"))
    monkeypatch.setattr(collection, "exact_search", search)
    encoder = Mock(return_value=[1.0])
    monkeypatch.setattr(
        search_router, "runtime_for", lambda _: SimpleNamespace(encode_query=encoder),
    )
    route = search_router.bundle_semantic_search if bundle else search_router.semantic_search
    result = await route(SemanticSearchRequest(query=" editor ", limit=1, minimumSimilarity=0.7))
    response = result if bundle else result.model_dump(by_alias=True)
    assert response["candidates"] == [{identity: "first", "rank": 1, "similarity": 0.9}]
    assert response["truncated"] is True
    assert response["indexVersion"] == "snapshot"
    encoder.assert_called_once_with("editor")
    search.assert_called_once_with(
        model=model, query_vector=[1.0], minimum_similarity=0.7, limit=2,
    )


@pytest.mark.asyncio
@pytest.mark.parametrize("bundle,code", [
    (False, "semantic_search_timeout"), (True, "semantic_index_not_ready"),
])
async def test_collection_timeout_keeps_its_existing_error_code(monkeypatch, bundle, code):
    from app.bundle_store import BundleSemanticStore

    model = SimpleNamespace(model_version="m", minimum_similarity=0.5)
    collection = BundleSemanticStore if bundle else search_router.store
    monkeypatch.setattr(collection, "active_model", lambda *_args: (model, "index"))
    monkeypatch.setattr(search_router, "runtime_for", lambda _: SimpleNamespace(
        encode_query=Mock(side_effect=TimeoutError),
    ))
    route = search_router.bundle_semantic_search if bundle else search_router.semantic_search
    with pytest.raises(HTTPException) as error:
        await route(SemanticSearchRequest(query="editor"))
    assert error.value.status_code == 503
    assert error.value.detail == {"code": code}
