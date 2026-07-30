"""Contiene las pruebas de `test_admin_benchmark`.
"""
import json

from app.benchmark_snapshot import evaluation_snapshot
from app.trainer import write_snapshot


def test_evaluation_snapshot_reuses_matching_catalog(tmp_path, monkeypatch) -> None:
    """Comprueba el escenario `evaluation_snapshot_reuses_matching_catalog`.

    Args:
        tmp_path (Any): Directorio temporal proporcionado por pytest.
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    documents = [
        {
            "app_id": "00000000-0000-0000-0000-000000000001",
            "content_hash": "a" * 64,
            "content": "Editor",
            "metadata": {"name": "Editor"},
        },
        {
            "app_id": "00000000-0000-0000-0000-000000000002",
            "content_hash": "b" * 64,
            "content": "Terminal",
            "metadata": {"name": "Terminal"},
        },
    ]
    queries = [
        {
            "query": "editor",
            "split": "validation",
            "positiveAppId": documents[0]["app_id"],
            "relevantAppIds": [documents[0]["app_id"]],
            "kind": "navigation-name",
        },
        {
            "query": "terminal",
            "split": "test",
            "positiveAppId": documents[1]["app_id"],
            "relevantAppIds": [documents[1]["app_id"]],
            "kind": "navigation-name",
        },
    ]
    dataset_hash, snapshot_dir = write_snapshot(
        documents,
        queries,
        root=tmp_path,
        seed=17,
    )
    monkeypatch.setattr(
        "app.benchmark_snapshot.build_query_snapshot",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AssertionError("the matching snapshot should be reused")
        ),
    )

    reused_hash, reused_dir, reused_queries, catalog_hash = evaluation_snapshot(
        documents,
        root=tmp_path,
        seed=17,
    )

    assert reused_hash == dataset_hash
    assert reused_dir == snapshot_dir
    assert [row["query"] for row in reused_queries] == ["editor", "terminal"]
    manifest = json.loads(
        (snapshot_dir / "manifest.json").read_text(encoding="utf-8")
    )
    assert manifest["catalogSnapshotHash"] == catalog_hash


def test_evaluation_snapshot_rebuilds_when_content_changes(
    tmp_path,
    monkeypatch,
) -> None:
    """Comprueba el escenario `evaluation_snapshot_rebuilds_when_content_changes`.

    Args:
        tmp_path (Any): Directorio temporal proporcionado por pytest.
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    original = [
        {
            "app_id": "00000000-0000-0000-0000-000000000001",
            "content_hash": "a" * 64,
            "content": "Editor",
            "metadata": {"name": "Editor"},
        },
        {
            "app_id": "00000000-0000-0000-0000-000000000002",
            "content_hash": "b" * 64,
            "content": "Terminal",
            "metadata": {"name": "Terminal"},
        },
    ]
    write_snapshot(
        original,
        [
            {
                "query": "editor",
                "split": "validation",
                "positiveAppId": original[0]["app_id"],
                "relevantAppIds": [original[0]["app_id"]],
                "kind": "navigation-name",
            }
        ],
        root=tmp_path,
        seed=17,
    )
    changed = [dict(row) for row in original]
    changed[0] = {
        **changed[0],
        "content_hash": "c" * 64,
        "content": "Editor actualizado",
    }
    generated = [
        {
            "query": "editor actualizado",
            "split": "test",
            "positiveAppId": changed[0]["app_id"],
            "relevantAppIds": [changed[0]["app_id"]],
            "kind": "navigation-name",
        }
    ]
    calls = []
    monkeypatch.setattr(
        "app.benchmark_snapshot.build_query_snapshot",
        lambda documents, seed: calls.append((documents, seed)) or generated,
    )

    _dataset_hash, _snapshot_dir, queries, _catalog_hash = evaluation_snapshot(
        changed,
        root=tmp_path,
        seed=17,
    )

    assert calls == [(changed, 17)]
    assert [row["query"] for row in queries] == ["editor actualizado"]
