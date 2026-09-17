"""Comprueba el runtime local de embeddings sin cargar un modelo real."""
from __future__ import annotations

import sys
from types import ModuleType

import numpy as np
import pytest

from app.embeddings import EmbeddingRuntime, RegisteredModel, vector_literal


class FakeModel:
    def __init__(self, dimension: object) -> None:
        self.dimension = dimension
        self.calls: list[tuple[list[str], dict[str, object]]] = []

    def get_embedding_dimension(self) -> object:
        return self.dimension

    def encode(self, values: list[str], **kwargs: object) -> np.ndarray:
        self.calls.append((values, kwargs))
        return np.ones((len(values), 2), dtype=np.float32)


def registered() -> RegisteredModel:
    return RegisteredModel("local-model-v1", 2, "query: ", "passage: ", 0.5)


def install_sentence_transformer(monkeypatch: pytest.MonkeyPatch, model: FakeModel) -> None:
    module = ModuleType("sentence_transformers")
    module.SentenceTransformer = lambda *_args, **_kwargs: model
    monkeypatch.setitem(sys.modules, "sentence_transformers", module)


def test_registered_model_defaults_and_vector_literal() -> None:
    model = RegisteredModel.from_row(
        {
            "model_version": "local-model-v1",
            "dimensions": 2,
            "query_prefix": None,
            "passage_prefix": None,
            "minimum_similarity": None,
        }
    )

    assert model.query_prefix == ""
    assert model.passage_prefix == ""
    assert model.minimum_similarity == 0.0
    assert vector_literal([1, 0.5]) == "[1,0.5]"


def test_runtime_loads_warms_and_encodes(tmp_path, monkeypatch: pytest.MonkeyPatch) -> None:
    model = FakeModel(2)
    install_sentence_transformer(monkeypatch, model)
    model_dir = tmp_path / "model"
    model_dir.mkdir()
    runtime = EmbeddingRuntime(
        registered(),
        model_dir=str(model_dir),
        cache_dir=str(tmp_path / "cache"),
        device="cpu",
        batch_size=0,
    )

    runtime.load()
    runtime.warmup()
    runtime.warmup()

    assert runtime.encode_query("query") == [1.0, 1.0]
    assert runtime.encode_documents(["one", "two"]) == [[1.0, 1.0], [1.0, 1.0]]
    assert runtime._encode([]).shape == (0, 2)
    assert [call[0] for call in model.calls] == [
        ["query: healthcheck"],
        ["query: query"],
        ["passage: one", "passage: two"],
    ]


@pytest.mark.parametrize(
    ("dimension", "message"),
    [
        (None, "embedding_dimension_invalid"),
        ("2", "embedding_dimension_invalid"),
        (3, "embedding_dimension_mismatch:3:2"),
    ],
)
def test_runtime_rejects_invalid_or_mismatched_dimension(
    tmp_path,
    monkeypatch: pytest.MonkeyPatch,
    dimension: object,
    message: str,
) -> None:
    model = FakeModel(dimension)
    install_sentence_transformer(monkeypatch, model)
    model_dir = tmp_path / "model"
    model_dir.mkdir()
    runtime = EmbeddingRuntime(
        registered(),
        model_dir=str(model_dir),
        cache_dir=str(tmp_path / "cache"),
        device="cpu",
    )

    with pytest.raises(RuntimeError, match=message):
        runtime.load()
    assert runtime._model is None


def test_runtime_requires_a_local_model_directory(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    model = FakeModel(2)
    install_sentence_transformer(monkeypatch, model)
    runtime = EmbeddingRuntime(
        registered(),
        model_dir=str(tmp_path / "missing"),
        cache_dir=str(tmp_path / "cache"),
        device="cpu",
    )

    with pytest.raises(RuntimeError, match="model_artifact_missing"):
        runtime.load()
