"""Caracteriza métricas, fusión RRF, particiones reproducibles y reutilización de embeddings
entre variantes.
"""

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
    """El candidato presente en ambos rankings queda primero y la fusión conserva la unión de
    identidades.
    """
    ranked = reciprocal_rank_fusion(
        ["literal", "both"],
        ["semantic", "both"],
        semantic_weight=1.0,
    )
    assert ranked[0] == "both"
    assert set(ranked) == {"literal", "semantic", "both"}


def test_ndcg_rewards_relevant_results_near_the_top() -> None:
    """Mover un acierto a la primera posición incrementa la ganancia descontada normalizada."""
    assert ndcg(["a", "b"], {"a"}, 10) > ndcg(["b", "a"], {"a"}, 10)


def test_snapshot_splits_by_application_and_keeps_multiple_tag_positives() -> None:
    """Todas las consultas y positivos de una aplicación comparten partición y las etiquetas
    admiten varios relevantes.
    """
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
    """La misma semilla reproduce los negativos, excluye positivos y mantiene cada negativo
    dentro de su partición.
    """
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
    """Cambiar de semántica pura a RRF reutiliza embeddings y mantiene calidad perfecta en un
    corpus de dos documentos.
    """

    class FakeRuntime:
        """Produce vectores ortogonales y cuenta codificaciones para detectar trabajo repetido
        entre variantes.
        """

        document_calls = 0

        query_calls = 0


        def encode_documents(self, values: list[str]) -> list[list[float]]:
            """Comprueba el corpus recibido y registra una única preparación de sus dos vectores.

            Args:
                values: Textos de los dos documentos cuya codificación debe prepararse una
                    sola vez.

            Returns:
                dos vectores unitarios ortogonales.
            """
            self.document_calls += 1
            assert values == ["primera", "segunda"]
            return [[1.0, 0.0], [0.0, 1.0]]

        def encode_query(self, _query: str) -> list[float]:
            """Cuenta codificaciones individuales y sitúa la consulta sobre el primer documento.

            Args:
                _query: Consulta ignorada por el doble que siempre coincide con el primer
                    documento.

            Returns:
                vector unitario del primer documento.
            """
            self.query_calls += 1
            return [1.0, 0.0]

        def encode_queries(self, queries: list[str]) -> list[list[float]]:
            """Reutiliza el doble individual para medir también las consultas preparadas por
            lote.

            Args:
                queries: Consultas que el doble codifica en su orden de entrada.

            Returns:
                un vector por consulta.
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
    """La evaluación semántica omite el ranking léxico, publica progreso y rechaza después una
    fusión sin preparación léxica.

    Args:
        monkeypatch: Sustituciones locales de configuración o colaboradores que pytest
            restaura después de la prueba.
    """

    class FakeRuntime:
        """Ofrece vectores deterministas sin cargar pesos para aislar la preparación semántica."""

        registered = type("Registered", (), {"dimensions": 2})()


        def encode_documents(self, _values: list[str]) -> list[list[float]]:
            """Devuelve los dos documentos como vectores ortogonales.

            Args:
                _values: Textos ignorados por el doble que devuelve dos vectores ortogonales.

            Returns:
                matriz de dos vectores unitarios.
            """
            return [[1.0, 0.0], [0.0, 1.0]]

        def encode_query(self, _query: str) -> list[float]:
            """Hace coincidir cada consulta con el primer documento del escenario.

            Args:
                _query: Consulta ignorada por el doble que siempre coincide con el primer
                    documento.

            Returns:
                vector unitario del primer documento.
            """
            return [1.0, 0.0]

        def encode_queries(self, queries: list[str]) -> list[list[float]]:
            """Codifica el lote con el mismo doble individual usado para la medición de latencia.

            Args:
                queries: Consultas que el doble codifica en su orden de entrada.

            Returns:
                vectores en el orden de las consultas.
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
    """Volver a escribir entradas y semilla idénticas reutiliza hash, directorio y manifiesto sin
    cambiar sus bytes.

    Args:
        tmp_path: Directorio temporal exclusivo que pytest retira al finalizar el escenario.
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
    """Los tres modelos base fijan commits de cuarenta caracteres e incorporan esa revisión en su
    versión zero-shot.
    """
    assert len(MODEL_DEFINITIONS) == 3
    for definition in MODEL_DEFINITIONS:
        assert "/" in definition.repository
        assert len(definition.revision) == 40
        assert definition.zero_shot_version == (f"{definition.key}@{definition.revision}:zero-shot")
