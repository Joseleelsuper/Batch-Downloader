"""Aísla las fases del entrenador para comprobar reutilización, restricciones smoke y limpieza de
artefactos parciales.
"""

from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from app import trainer


def test_evaluation_reuses_preparation_and_preserves_order(tmp_path, monkeypatch) -> None:
    """Las cuatro variantes comparten una sola preparación y conservan su orden e identidad de
    modelo.

    Args:
        tmp_path: Directorio temporal exclusivo que pytest retira al finalizar el escenario.
        monkeypatch: Sustituciones locales de configuración o colaboradores que pytest
            restaura después de la prueba.
    """
    data = trainer.TrainingDataset("hash", tmp_path, [], [], [], [])
    prepare = Mock(return_value=object())
    evaluate = Mock(side_effect=lambda *_args, **kwargs: {"variant": kwargs["variant"]})
    monkeypatch.setattr(trainer, "EmbeddingRuntime", Mock())
    monkeypatch.setattr(trainer, "prepare_runtime_evaluation", prepare)
    monkeypatch.setattr(trainer, "evaluate_prepared_runtime", evaluate)
    settings = SimpleNamespace(device="cpu", model_cache_dir=str(tmp_path), index_batch_size=3)
    rows = trainer.evaluate_training_variants(
        SimpleNamespace(model_version="version"), "key", "zero-shot", data, settings, Mock(),
    )
    assert prepare.call_count == 1
    assert [row["variant"] for row in rows] == [
        "key:zero-shot", "key:zero-shot:hybrid:0.5", "key:zero-shot:hybrid:1.0",
        "key:zero-shot:hybrid:1.5",
    ]
    assert all(row["modelVersion"] == "version" for row in rows)


def test_smoke_metrics_cannot_select_a_production_model(tmp_path, monkeypatch) -> None:
    """Una ejecución smoke desactiva candidatos aunque la puntuación los considere elegibles y
    nunca escribe la selección.

    Args:
        tmp_path: Directorio temporal exclusivo que pytest retira al finalizar el escenario.
        monkeypatch: Sustituciones locales de configuración o colaboradores que pytest
            restaura después de la prueba.
    """
    data = trainer.TrainingDataset("hash", tmp_path, [], [], [], [])
    candidate = {"eligible": True, "totalScore": 1, "modelVersion": "candidate"}
    monkeypatch.setattr(trainer, "score_variants", lambda *_args, **_kwargs: [candidate])
    runtime = Mock()
    monkeypatch.setattr(trainer, "EmbeddingRuntime", runtime)
    store = Mock()
    rows, selected = trainer.select_training_winner(
        [{"exactMrrAt1": 1}], data, SimpleNamespace(), store, smoke=True,
    )
    assert rows == [{**candidate, "eligible": False}]
    assert selected is None
    runtime.assert_not_called()
    store.select_model.assert_not_called()


def test_failed_training_removes_partial_artifact(tmp_path, monkeypatch) -> None:
    """Un fallo del entrenador retira su directorio temporal y evita registrar una versión
    incompleta.

    Args:
        tmp_path: Directorio temporal exclusivo que pytest retira al finalizar el escenario.
        monkeypatch: Sustituciones locales de configuración o colaboradores que pytest
            restaura después de la prueba.
    """
    data = trainer.TrainingDataset("abcdef123456", tmp_path, [], [], [], [])
    settings = SimpleNamespace(trainer_max_steps=4, model_cache_dir=str(tmp_path))
    store = Mock()
    monkeypatch.setattr(trainer, "train_model", Mock(side_effect=RuntimeError("training_failed")))
    with pytest.raises(RuntimeError, match="training_failed"):
        trainer.prepare_trained_model(
            SimpleNamespace(revision="revision"), Mock(), "key", data, settings, store, smoke=False,
        )
    assert list((tmp_path / "trained").iterdir()) == []
    store.register_trained_model.assert_not_called()
