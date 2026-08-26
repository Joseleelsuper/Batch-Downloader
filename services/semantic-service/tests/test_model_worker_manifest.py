"""Contiene las pruebas de `test_model_worker_manifest`.
"""
from pathlib import Path

import pytest

from app.model_worker import SemanticModelWorker


def test_manifest_validation_ignores_runtime_cache_and_checks_expected_sizes(
    tmp_path: Path,
) -> None:
    """Comprueba que la caché auxiliar no altera el manifiesto del modelo.

    Args:
        tmp_path (Path): Directorio temporal proporcionado por pytest.
    """
    (tmp_path / "model.safetensors").write_bytes(b"weights")
    (tmp_path / "config.json").write_text("{}", encoding="utf-8")
    cache = tmp_path / ".cache" / "runtime"
    cache.mkdir(parents=True)
    cache_file = cache / "download.json"
    cache_file.write_text('{"timestamp": 1}', encoding="utf-8")
    expected = [
        {"path": "model.safetensors", "size": 7},
        {"path": "config.json", "size": 2},
    ]
    worker = object.__new__(SemanticModelWorker)

    first = worker._verify_and_digest(tmp_path, expected_files=expected)
    cache_file.write_text('{"timestamp": 2}', encoding="utf-8")
    second = worker._verify_and_digest(tmp_path, expected_files=expected)

    assert first == second
    with pytest.raises(
        RuntimeError,
        match="semantic_model_incompatible_manifest_size",
    ):
        worker._verify_and_digest(
            tmp_path,
            expected_files=[
                {"path": "model.safetensors", "size": 999},
                {"path": "config.json", "size": 2},
            ],
        )


def test_local_artifact_only_resolves_manual_or_managed_directories(
    tmp_path: Path,
) -> None:
    """Comprueba que la resolución de modelos se limita al almacenamiento local."""
    worker = object.__new__(SemanticModelWorker)
    worker.artifacts_root = tmp_path / "artifacts"
    worker.manual_root = tmp_path / "manual"
    revision = "a" * 40

    assert worker._local_artifact("model-id", "owner/model", revision) is None

    manual = worker.manual_root / "owner--model" / revision
    manual.mkdir(parents=True)

    assert worker._local_artifact("model-id", "owner/model", revision) == manual
