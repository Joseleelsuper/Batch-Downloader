"""La búsqueda conserva candidatos parciales y la versión de su instantánea de lectura."""
from types import SimpleNamespace

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
