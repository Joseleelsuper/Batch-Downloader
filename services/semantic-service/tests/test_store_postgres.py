"""Comprueba la proyección semántica mínima con PostgreSQL y pgvector."""
from __future__ import annotations

import os
import uuid
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta

import pytest

from app.config import Settings
from app.database import Database
from app.model_validation import ModelDescriptor
from app.store import SemanticStore

testcontainers_postgres = pytest.importorskip("testcontainers.postgres")
PostgresContainer = testcontainers_postgres.PostgresContainer


@pytest.fixture(scope="module")
def postgres_dsn() -> Iterator[str]:
    """Proporciona PostgreSQL configurado o un contenedor pgvector efímero."""
    configured = os.environ.get("SEMANTIC_TEST_POSTGRES_DSN")
    if configured:
        yield configured
        return
    try:
        container = PostgresContainer(
            image="pgvector/pgvector:pg16",
            username="semantic",
            password="semantic",
            dbname="semantic",
        )
        container.start()
    except Exception as exception:  # pragma: no cover - depende del host
        if os.environ.get("CI"):
            pytest.fail(f"Docker-backed pgvector is required in CI: {exception.__class__.__name__}")
        pytest.skip(f"Docker-backed pgvector unavailable: {exception.__class__.__name__}")
    try:
        yield container.get_connection_url().replace(
            "postgresql+psycopg2://",
            "postgresql://",
        )
    finally:
        container.stop()


@pytest.fixture()
def database(postgres_dsn: str) -> Iterator[Database]:
    database = Database(Settings(postgres_dsn_override=postgres_dsn))
    database.open()
    database.migrate()
    database.verify_schema()
    database.run(
        lambda connection: (
            connection.execute("DELETE FROM semantic_documents"),
            connection.execute("DELETE FROM embedding_models"),
            connection.execute("DELETE FROM semantic_worker_heartbeats"),
        )
    )
    try:
        yield database
    finally:
        database.run(
            lambda connection: (
                connection.execute("DELETE FROM semantic_documents"),
                connection.execute("DELETE FROM embedding_models"),
                connection.execute("DELETE FROM semantic_worker_heartbeats"),
            )
        )
        database.close()


def descriptor(version: str, dimensions: int) -> ModelDescriptor:
    return ModelDescriptor(version, dimensions, "query: ", "passage: ", 0.0)


