from pathlib import Path

import pytest

from app.model_worker import SemanticModelWorker


def test_manifest_validation_ignores_hub_cache_and_checks_expected_sizes(
    tmp_path: Path,
) -> None:
    (tmp_path / "model.safetensors").write_bytes(b"weights")
    (tmp_path / "config.json").write_text("{}", encoding="utf-8")
    cache = tmp_path / ".cache" / "huggingface"
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
