"""Comprueba decisiones de horario, reintento y consumo de finales del Scraper."""
from types import SimpleNamespace

import httpx
import pytest

from app.config import Settings
from app.indexer import SemanticIndexer


@pytest.mark.parametrize(
    ("window", "source", "last", "runs"),
    ((False, "new", "old", True), (False, "same", "same", False),
     (False, None, None, False), (True, None, None, True), (True, "same", "same", True)),
)
def test_finished_run_bypasses_window_and_duplicates_wait(
    monkeypatch: pytest.MonkeyPatch, window, source, last, runs,
) -> None:
    indexer = SemanticIndexer(Settings())
    indexer.settings = SimpleNamespace(background_window_open=lambda: window)
    monkeypatch.setattr(indexer, "_latest_scrape_run_id", lambda: source)
    monkeypatch.setattr(indexer.store, "last_scrape_run_id", lambda: last)
    calls = []
    monkeypatch.setattr(
        indexer, "run_once", lambda **kwargs: calls.append(kwargs) or {"complete": True},
    )
    report = indexer.run_scheduled()
    assert calls == ([{"source_run_id": source}] if runs else [])
    assert (report is not None) is runs


def test_completion_during_indexing_and_failure_are_retried(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    indexer = SemanticIndexer(Settings())
    indexer.settings = SimpleNamespace(background_window_open=lambda: False)
    state = {"source": "first", "last": None}
    monkeypatch.setattr(indexer, "_latest_scrape_run_id", lambda: state["source"])
    monkeypatch.setattr(indexer.store, "last_scrape_run_id", lambda: state["last"])
    calls = []

    def run_once(*, source_run_id):
        calls.append(source_run_id)
        if len(calls) == 1:
            raise RuntimeError("temporary")
        if len(calls) == 2:
            return {"complete": False}
        state["last"] = source_run_id
        state["source"] = "second"
        return {"complete": True}

    monkeypatch.setattr(indexer, "run_once", run_once)
    with pytest.raises(RuntimeError, match="temporary"):
        indexer.run_scheduled()
    assert indexer.run_scheduled() == {"complete": False}
    assert indexer.run_scheduled() == {"complete": True}
    assert indexer.run_scheduled() == {"complete": True}
    assert indexer.run_scheduled() is None
    assert calls == ["first", "first", "first", "second"]


def test_source_status_uses_internal_auth_and_propagates_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    indexer = SemanticIndexer(Settings(internal_service_token="test-token"))
    requested = []

    def get(url, *, headers, timeout):
        requested.append((url, headers, timeout))
        return httpx.Response(503, request=httpx.Request("GET", url))

    monkeypatch.setattr(httpx, "get", get)
    with pytest.raises(httpx.HTTPStatusError):
        indexer._latest_scrape_run_id()
    assert requested == [(
        "http://scraper-api:8000/internal/v1/semantic/source-status",
        {"X-Internal-Service-Token": "test-token"}, 30,
    )]
