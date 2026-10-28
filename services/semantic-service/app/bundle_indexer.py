"""Sincroniza metadatos públicos de Core sin depender de finales del scraper."""

from datetime import UTC, datetime
from pathlib import Path

import httpx

from app.bundle_store import BundleSemanticStore
from app.embeddings import EmbeddingRuntime
from app.model_validation import load_model_manifest, validate_model_directory


def synchronize_bundles(indexer) -> dict[str, object] | None:
    settings = indexer.settings
    with httpx.Client(
        base_url=settings.core_api_url.rstrip("/"),
        timeout=30,
        headers={"X-Internal-Service-Token": settings.internal_service_token.get_secret_value()},
    ) as client:

        def revision():
            response = client.get("/internal/v1/semantic/bundles/source-status")
            response.raise_for_status()
            return int(response.json()["revision"])

        source_revision = revision()
        descriptor = load_model_manifest(
            Path(settings.model_dir),
            manifest_name=settings.model_manifest_name,
        )
        with indexer.database.exclusive_background_operation():
            store = BundleSemanticStore(indexer.database)
            if store.checkpoint(descriptor.model_version) == source_revision:
                return None
            descriptor, _ = validate_model_directory(
                Path(settings.model_dir),
                device=settings.device,
                manifest_name=settings.model_manifest_name,
            )
            model = indexer.store.ensure_model(descriptor)
            seen = datetime.now(UTC)
            after = None
            seen_cursors = set()
            while True:
                params = {"limit": 500}
                if after:
                    params["afterBundleId"] = after
                response = client.get("/internal/v1/semantic/bundles/documents", params=params)
                response.raise_for_status()
                page = response.json()
                store.upsert_page(page["documents"], model.model_version, seen)
                after = page.get("nextAfterBundleId")
                if not after:
                    break
                if after in seen_cursors:
                    raise RuntimeError("bundle_semantic_cursor_repeated")
                seen_cursors.add(after)
            # Nunca purgar una colección tras una lectura incompleta o una revisión cambiante.
            if revision() != source_revision:
                return {"retry": True}
            store.finish_sweep(seen, model.model_version)
            runtime = EmbeddingRuntime(
                model,
                model_dir=settings.model_dir,
                cache_dir=settings.model_runtime_cache_dir,
                device=settings.device,
                batch_size=settings.index_batch_size,
            )
            embedded = 0
            while jobs := store.claim_jobs(
                model.model_version, settings.index_batch_size, settings.index_lease_seconds
            ):
                try:
                    vectors = runtime.encode_documents([job["content"] for job in jobs])
                    store.complete_jobs(model.model_version, jobs, vectors)
                    embedded += len(jobs)
                except Exception as error:
                    store.fail_jobs(jobs, error.__class__.__name__)
                    raise
            acknowledged = revision() == source_revision and store.acknowledge(
                model.model_version, source_revision
            )
            return {
                "embedded": embedded,
                "sourceRevision": source_revision,
                "acknowledged": acknowledged,
            }
