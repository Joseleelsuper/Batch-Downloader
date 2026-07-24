from __future__ import annotations

import os
from collections.abc import Iterator
from datetime import datetime, timedelta, timezone

import pytest

from app.config import Settings
from app.database import Database
from app.model_registry import MODELS_BY_KEY
from app.store import SemanticStore

testcontainers_postgres = pytest.importorskip("testcontainers.postgres")
PostgresContainer = testcontainers_postgres.PostgresContainer


@pytest.fixture(scope="module")
def postgres_dsn() -> Iterator[str]:
    container = PostgresContainer(
        image="pgvector/pgvector:pg16",
        username="semantic",
        password="semantic",
        dbname="semantic",
    )
    try:
        container.start()
    except Exception as exception:  # pragma: no cover - host runtime dependent
        if os.environ.get("CI"):
            pytest.fail(
                "Docker-backed pgvector is required in CI: "
                f"{exception.__class__.__name__}"
            )
        pytest.skip(
            f"Docker-backed pgvector unavailable: {exception.__class__.__name__}"
        )
    try:
        yield container.get_connection_url().replace(
            "postgresql+psycopg2://",
            "postgresql://",
        )
    finally:
        container.stop()


def test_migration_sync_dimension_contract_search_and_atomic_promotion(
    postgres_dsn: str,
) -> None:
    settings = Settings(postgres_dsn_override=postgres_dsn)
    database = Database(settings)
    database.open()
    try:
        database.migrate()
        store = SemanticStore(database)
        model_version = MODELS_BY_KEY["multilingual-e5-base"].zero_shot_version
        seen_at = datetime.now(timezone.utc)
        document = {
            "appId": "00000000-0000-0000-0000-000000000001",
            "contentHash": "a" * 64,
            "content": "Editor de código para Windows",
            "metadata": {
                "name": "Editor",
                "packageId": "Vendor.Editor",
                "tags": ["desarrollo"],
            },
        }

        assert store.upsert_document_page(
            [document],
            model_version=model_version,
            seen_at=seen_at,
        ) == 1
        assert store.upsert_document_page(
            [document],
            model_version=model_version,
            seen_at=seen_at,
        ) == 0
        jobs = store.claim_jobs(
            model_version=model_version,
            owner="test-indexer",
            limit=10,
            lease_seconds=60,
        )
        assert len(jobs) == 1
        vector = [1.0] + [0.0] * 767
        store.complete_jobs(
            model_version=model_version,
            jobs=jobs,
            embeddings=[vector],
        )
        coverage = store.coverage_and_promote(model_version)
        assert coverage["complete"] is True
        active = store.active_model()
        assert active is not None
        assert active[0].model_version == model_version
        assert store.exact_search(
            model=active[0],
            query_vector=vector,
            minimum_similarity=0.0,
            limit=10,
        )[0]["appId"] == document["appId"]

        store.select_model(model_version, rrf_weight=1.5)
        store.coverage_and_promote(model_version)
        active = store.active_model()
        assert active is not None
        assert active[0].rrf_weight == 1.5
        unavailable_version = MODELS_BY_KEY[
            "paraphrase-multilingual-MiniLM-L12-v2"
        ].zero_shot_version
        with pytest.raises(RuntimeError, match="model_coverage_incomplete"):
            store.activate_complete_model(unavailable_version)
        assert store.active_model() is not None
        rollback = store.activate_complete_model(model_version, rrf_weight=1.25)
        assert rollback["rrfWeight"] == 1.25
        assert store.active_model()[0].rrf_weight == 1.25

        changed = {**document, "contentHash": "b" * 64, "content": "IDE actualizado"}
        assert store.upsert_document_page(
            [changed],
            model_version=model_version,
            seen_at=seen_at + timedelta(seconds=1),
        ) == 1
        assert store.active_model() is None
        assert store.coverage_and_promote(model_version)["complete"] is False
        assert store.active_model() is None
        changed_jobs = store.claim_jobs(
            model_version=model_version,
            owner="test-indexer-2",
            limit=10,
            lease_seconds=60,
        )
        with pytest.raises(Exception, match="embedding dimension mismatch"):
            store.complete_jobs(
                model_version=model_version,
                jobs=changed_jobs,
                embeddings=[[1.0, 0.0]],
            )

        removed = store.finish_sweep(seen_at + timedelta(seconds=2))
        assert removed == 1
        remaining = database.run(
            lambda connection: connection.execute(
                """
                SELECT
                    (SELECT count(*) FROM semantic_documents) AS documents,
                    (SELECT count(*) FROM software_embeddings) AS embeddings,
                    (SELECT count(*) FROM embedding_jobs) AS jobs
                """
            ).fetchone()
        )
        assert remaining == {"documents": 0, "embeddings": 0, "jobs": 0}
    finally:
        database.close()


def test_hnsw_benchmark_compares_approximate_and_exact_search(
    postgres_dsn: str,
) -> None:
    database = Database(Settings(postgres_dsn_override=postgres_dsn))
    database.open()
    try:
        database.migrate()
        metrics = SemanticStore(database).benchmark_hnsw(
            dimensions=3,
            app_ids=[
                "00000000-0000-0000-0000-000000000011",
                "00000000-0000-0000-0000-000000000012",
                "00000000-0000-0000-0000-000000000013",
            ],
            document_vectors=[
                [1.0, 0.0, 0.0],
                [0.0, 1.0, 0.0],
                [0.0, 0.0, 1.0],
            ],
            query_vectors=[[1.0, 0.0, 0.0]],
        )
        assert metrics["hnswRecallAt20"] == 1.0
        assert metrics["hnswBuildMs"] >= 0
        assert metrics["hnswIndexBytes"] > 0
    finally:
        database.close()


def test_training_variants_can_share_one_dataset_snapshot(
    postgres_dsn: str,
) -> None:
    database = Database(Settings(postgres_dsn_override=postgres_dsn))
    database.open()
    try:
        database.migrate()
        store = SemanticStore(database)
        base = store.model(MODELS_BY_KEY["multilingual-e5-base"].zero_shot_version)
        dataset_hash = "c" * 64
        versions = (
            f"{base.model_key}@{base.hf_revision}:lora-smoke:{dataset_hash[:12]}",
            f"{base.model_key}@{base.hf_revision}:lora:{dataset_hash[:12]}",
        )
        for version in versions:
            store.register_trained_model(
                base=base,
                model_version=version,
                artifact_path=f"/models/{version}",
                dataset_hash=dataset_hash,
                training_config={"smoke": "lora-smoke" in version},
            )
        count = database.run(
            lambda connection: connection.execute(
                """
                SELECT count(*) AS variants
                FROM embedding_models
                WHERE model_version = ANY(%s)
                """,
                (list(versions),),
            ).fetchone()["variants"]
        )
        assert count == 2
    finally:
        database.close()
