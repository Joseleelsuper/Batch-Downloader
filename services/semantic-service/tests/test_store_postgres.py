"""Contiene las pruebas de `test_store_postgres`.
"""
from __future__ import annotations

import os
import uuid
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta

import pytest

from app.admin_store import SemanticAdminStore
from app.config import Settings
from app.database import Database
from app.heartbeat import WorkerHeartbeatStore
from app.model_registry import MODELS_BY_KEY
from app.retention import SemanticRetentionStore
from app.store import SemanticStore

testcontainers_postgres = pytest.importorskip("testcontainers.postgres")
"""Estado global asociado a `testcontainers_postgres`.
"""
PostgresContainer = testcontainers_postgres.PostgresContainer
"""Estado global asociado a `PostgresContainer`.
"""


@pytest.fixture(scope="module")
def postgres_dsn() -> Iterator[str]:
    """Ejecuta la operación `postgres_dsn`.

    Yields:
        Iterator[str]: Elemento producido por la operación.
    """
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
    except Exception as exception:  # pragma: no cover - depende del runtime del host
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


def test_schema_verification_rejects_checksum_drift(postgres_dsn: str) -> None:
    """Rechaza un historial aplicado cuyo contenido ya no coincide con el archivo."""
    database = Database(Settings(postgres_dsn_override=postgres_dsn))
    database.open()
    migrations = Database._migration_files()
    latest_version = next(reversed(migrations))
    expected_checksum = migrations[latest_version][0]
    try:
        database.migrate()
        database.verify_schema()
        database.run(
            lambda connection: connection.execute(
                """
                UPDATE semantic_schema_migrations
                SET checksum = %s
                WHERE version = %s
                """,
                ("0" * 64, latest_version),
            )
        )
        with pytest.raises(
            RuntimeError,
            match=f"semantic_migration_checksum_mismatch:{latest_version}",
        ):
            database.verify_schema()
    finally:
        database.run(
            lambda connection: connection.execute(
                """
                UPDATE semantic_schema_migrations
                SET checksum = %s
                WHERE version = %s
                """,
                (expected_checksum, latest_version),
            )
        )
        database.close()


def test_worker_heartbeat_degrades_only_after_persistent_failures_or_staleness(
    postgres_dsn: str,
) -> None:
    """Comprueba reinicio, umbral de fallos, recuperación y antigüedad."""
    database = Database(Settings(postgres_dsn_override=postgres_dsn))
    database.open()
    store = WorkerHeartbeatStore(database)
    instance_id = uuid.UUID("00000000-0000-0000-0000-000000000081")
    replacement_id = uuid.UUID("00000000-0000-0000-0000-000000000082")
    try:
        database.migrate()
        database.run(
            lambda connection: connection.execute(
                "DELETE FROM semantic_worker_heartbeats WHERE role = 'indexer'"
            )
        )
        missing = store.status(
            "indexer", max_age_seconds=45, failure_threshold=3
        )
        assert missing.present is False
        assert missing.reason == "missing"

        store.success("indexer", instance_id)
        for _attempt in range(2):
            store.failure("indexer", instance_id, "TransientDatabaseError")
        transient = store.status(
            "indexer", max_age_seconds=45, failure_threshold=3
        )
        assert transient.healthy is True
        assert transient.consecutive_failures == 2

        store.failure("indexer", instance_id, "PersistentDatabaseError")
        persistent = store.status(
            "indexer", max_age_seconds=45, failure_threshold=3
        )
        assert persistent.healthy is False
        assert persistent.reason == "persistent_failures"
        assert persistent.last_error_code == "PersistentDatabaseError"

        store.success("indexer", instance_id)
        recovered = store.status(
            "indexer", max_age_seconds=45, failure_threshold=3
        )
        assert recovered.healthy is True
        assert recovered.consecutive_failures == 0

        database.run(
            lambda connection: connection.execute(
                """
                UPDATE semantic_worker_heartbeats
                SET heartbeat_at = now() - interval '46 seconds'
                WHERE role = 'indexer'
                """
            )
        )
        stale = store.status("indexer", max_age_seconds=45, failure_threshold=3)
        assert stale.healthy is False
        assert stale.reason == "stale"

        store.pulse("indexer", replacement_id)
        restarted = store.status(
            "indexer", max_age_seconds=45, failure_threshold=3
        )
        assert restarted.healthy is True
        assert restarted.consecutive_failures == 0
    finally:
        database.run(
            lambda connection: connection.execute(
                "DELETE FROM semantic_worker_heartbeats WHERE role = 'indexer'"
            )
        )
        database.close()


