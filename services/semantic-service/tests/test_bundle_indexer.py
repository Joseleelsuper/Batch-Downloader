"""Un barrido incompleto o cambiante nunca purga documentos ni consume el checkpoint."""

from contextlib import nullcontext
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from app import bundle_indexer
from app.config import Settings


def scenario(monkeypatch, revisions, *, broken_page=False):
    store = Mock()
    store.checkpoint.return_value = None
    store.claim_jobs.return_value = []
    store.acknowledge.return_value = True
    monkeypatch.setattr(bundle_indexer, "BundleSemanticStore", lambda _db: store)
    monkeypatch.setattr(
        bundle_indexer,
        "load_model_manifest",
        lambda *_args, **_kwargs: SimpleNamespace(model_version="m"),
    )
    monkeypatch.setattr(
        bundle_indexer, "validate_model_directory", lambda *_args, **_kwargs: ("descriptor", None)
    )
    monkeypatch.setattr(bundle_indexer, "EmbeddingRuntime", lambda *_args, **_kwargs: Mock())
    values = iter(revisions)

    class Client:
        def __init__(self, **kwargs):
            assert kwargs["base_url"] == "http://core-api:8080"

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            pass

        def get(self, path, **_kwargs):
            if path.endswith("source-status"):
                return SimpleNamespace(
                    raise_for_status=lambda: None, json=lambda: {"revision": next(values)}
                )
            if broken_page:
                raise RuntimeError("core_unavailable")
            return SimpleNamespace(
                raise_for_status=lambda: None,
                json=lambda: {"documents": [], "nextAfterBundleId": None},
            )

    monkeypatch.setattr(bundle_indexer.httpx, "Client", Client)
    indexer = SimpleNamespace(
        settings=Settings(),
        database=SimpleNamespace(exclusive_background_operation=nullcontext),
        store=SimpleNamespace(ensure_model=lambda _: SimpleNamespace(model_version="m")),
    )
    return indexer, store


def test_failed_source_page_keeps_previous_projection(monkeypatch):
    indexer, store = scenario(monkeypatch, [1], broken_page=True)
    with pytest.raises(RuntimeError, match="core_unavailable"):
        bundle_indexer.synchronize_bundles(indexer)
    store.finish_sweep.assert_not_called()
    store.acknowledge.assert_not_called()


def test_changed_source_does_not_purge_or_acknowledge(monkeypatch):
    indexer, store = scenario(monkeypatch, [1, 2])
    assert bundle_indexer.synchronize_bundles(indexer) == {"retry": True}
    store.finish_sweep.assert_not_called()
    store.acknowledge.assert_not_called()


def test_same_revision_skips_embedding_work(monkeypatch):
    indexer, store = scenario(monkeypatch, [5])
    store.checkpoint.return_value = 5
    validator = Mock()
    monkeypatch.setattr(bundle_indexer, "validate_model_directory", validator)
    assert bundle_indexer.synchronize_bundles(indexer) is None
    store.upsert_page.assert_not_called()
    store.claim_jobs.assert_not_called()
    validator.assert_not_called()


def test_completed_sweep_acknowledges_only_stable_source(monkeypatch):
    indexer, store = scenario(monkeypatch, [5, 5, 5])
    assert bundle_indexer.synchronize_bundles(indexer)["acknowledged"] is True
    store.finish_sweep.assert_called_once()
    store.acknowledge.assert_called_once_with("m", 5)
