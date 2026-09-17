"""Comprueba el contrato de la ranura local sin cargar PyTorch."""
from __future__ import annotations

import json

import pytest

from app.model_validation import ModelDescriptor, load_model_manifest, model_directory_ready


def manifest() -> dict[str, object]:
    return {
        "modelVersion": "local-model-v1",
        "dimensions": 2,
        "queryPrefix": "query: ",
        "passagePrefix": "passage: ",
        "minimumSimilarity": 0.5,
    }


def test_manifest_is_strict_and_model_directory_requires_safetensors(tmp_path) -> None:
    model_dir = tmp_path / "current"
    model_dir.mkdir()
    (model_dir / "batch-model.json").write_text(json.dumps(manifest()), encoding="utf-8")
    assert model_directory_ready(model_dir) is False
    (model_dir / "model.safetensors").write_bytes(b"fixture")
    assert model_directory_ready(model_dir) is True
    assert load_model_manifest(model_dir).model_version == "local-model-v1"


@pytest.mark.parametrize(
    "change",
    [
        {"dimensions": 0},
        {"minimumSimilarity": 2},
        {"unexpected": True},
    ],
)
def test_manifest_rejects_invalid_values(change: dict[str, object]) -> None:
    payload = manifest()
    payload.update(change)
    with pytest.raises(RuntimeError):
        ModelDescriptor.from_manifest(payload)
