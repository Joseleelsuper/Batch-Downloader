"""Comprueba la proyección semántica mínima con PostgreSQL y pgvector."""
from __future__ import annotations

import os
import uuid
from collections.abc import Iterator
from datetime import UTC, datetime

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
    assert store.exact_search(
        model=active[0],
        query_vector=[1.0, 0.0],
        minimum_similarity=0.0,
        limit=10,
    )[0]["appId"] == app_id

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
