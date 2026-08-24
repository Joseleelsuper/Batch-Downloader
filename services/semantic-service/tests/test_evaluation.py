"""Contiene las pruebas de `test_evaluation`."""

import pytest

from app.evaluation import ndcg, reciprocal_rank_fusion
from app.model_registry import MODEL_DEFINITIONS
from app.runtime_evaluation import (
    evaluate_prepared_runtime,
    prepare_runtime_evaluation,
)
from app.training_dataset import (
    build_query_snapshot,
    split_for_app,
    write_snapshot,
)


def test_rrf_fuses_both_rankings_deterministically() -> None:
    """Comprueba el escenario `rrf_fuses_both_rankings_deterministically`."""
    ranked = reciprocal_rank_fusion(
        ["literal", "both"],
        ["semantic", "both"],
        semantic_weight=1.0,
    )
    assert ranked[0] == "both"
    assert set(ranked) == {"literal", "semantic", "both"}


def test_ndcg_rewards_relevant_results_near_the_top() -> None:
    """Comprueba el escenario `ndcg_rewards_relevant_results_near_the_top`."""
    assert ndcg(["a", "b"], {"a"}, 10) > ndcg(["b", "a"], {"a"}, 10)


def test_snapshot_splits_by_application_and_keeps_multiple_tag_positives() -> None:
    """Comprueba el escenario `snapshot_splits_by_application_and_keeps_multiple_tag_positives`."""
    documents = [
        {
            "app_id": "00000000-0000-0000-0000-000000000001",
            "content": "Editor de código",
            "metadata": {
                "name": "Uno",
                "packageId": "Vendor.Uno",
                "tags": ["desarrollo"],
                "operatingSystems": ["windows"],
                "shortDescription": "Edición rápida de código fuente",
            },
        },
        {
            "app_id": "00000000-0000-0000-0000-000000000002",
            "content": "IDE",
            "metadata": {
                "name": "Dos",
                "packageId": "Vendor.Dos",
                "tags": ["desarrollo"],
                "operatingSystems": ["linux"],
                "longDescription": "Entorno integrado para programar proyectos",
            },
        },
    ]

    rows = build_query_snapshot(documents, 7)

    assert all(row["split"] == split_for_app(row["positiveAppId"], 7) for row in rows)
    intention = next(row for row in rows if row["kind"] == "intent")
    assert intention["relevantAppIds"] == [
        "00000000-0000-0000-0000-000000000001",
        "00000000-0000-0000-0000-000000000002",
    ]
    assert any(row["kind"] == "description-intent" for row in rows)
    assert all(
        split_for_app(relevant, 7) == row["split"]
        for row in rows
        for relevant in row["relevantAppIds"]
    )


def test_hard_negatives_are_reproducible_and_never_cross_splits_or_positives() -> None:
    """Comprueba negativos reproducibles, sin positivos ni cruces entre particiones."""
    documents = [
        {
            "app_id": f"00000000-0000-0000-0000-{index:012d}",
            "content_hash": f"{index:064x}",
            "content": f"Herramienta de productividad {index}",
            "metadata": {
                "name": f"Aplicación {index}",
                "packageId": f"Vendor.App{index}",
                "publisher": f"Editor {index % 4}",
                "tags": [f"categoria-{index % 3}"],
                "operatingSystems": ["windows" if index % 2 else "linux"],
            },
        }
        for index in range(1, 31)
    ]

    first = build_query_snapshot(documents, 17)
    second = build_query_snapshot(documents, 17)

    assert first == second
    assert any(row["hardNegativeAppIds"] for row in first)
    for row in first:
        for negative_app_id in row["hardNegativeAppIds"]:
            assert negative_app_id not in row["relevantAppIds"]
            assert split_for_app(negative_app_id, 17) == row["split"]


def test_rrf_weights_reuse_one_embedding_evaluation() -> None:
    """Comprueba el escenario `rrf_weights_reuse_one_embedding_evaluation`."""

    class FakeRuntime:
        """Agrupa los escenarios de prueba de `FakeRuntime`."""

        document_calls = 0
        """Atributo de clase `document_calls` de `FakeRuntime`.
        """
        query_calls = 0
        """Atributo de clase `query_calls` de `FakeRuntime`.
        """

        def encode_documents(self, values: list[str]) -> list[list[float]]:
            """Ejecuta `encode_documents` dentro de `FakeRuntime`.

            Args:
                values (list[str]): Valor de `values` utilizado por la operación.

            Returns:
                list[list[float]]: Colección de elementos obtenidos por la operación.
            """
            self.document_calls += 1
            assert values == ["primera", "segunda"]
            return [[1.0, 0.0], [0.0, 1.0]]

        def encode_query(self, _query: str) -> list[float]:
            """Ejecuta `encode_query` dentro de `FakeRuntime`.

            Args:
                _query (str): Valor de `_query` utilizado por la operación.

            Returns:
                list[float]: Colección de elementos obtenidos por la operación.
            """
            self.query_calls += 1
            return [1.0, 0.0]

        def encode_queries(self, queries: list[str]) -> list[list[float]]:
            """Ejecuta `encode_queries` dentro de `FakeRuntime`.

            Args:
                queries (list[str]): Valor de `queries` utilizado por la operación.

            Returns:
                list[list[float]]: Colección de elementos obtenidos por la operación.
            """
            return [self.encode_query(query) for query in queries]

    runtime = FakeRuntime()
    documents = [
        {
            "app_id": "a",
            "content": "primera",
            "metadata": {"name": "Primera"},
        },
        {
            "app_id": "b",
            "content": "segunda",
            "metadata": {"name": "Segunda"},
        },
    ]
    queries = [
        {
            "query": "primera",
            "positiveAppId": "a",
            "relevantAppIds": ["a"],
            "kind": "navigation-name",
        }
    ]

    prepared = prepare_runtime_evaluation(runtime, documents, queries)
    semantic = evaluate_prepared_runtime(
        prepared,
        variant="semantic",
        semantic_weight=None,
    )
    hybrid = evaluate_prepared_runtime(
        prepared,
        variant="hybrid",
        semantic_weight=1.0,
    )

    assert runtime.document_calls == 1
    assert runtime.query_calls == 2
    assert semantic["ndcgAt10"] == 1.0
    assert hybrid["ndcgAt10"] == 1.0