def test_retention_prunes_only_old_terminal_rows_and_preserves_benchmarks(
    postgres_dsn: str,
) -> None:
    """Comprueba límites, estados activos y conservación indefinida de benchmarks."""
    database = Database(Settings(postgres_dsn_override=postgres_dsn))
    database.open()
    now = datetime(2026, 8, 23, tzinfo=UTC)
    app_id = uuid.UUID("00000000-0000-0000-0000-000000000071")
    old_operation_id = uuid.UUID("00000000-0000-0000-0000-000000000072")
    queued_operation_id = uuid.UUID("00000000-0000-0000-0000-000000000073")
    benchmark_id = uuid.UUID("00000000-0000-0000-0000-000000000074")
    model_version = MODELS_BY_KEY["multilingual-e5-base"].zero_shot_version
    try:
        database.migrate()

        def seed(connection) -> None:
            connection.execute(
                """
                INSERT INTO semantic_documents(app_id, content_hash, content, metadata)
                VALUES (%s, %s, 'retention fixture', '{}'::jsonb)
                ON CONFLICT (app_id) DO NOTHING
                """,
                (app_id, "7" * 64),
            )
            connection.execute(
                """
                INSERT INTO embedding_jobs(
                    app_id, model_version, content_hash, status, updated_at
                ) VALUES
                    (%s, %s, %s, 'completed', %s),
                    (%s, %s, %s, 'queued', %s)
                ON CONFLICT (app_id, model_version, content_hash) DO NOTHING
                """,
                (
                    app_id,
                    model_version,
                    "8" * 64,
                    now - timedelta(days=31),
                    app_id,
                    model_version,
                    "9" * 64,
                    now - timedelta(days=31),
                ),
            )
            connection.execute(
                """
                INSERT INTO semantic_operations(
                    id, operation_kind, status, phase, actor, created_at,
                    updated_at, finished_at
                ) VALUES
                    (%s, 'benchmark', 'succeeded', 'completed', 'test', %s, %s, %s),
                    (%s, 'benchmark', 'queued', 'queued', 'test', %s, %s, NULL)
                ON CONFLICT (id) DO NOTHING
                """,
                (
                    old_operation_id,
                    now - timedelta(days=91),
                    now - timedelta(days=91),
                    now - timedelta(days=91),
                    queued_operation_id,
                    now - timedelta(days=91),
                    now - timedelta(days=91),
                ),
            )
            connection.execute(
                """
                INSERT INTO benchmark_runs(
                    id, dataset_hash, seed, configuration, metrics, operation_id
                ) VALUES (%s, %s, 1, '{}'::jsonb, '{}'::jsonb, %s)
                ON CONFLICT (id) DO NOTHING
                """,
                (benchmark_id, "a" * 64, old_operation_id),
            )

        database.run(seed)
        first = SemanticRetentionStore(database).prune(now=now, batch_size=1)
        assert first == {"embeddingJobs": 1, "operations": 1}
        remaining = database.run(
            lambda connection: connection.execute(
                """
                SELECT
                    (SELECT count(*) FROM embedding_jobs
                     WHERE app_id = %s AND status = 'queued') AS queued_jobs,
                    (SELECT count(*) FROM semantic_operations
                     WHERE id = %s AND status = 'queued') AS queued_operations,
                    (SELECT count(*) FROM benchmark_runs
                     WHERE id = %s AND operation_id IS NULL) AS benchmarks
                """,
                (app_id, queued_operation_id, benchmark_id),
            ).fetchone()
        )
        assert remaining == {
            "queued_jobs": 1,
            "queued_operations": 1,
            "benchmarks": 1,
        }
        assert SemanticRetentionStore(database).prune(now=now, batch_size=1) == {
            "embeddingJobs": 0,
            "operations": 0,
        }
    finally:
        database.run(
            lambda connection: connection.execute(
                "DELETE FROM benchmark_runs WHERE id = %s",
                (benchmark_id,),
            )
        )
        database.run(
            lambda connection: connection.execute(
                "DELETE FROM semantic_operations WHERE id IN (%s, %s)",
                (old_operation_id, queued_operation_id),
            )
        )
        database.run(
            lambda connection: connection.execute(
                "DELETE FROM semantic_documents WHERE app_id = %s",
                (app_id,),
            )
        )
        database.close()


def test_migration_sync_dimension_contract_search_and_explicit_activation(
    postgres_dsn: str,
) -> None:
    """Comprueba el escenario `migration_sync_dimension_contract_search_and_explicit_activation`.

    Args:
        postgres_dsn (str): Valor de `postgres_dsn` utilizado por la operación.
    """
    settings = Settings(postgres_dsn_override=postgres_dsn)
    database = Database(settings)
    database.open()
    try:
        database.migrate()
        store = SemanticStore(database)
        model_version = MODELS_BY_KEY["multilingual-e5-base"].zero_shot_version
        seen_at = datetime.now(UTC)
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
    """Comprueba el escenario `hnsw_benchmark_compares_approximate_and_exact_search`.

    Args:
        postgres_dsn (str): Valor de `postgres_dsn` utilizado por la operación.
    """
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
    """Comprueba el escenario `training_variants_can_share_one_dataset_snapshot`.

    Args:
        postgres_dsn (str): Valor de `postgres_dsn` utilizado por la operación.
    """
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
    """Comprueba el escenario `admin_migration_reconciles_models_and_recovers_operation_leases`.

    Args:
        postgres_dsn (str): Valor de `postgres_dsn` utilizado por la operación.
    """
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

        artifact = models[2]
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
    """Comprueba el escenario `admin_activation_and_rollback_are_atomic_and_benchmark_gated`.

    Args:
        postgres_dsn (str): Valor de `postgres_dsn` utilizado por la operación.
    """
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
        seen_at = datetime.now(UTC)
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
            """Ejecuta la operación `configuration`.

            Args:
                model (dict[str, object]): Modelo utilizado por la operación.

            Returns:
                dict[str, object]: Mapa con los datos producidos por la operación.
            """
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
