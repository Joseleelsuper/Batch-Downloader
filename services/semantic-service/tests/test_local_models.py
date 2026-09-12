"""Protege la visibilidad de artefactos disponibles sin filtrar usando propiedades eliminadas por
la proyección pública.
"""
from datetime import UTC, datetime
from unittest.mock import Mock

import pytest

from app.admin_model_store import SemanticModelStore


def test_local_models_filter_private_paths_before_public_projection(tmp_path):
    """El listado local conserva únicamente directorios existentes, devuelve el modelo por UUID y
    omite su ruta interna.

    Args:
        tmp_path: Directorio temporal exclusivo que pytest retira al finalizar el escenario.
    """
    local = tmp_path / "prepared"
    local.mkdir()
    common = dict(
        metadata={}, display_name="Modelo", hf_repository="owner/model", resolved_revision="abc",
        artifact_state="ready", artifact_bytes=7, dimensions=384,
        query_prefix="", passage_prefix="",
        minimum_similarity=0.0, validation_message=None, created_at=datetime.now(UTC),
        downloaded_at=None, validated_at=None,
    )
    rows = [dict(common, id=name, local_path=path) for name, path in (
        ("local", str(local)), ("missing", str(tmp_path / "absent")), ("remote", None),
    )]
    database = Mock()
    connection = Mock()
    connection.execute.return_value.fetchall.return_value = rows
    database.run.side_effect = lambda callback: callback(connection)
    store = SemanticModelStore(database)
    assert [model["id"] for model in store.list_models()] == ["local", "missing", "remote"]
    assert [model["id"] for model in store.local_models()] == ["local"]
    assert store.local_model("local")["id"] == "local"
    assert "localPath" not in store.local_model("local")
    with pytest.raises(LookupError, match="semantic_model_not_found"):
        store.local_model("missing")