def test_semantic_only_evaluation_skips_literal_ranking(monkeypatch) -> None:
    """Comprueba el escenario `semantic_only_evaluation_skips_literal_ranking`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """

    class FakeRuntime:
        """Agrupa los escenarios de prueba de `FakeRuntime`."""

        registered = type("Registered", (), {"dimensions": 2})()
        """Atributo de clase `registered` de `FakeRuntime`.
        """

        def encode_documents(self, _values: list[str]) -> list[list[float]]:
            """Ejecuta `encode_documents` dentro de `FakeRuntime`.

            Args:
                _values (list[str]): Valor de `_values` utilizado por la operación.

            Returns:
                list[list[float]]: Colección de elementos obtenidos por la operación.
            """
            return [[1.0, 0.0], [0.0, 1.0]]

        def encode_query(self, _query: str) -> list[float]:
            """Ejecuta `encode_query` dentro de `FakeRuntime`.

            Args:
                _query (str): Valor de `_query` utilizado por la operación.

            Returns:
                list[float]: Colección de elementos obtenidos por la operación.
            """
            return [1.0, 0.0]

        def encode_queries(self, queries: list[str]) -> list[list[float]]:
            """Ejecuta `encode_queries` dentro de `FakeRuntime`.

            Args:
                queries (list[str]): Valor de `queries` utilizado por la operación.

            Returns:
                list[list[float]]: Colección de elementos obtenidos por la operación.
            """
            return [self.encode_query(query) for query in queries]

    monkeypatch.setattr(
        "app.runtime_evaluation.lexical_rank",
        lambda *_args, **_kwargs: pytest.fail(
            "semantic-only benchmarks must not calculate literal rankings"
        ),
    )
    progress: list[tuple[str, int, int]] = []
    prepared = prepare_runtime_evaluation(
        FakeRuntime(),
        [
            {"app_id": "a", "content": "primera"},
            {"app_id": "b", "content": "segunda"},
        ],
        [
            {
                "query": "primera",
                "positiveAppId": "a",
                "relevantAppIds": ["a"],
                "kind": "navigation-name",
            }
        ],
        include_lexical=False,
        progress=lambda stage, current, total: progress.append((stage, current, total)),
    )

    semantic = evaluate_prepared_runtime(
        prepared,
        variant="semantic",
        semantic_weight=None,
    )

    assert semantic["ndcgAt10"] == 1.0
    assert progress[-1] == ("ranking", 1, 1)
    with pytest.raises(RuntimeError, match="lexical_rankings_not_prepared"):
        evaluate_prepared_runtime(
            prepared,
            variant="hybrid",
            semantic_weight=1.0,
        )


def test_snapshot_persists_catalog_and_is_immutable(tmp_path) -> None:
    """Comprueba el escenario `snapshot_persists_catalog_and_is_immutable`.

    Args:
        tmp_path (Any): Directorio temporal proporcionado por pytest.
    """
    documents = [
        {
            "app_id": "a",
            "content_hash": "a" * 64,
            "content": "Editor",
            "metadata": {"name": "Editor"},
        }
    ]
    queries = [
        {
            "query": "editor",
            "split": "train",
            "positiveAppId": "a",
            "relevantAppIds": ["a"],
        }
    ]

    dataset_hash, snapshot_dir = write_snapshot(
        documents,
        queries,
        root=tmp_path,
        seed=7,
    )
    manifest = (snapshot_dir / "manifest.json").read_text(encoding="utf-8")
    second_hash, second_dir = write_snapshot(
        documents,
        queries,
        root=tmp_path,
        seed=7,
    )

    assert dataset_hash == second_hash
    assert snapshot_dir == second_dir
    assert (snapshot_dir / "documents.jsonl").is_file()
    assert (snapshot_dir / "train.jsonl").is_file()
    assert (snapshot_dir / "manifest.json").read_text(encoding="utf-8") == manifest


def test_training_model_definitions_have_immutable_local_artifact_identifiers() -> None:
    """Comprueba que los modelos locales mantienen identificadores reproducibles."""
    assert len(MODEL_DEFINITIONS) == 3
    for definition in MODEL_DEFINITIONS:
        assert "/" in definition.repository
        assert len(definition.revision) == 40
        assert definition.zero_shot_version == (f"{definition.key}@{definition.revision}:zero-shot")