def test_schema_removes_lifecycle_history_and_static_hnsw(database: Database) -> None:
    result = database.run(
        lambda connection: connection.execute(
            """
            SELECT
                to_regclass('public.benchmark_runs') AS benchmark_runs,
                to_regclass('public.semantic_operations') AS semantic_operations,
                to_regclass('public.semantic_model_artifacts') AS semantic_model_artifacts,
                to_regclass('public.semantic_remote_metadata_archive') AS remote_archive,
                EXISTS (
                    SELECT 1 FROM pg_indexes
                    WHERE tablename = 'software_embeddings' AND indexdef ILIKE '%USING hnsw%'
                ) AS has_hnsw
            """
        ).fetchone()
    )
    assert result == {
        "benchmark_runs": None,
        "semantic_operations": None,
        "semantic_model_artifacts": None,
        "remote_archive": None,
        "has_hnsw": False,
    }

    columns = database.run(
        lambda connection: connection.execute(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'embedding_models'
            """
        ).fetchall()
    )
    assert {row["column_name"] for row in columns} == {
        "model_version",
        "dimensions",
        "query_prefix",
        "passage_prefix",
        "minimum_similarity",
        "active",
        "created_at",
        "activated_at",
    }


def test_index_and_search_work_and_model_swap_purges_old_vectors(database: Database) -> None:
    store = SemanticStore(database)
    first = descriptor("local-model-v1", 2)
    store.ensure_model(first)
    app_id = str(uuid.UUID("00000000-0000-0000-0000-000000000001"))
    store.upsert_document_page(
        [{
            "appId": app_id,
            "contentHash": "a" * 64,
            "content": "Editor de código para Windows",
            "metadata": {"name": "Editor"},
        }],
        model_version=first.model_version,
        seen_at=datetime.now(UTC),
    )
    jobs = store.claim_jobs(
        model_version=first.model_version,
        owner="test-indexer",
        limit=10,
        lease_seconds=60,
    )
    store.complete_jobs(
        model_version=first.model_version,
        jobs=jobs,
        embeddings=[[1.0, 0.0]],
    )
    assert store.coverage_and_promote(first.model_version)["complete"] is True
    active = store.active_model()
    assert active is not None
    candidates, index_version = store.exact_search(
        model=active[0],
        query_vector=[1.0, 0.0],
        minimum_similarity=0.0,
        limit=10,
    )
    assert candidates[0]["appId"] == app_id
    assert index_version == active[1]

    second = descriptor("local-model-v2", 3)
    store.ensure_model(second)
    assert store.active_model() is None
    remaining = database.run(
        lambda connection: connection.execute(
            """
            SELECT
                (SELECT count(*) FROM embedding_models) AS models,
                (SELECT count(*) FROM software_embeddings) AS embeddings,
                (SELECT count(*) FROM embedding_jobs) AS jobs
            """
        ).fetchone()
    )
    assert remaining == {"models": 1, "embeddings": 0, "jobs": 0}


def test_partial_index_survives_add_update_remove_and_content_reversion(
    database: Database,
) -> None:
    """Cada lote publica la cobertura real y un contenido A-B-A vuelve a generar su vector."""
    store = SemanticStore(database)
    model = store.ensure_model(descriptor("partial-model", 2))
    first_id, second_id, run_id = (str(uuid.uuid4()) for _ in range(3))
    seen_at = datetime.now(UTC)

    def document(app_id: str, letter: str) -> dict[str, str]:
        return {"appId": app_id, "contentHash": letter * 64, "content": letter}

    def embed_next() -> None:
        jobs = store.claim_jobs(
            model_version=model.model_version, owner="test", limit=1, lease_seconds=60,
        )
        assert len(jobs) == 1
        store.complete_jobs(
            model_version=model.model_version, jobs=jobs, embeddings=[[1.0, 0.0]],
        )

    store.upsert_document_page(
        [document(first_id, "a"), document(second_id, "a")],
        model_version=model.model_version, seen_at=seen_at,
    )
    assert store.active_model() is None
    embed_next()
    partial = store.semantic_status()["index"]
    assert partial["indexed"] == 1 and partial["expected"] == 2
    assert partial["complete"] is False
    assert store.active_model() is not None
    assert store.acknowledge_scrape_run(model.model_version, run_id) is False
    assert store.last_scrape_run_id() is None
    embed_next()
    assert store.semantic_status()["index"]["indexVersion"] != partial["indexVersion"]
    assert store.acknowledge_scrape_run(model.model_version, run_id) is True
    assert SemanticStore(database).last_scrape_run_id() == run_id

    new_id = str(uuid.uuid4())
    store.upsert_document_page(
        [document(new_id, "a")], model_version=model.model_version, seen_at=seen_at,
    )
    assert store.active_model() is not None
    assert store.semantic_status()["index"]["indexed"] == 2
    assert store.semantic_status()["index"]["expected"] == 3
    embed_next()

    for letter in ("b", "a"):
        store.upsert_document_page(
            [document(first_id, letter)], model_version=model.model_version, seen_at=seen_at,
        )
        active = store.active_model()
        assert active is not None
        candidates, version = store.exact_search(
            model=active[0], query_vector=[1.0, 0.0], minimum_similarity=0, limit=10,
        )
        assert {row["appId"] for row in candidates} == {second_id, new_id}
        assert version == store.semantic_status()["index"]["indexVersion"]
        embed_next()
        assert store.semantic_status()["index"]["complete"] is True

    sweep = seen_at + timedelta(seconds=1)
    store.upsert_document_page(
        [document(first_id, "a")], model_version=model.model_version, seen_at=sweep,
    )
    assert store.finish_sweep(sweep, model_version=model.model_version) == 2
    assert store.semantic_status()["index"]["expected"] == 1
    assert store.active_model() is not None
    store.finish_sweep(sweep + timedelta(seconds=1), model_version=model.model_version)
    assert store.active_model() is None
    empty_run_id = str(uuid.uuid4())
    assert store.acknowledge_scrape_run(model.model_version, empty_run_id) is True
    assert store.last_scrape_run_id() == empty_run_id


def test_failed_embedding_batch_keeps_committed_partial_index(
    database: Database, monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Fallar después del primer lote no retira consultas ni reconoce el run pendiente."""
    from app import indexer as module

    indexer = module.SemanticIndexer(Settings(index_batch_size=1), database)
    model = descriptor("failing-model", 2)
    monkeypatch.setattr(module, "validate_model_directory", lambda *_args, **_kwargs: (model, {}))

    def synchronize(model_version, *_args):
        documents = [
            {"appId": str(uuid.uuid4()), "contentHash": letter * 64, "content": letter}
            for letter in ("a", "b")
        ]
        indexer.store.upsert_document_page(
            documents, model_version=model_version, seen_at=datetime.now(UTC),
        )
        return 2, 2, 0

    class Runtime:
        def __init__(self, *_args, **_kwargs):
            self.calls = 0

        def encode_documents(self, _documents):
            self.calls += 1
            if self.calls == 2:
                raise RuntimeError("embedding_failed")
            return [[1.0, 0.0]]

    monkeypatch.setattr(indexer, "_synchronize_documents", synchronize)
    monkeypatch.setattr(module, "EmbeddingRuntime", Runtime)
    with pytest.raises(RuntimeError, match="embedding_failed"):
        indexer.run_once(source_run_id=str(uuid.uuid4()))
    assert indexer.store.active_model() is not None
    assert indexer.store.semantic_status()["index"]["indexed"] == 1
    assert indexer.store.last_scrape_run_id() is None


def test_search_version_and_candidates_share_snapshot_during_publication(
    database: Database, monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Un commit entre la lectura de versión y candidatos no mezcla dos lotes."""
    store = SemanticStore(database)
    model = store.ensure_model(descriptor("snapshot-model", 2))
    app_id = str(uuid.uuid4())
    document = {"appId": app_id, "contentHash": "a" * 64, "content": "a"}
    store.upsert_document_page(
        [document], model_version=model.model_version, seen_at=datetime.now(UTC),
    )
    jobs = store.claim_jobs(
        model_version=model.model_version, owner="test", limit=1, lease_seconds=60,
    )
    store.complete_jobs(model_version=model.model_version, jobs=jobs, embeddings=[[1.0, 0.0]])
    before = store.semantic_status()["index"]["indexVersion"]
    original_run = database.run

    class ConcurrentPublication:
        def __init__(self, connection):
            self.connection = connection

        def execute(self, sql, *args):
            result = self.connection.execute(sql, *args)
            if "SELECT s.index_version" in sql:
                store.upsert_document_page(
                    [{**document, "contentHash": "b" * 64, "content": "b"}],
                    model_version=model.model_version, seen_at=datetime.now(UTC),
                )
            return result

    monkeypatch.setattr(
        database, "run", lambda callback: original_run(
            lambda connection: callback(ConcurrentPublication(connection)),
        ),
    )
    candidates, version = store.exact_search(
        model=model, query_vector=[1.0, 0.0], minimum_similarity=0, limit=10,
    )
    assert candidates[0]["appId"] == app_id
    assert version == before
    assert store.semantic_status()["index"]["indexVersion"] != before
    assert store.active_model() is None
