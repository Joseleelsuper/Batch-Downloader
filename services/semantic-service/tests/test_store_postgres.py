from __future__ import annotations

import os
import uuid
from collections.abc import Iterator
from datetime import datetime, timedelta, timezone

import pytest

from app.admin_store import SemanticAdminStore
from app.config import Settings
from app.database import Database
from app.model_registry import MODELS_BY_KEY
from app.store import SemanticStore

testcontainers_postgres = pytest.importorskip("testcontainers.postgres")
PostgresContainer = testcontainers_postgres.PostgresContainer


@pytest.fixture(scope="module")
def postgres_dsn() -> Iterator[str]:
    configured_dsn = os.environ.get("SEMANTIC_TEST_POSTGRES_DSN")
    if configured_dsn:
        yield configured_dsn
        return
    try:
        container = PostgresContainer(
            image="pgvector/pgvector:pg16",
            username="semantic",
            password="semantic",
            dbname="semantic",
        )
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


def test_migration_sync_dimension_contract_search_and_explicit_activation(
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
        assert store.active_model() is None
        activated = store.activate_complete_model(model_version, rrf_weight=1.5)
        assert activated["rrfWeight"] == 1.5
        active = store.active_model()
        assert active is not None
        assert active[0].model_version == model_version
        assert store.exact_search(
            model=active[0],
            query_vector=vector,
            minimum_similarity=0.0,
            limit=10,
        )[0]["appId"] == document["appId"]

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
        assert store.selected_model_version("fallback") == model_version
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


def test_admin_migration_reconciles_models_and_recovers_operation_leases(
    postgres_dsn: str,
) -> None:
    database = Database(Settings(postgres_dsn_override=postgres_dsn))
    database.open()
    try:
        database.migrate()
        admin = SemanticAdminStore(database)
        models = admin.models()
        e5 = next(
            model
            for model in models
            if model["repository"] == "intfloat/multilingual-e5-base"
        )
        assert e5["artifactState"] == "ready"
        assert e5["minimumSimilarity"] == 0.82

        download_request = {
            "repository": "fixture/semantic-model",
            "revision": "main",
            "queryPrefix": "query: ",
            "passagePrefix": "passage: ",
            "minimumSimilarity": 0.4,
        }
        artifact, download = admin.create_download_operation(
            repository="fixture/semantic-model",
            requested_revision="main",
            resolved_revision="d" * 40,
            display_name="semantic-model",
            metadata={"libraryName": "sentence-transformers"},
            query_prefix="query: ",
            passage_prefix="passage: ",
            minimum_similarity=0.4,
            actor="admin-test",
            idempotency_key="download-idempotency-test",
            request_payload=download_request,
            progress_total=1024,
        )
        same_artifact, same_download = admin.create_download_operation(
            repository="fixture/semantic-model",
            requested_revision="main",
            resolved_revision="d" * 40,
            display_name="semantic-model",
            metadata={"libraryName": "sentence-transformers"},
            query_prefix="query: ",
            passage_prefix="passage: ",
            minimum_similarity=0.4,
            actor="admin-test",
            idempotency_key="download-idempotency-test",
            request_payload=download_request,
            progress_total=1024,
        )
        assert same_artifact["id"] == artifact["id"]
        assert same_download["id"] == download["id"]
        with pytest.raises(
            RuntimeError,
            match="semantic_idempotency_key_conflict",
        ):
            admin.create_download_operation(
                repository="fixture/other-model",
                requested_revision="main",
                resolved_revision="e" * 40,
                display_name="other-model",
                metadata={"libraryName": "sentence-transformers"},
                query_prefix="",
                passage_prefix="",
                minimum_similarity=0.0,
                actor="admin-test",
                idempotency_key="download-idempotency-test",
                request_payload={"repository": "fixture/other-model"},
                progress_total=2048,
            )
        assert admin.request_cancel(str(download["id"]))["status"] == "cancelled"
        delete = admin.create_operation(
            kind="delete",
            actor="admin-test",
            idempotency_key="delete-fixture-test",
            request_payload={"modelId": str(artifact["id"])},
            model_id=str(artifact["id"]),
        )
        claimed_delete = admin.claim_operation("worker-delete", lease_seconds=30)
        assert claimed_delete["id"] == delete["id"]
        assert admin.begin_finalization(
            str(delete["id"]),
            owner="worker-delete",
            phase="deleting",
            message="deleting fixture",
        )
        deletion = admin.begin_model_deletion(
            str(artifact["id"]),
            excluding_operation_id=str(delete["id"]),
        )
        admin.finish_model_deletion(
            str(artifact["id"]),
            operation_id=str(delete["id"]),
            model_version=deletion["modelVersion"],
        )
        assert admin.operation(str(delete["id"]))["status"] == "succeeded"

        payload = {"modelIds": [e5["id"], models[1]["id"]]}
        first = admin.create_operation(
            kind="benchmark",
            actor="admin-test",
            idempotency_key="benchmark-idempotency-test",
            request_payload=payload,
            model_id=e5["id"],
        )
        duplicate = admin.create_operation(
            kind="benchmark",
            actor="admin-test",
            idempotency_key="benchmark-idempotency-test",
            request_payload=payload,
            model_id=e5["id"],
        )
        assert duplicate["id"] == first["id"]
        with pytest.raises(
            RuntimeError,
            match="semantic_model_has_open_operations",
        ):
            admin.assert_model_deletable(models[1]["id"])
        with pytest.raises(
            RuntimeError,
            match="semantic_idempotency_key_conflict",
        ):
            admin.create_operation(
                kind="benchmark",
                actor="admin-test",
                idempotency_key="benchmark-idempotency-test",
                request_payload={"modelIds": ["different-request"]},
                model_id=e5["id"],
            )

        claimed = admin.claim_operation("worker-one", lease_seconds=30)
        assert claimed is not None
        assert claimed["id"] == first["id"]
        database.run(
            lambda connection: connection.execute(
                """
                UPDATE semantic_operations
                SET lease_until = now() - interval '1 second'
                WHERE id = %s
                """,
                (first["id"],),
            )
        )
        recovered = admin.claim_operation("worker-two", lease_seconds=30)
        assert recovered is not None
        assert recovered["id"] == first["id"]
        assert recovered["attempts"] == 2
        cancelled = admin.request_cancel(str(first["id"]))
        assert cancelled["status"] == "cancel_requested"
        admin.mark_cancelled(str(first["id"]))
        assert admin.operation(str(first["id"]))["status"] == "cancelled"
        retry = admin.retry_operation(
            str(first["id"]),
            actor="admin-test",
            idempotency_key="benchmark-retry-idempotency-test",
        )
        assert retry["id"] != str(first["id"])
        assert retry["status"] == "queued"
        assert admin.request_cancel(retry["id"])["status"] == "cancelled"

        cancelled_activation = admin.create_operation(
            kind="activate",
            actor="admin-test",
            idempotency_key="activation-cancel-test",
            request_payload={"benchmarkRunId": str(first["id"])},
            model_id=models[1]["id"],
        )
        claimed_activation = admin.claim_operation("worker-three", lease_seconds=30)
        assert claimed_activation["id"] == cancelled_activation["id"]
        assert admin.request_cancel(cancelled_activation["id"])["status"] == (
            "cancel_requested"
        )
        assert not admin.begin_activation(
            cancelled_activation["id"],
            owner="worker-three",
        )
        admin.mark_cancelled(cancelled_activation["id"])

        atomic_activation = admin.create_operation(
            kind="activate",
            actor="admin-test",
            idempotency_key="activation-atomic-test",
            request_payload={"benchmarkRunId": str(first["id"]), "atomic": True},
            model_id=models[1]["id"],
        )
        claimed_atomic = admin.claim_operation("worker-four", lease_seconds=30)
        assert claimed_atomic["id"] == atomic_activation["id"]
        assert admin.begin_activation(
            atomic_activation["id"],
            owner="worker-four",
        )
        with pytest.raises(
            RuntimeError,
            match="semantic_operation_not_cancellable",
        ):
            admin.request_cancel(atomic_activation["id"])
        admin.fail_operation(
            atomic_activation["id"],
            "semantic_test_complete",
            "test complete",
        )

        with pytest.raises(
            RuntimeError,
            match="active_semantic_model_cannot_be_deleted",
        ):
            admin.assert_model_deletable(e5["id"])
    finally:
        database.close()


def test_admin_activation_and_rollback_are_atomic_and_benchmark_gated(
    postgres_dsn: str,
) -> None:
    database = Database(Settings(postgres_dsn_override=postgres_dsn))
    database.open()
    try:
        database.migrate()
        semantic = SemanticStore(database)
        admin = SemanticAdminStore(database)
        models = admin.models()
        active = next(model for model in models if model["active"])
        candidate = next(model for model in models if not model["active"])
        document = {
            "appId": "00000000-0000-0000-0000-000000000099",
            "contentHash": "9" * 64,
            "content": "Editor semántico multilingüe",
            "metadata": {"name": "Semantic fixture"},
        }
        seen_at = datetime.now(timezone.utc)
        semantic.upsert_document_page(
            [document],
            model_version=active["modelVersion"],
            seen_at=seen_at,
        )
        active_jobs = semantic.claim_jobs(
            model_version=active["modelVersion"],
            owner="activation-active-indexer",
            limit=10,
            lease_seconds=60,
        )
        semantic.complete_jobs(
            model_version=active["modelVersion"],
            jobs=active_jobs,
            embeddings=[
                [1.0] + [0.0] * (int(active["dimensions"]) - 1)
            ],
        )
        database.run(
            lambda connection: connection.execute(
                """
                INSERT INTO embedding_jobs (
                    app_id, model_version, content_hash
                ) VALUES (%s, %s, %s)
                ON CONFLICT DO NOTHING
                """,
                (
                    document["appId"],
                    candidate["modelVersion"],
                    document["contentHash"],
                ),
            )
        )
        candidate_jobs = semantic.claim_jobs(
            model_version=candidate["modelVersion"],
            owner="activation-candidate-indexer",
            limit=10,
            lease_seconds=60,
        )
        semantic.complete_jobs(
            model_version=candidate["modelVersion"],
            jobs=candidate_jobs,
            embeddings=[
                [1.0] + [0.0] * (int(candidate["dimensions"]) - 1)
            ],
        )
        active_index = semantic.coverage_and_promote(active["modelVersion"])
        candidate_index = semantic.coverage_and_promote(candidate["modelVersion"])
        assert active_index["complete"] and candidate_index["complete"]
        assert active_index["indexVersion"] != candidate_index["indexVersion"]
        catalog_snapshot_hash = database.run(
            lambda connection: connection.execute(
                """
                SELECT snapshot_hash
                FROM semantic_index_state
                WHERE model_version = %s
                """,
                (active["modelVersion"],),
            ).fetchone()["snapshot_hash"]
        )

        def configuration(model: dict[str, object]) -> dict[str, object]:
            artifact = admin.artifact(str(model["id"]))
            return {
                "repository": artifact["hf_repository"],
                "revision": artifact["resolved_revision"],
                "queryPrefix": artifact["query_prefix"],
                "passagePrefix": artifact["passage_prefix"],
                "minimumSimilarity": float(artifact["minimum_similarity"]),
            }

        benchmark_operation = admin.create_operation(
            kind="benchmark",
            actor="admin-test",
            idempotency_key=f"activation-benchmark-{uuid.uuid4()}",
            request_payload={"modelIds": [active["id"], candidate["id"]]},
            model_id=str(active["id"]),
        )
        claimed_benchmark = admin.claim_operation(
            "activation-benchmark-worker",
            lease_seconds=60,
        )
        assert claimed_benchmark["id"] == benchmark_operation["id"]
        benchmark_run_id = str(uuid.uuid4())
        admin.save_benchmark_run(
            run_id=benchmark_run_id,
            operation_id=str(benchmark_operation["id"]),
            model_ids=[str(active["id"]), str(candidate["id"])],
            dataset_hash="7" * 64,
            seed=20260723,
            configuration={
                "catalogSnapshotHash": catalog_snapshot_hash,
                "modelConfigurations": {
                    str(active["id"]): configuration(active),
                    str(candidate["id"]): configuration(candidate),
                },
            },
            metrics=[
                {
                    "modelId": str(active["id"]),
                    "eligible": True,
                    "totalScore": 0.7,
                },
                {
                    "modelId": str(candidate["id"]),
                    "eligible": True,
                    "totalScore": 0.8,
                },
            ],
            hardware_fingerprint="fixture-hardware",
            document_count=1,
            query_count=2,
            paths={
                "json": "/reports/fixture.json",
                "csv": "/reports/fixture.csv",
                "markdown": "/reports/fixture.md",
            },
        )
        assert admin.operation(str(benchmark_operation["id"]))["status"] == (
            "succeeded"
        )
        assert admin.eligible_benchmark(str(candidate["id"]))["id"] == (
            benchmark_run_id
        )
        assert admin.active_model_id() == str(active["id"])

        activation = admin.create_operation(
            kind="activate",
            actor="admin-test",
            idempotency_key=f"activation-{uuid.uuid4()}",
            request_payload={"benchmarkRunId": benchmark_run_id},
            model_id=str(candidate["id"]),
        )
        assert admin.claim_operation(
            "activation-worker",
            lease_seconds=60,
        )["id"] == activation["id"]
        assert admin.begin_activation(
            str(activation["id"]),
            owner="activation-worker",
        )
        activated = admin.activate_model(
            str(candidate["id"]),
            operation_id=str(activation["id"]),
            benchmark_run_id=benchmark_run_id,
            expected_current_model_id=str(active["id"]),
            confirm_regression=False,
        )
        assert activated["indexVersion"] == candidate_index["indexVersion"]
        assert admin.active_model_id() == str(candidate["id"])
        assert admin.operation(str(activation["id"]))["status"] == "succeeded"
        active_runtime = semantic.active_model()
        assert active_runtime is not None
        assert active_runtime[0].model_version == candidate["modelVersion"]
        assert semantic.exact_search(
            model=active_runtime[0],
            query_vector=[
                1.0
            ] + [0.0] * (int(candidate["dimensions"]) - 1),
            minimum_similarity=0.0,
            limit=10,
        )[0]["appId"] == document["appId"]

        rollback = admin.create_operation(
            kind="activate",
            actor="admin-test",
            idempotency_key=f"rollback-{uuid.uuid4()}",
            request_payload={"benchmarkRunId": benchmark_run_id},
            model_id=str(active["id"]),
        )
        assert admin.claim_operation(
            "rollback-worker",
            lease_seconds=60,
        )["id"] == rollback["id"]
        assert admin.begin_activation(
            str(rollback["id"]),
            owner="rollback-worker",
        )
        restored = admin.activate_model(
            str(active["id"]),
            operation_id=str(rollback["id"]),
            benchmark_run_id=benchmark_run_id,
            expected_current_model_id=str(candidate["id"]),
            confirm_regression=True,
        )
        assert restored["indexVersion"] == active_index["indexVersion"]
        assert admin.active_model_id() == str(active["id"])
        assert admin.operation(str(rollback["id"]))["status"] == "succeeded"
        restored_runtime = semantic.active_model()
        assert restored_runtime is not None
        assert restored_runtime[0].model_version == active["modelVersion"]
        semantic.finish_sweep(seen_at + timedelta(seconds=1))
    finally:
        database.close()
