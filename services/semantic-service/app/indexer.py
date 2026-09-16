"""Sincroniza el catalogo y genera embeddings para el modelo de la carpeta actual."""
from __future__ import annotations

import argparse
import logging
import time
import uuid
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import httpx

from app.config import Settings, get_settings
from app.database import Database
from app.embeddings import EmbeddingRuntime
from app.heartbeat import WorkerHeartbeat
from app.model_validation import validate_model_directory
from app.store import SemanticStore

logger = logging.getLogger("semantic-indexer")


class SemanticIndexer:
    """Construye el unico indice semantico a partir de la ranura local."""

    def __init__(
        self,
        settings: Settings | None = None,
        database: Database | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.database = database or Database(self.settings)
        self.store = SemanticStore(self.database)
        self._owns_database = database is None

    def open(self) -> None:
        if self._owns_database:
            self.database.open()
            self.database.verify_schema()

    def close(self) -> None:
        if self._owns_database:
            self.database.close()

    def run_once(
        self,
        *,
        progress: Callable[[str, int, int], None] | None = None,
        cancelled: Callable[[], bool] | None = None,
    ) -> dict[str, object]:
        descriptor, _validation = validate_model_directory(
            Path(self.settings.model_dir),
            device=self.settings.device,
            manifest_name=self.settings.model_manifest_name,
        )
        with self.database.exclusive_background_operation():
            model = self.store.ensure_model(descriptor)
            seen, changed, removed = self._synchronize_documents(
                model.model_version,
                progress,
                cancelled,
            )
            runtime = EmbeddingRuntime(
                model,
                model_dir=self.settings.model_dir,
                cache_dir=self.settings.model_runtime_cache_dir,
                device=self.settings.device,
                batch_size=self.settings.index_batch_size,
            )
            owner = f"indexer-{uuid.uuid4()}"
            embedded = 0
            expected = self._active_document_count()
            while True:
                if cancelled and cancelled():
                    raise InterruptedError("semantic_operation_cancelled")
                jobs = self.store.claim_jobs(
                    model_version=model.model_version,
                    owner=owner,
                    limit=self.settings.index_batch_size,
                    lease_seconds=self.settings.index_lease_seconds,
                )
                if not jobs:
                    break
                try:
                    vectors = runtime.encode_documents([job["content"] for job in jobs])
                    self.store.complete_jobs(
                        model_version=model.model_version,
                        jobs=jobs,
                        embeddings=vectors,
                    )
                    embedded += len(jobs)
                    if progress:
                        progress("indexing", embedded, expected)
                except Exception as exception:
                    self.store.fail_jobs(jobs, exception.__class__.__name__)
                    raise
            coverage = self.store.coverage_and_promote(model.model_version)
            if progress:
                progress(
                    "finalizing",
                    int(coverage["indexed"]),
                    int(coverage["expected"]),
                )
            return {
                "modelVersion": model.model_version,
                "seen": seen,
                "changed": changed,
                "removed": removed,
                "embedded": embedded,
                **coverage,
            }

    def _active_document_count(self) -> int:
        def query(connection: Any) -> int:
            row = connection.execute(
                "SELECT count(*) AS count FROM semantic_documents WHERE active"
            ).fetchone()
            return int(row["count"]) if row else 0

        return self.database.run(query)

    def _synchronize_documents(
        self,
        model_version: str,
        progress: Callable[[str, int, int], None] | None,
        cancelled: Callable[[], bool] | None,
    ) -> tuple[int, int, int]:
        started = datetime.now(UTC)
        next_after: str | None = None
        seen = changed = 0
        headers = {
            "X-Internal-Service-Token": (
                self.settings.internal_service_token.get_secret_value()
            )
        }
        with httpx.Client(
            base_url=self.settings.scraper_api_url.rstrip("/"),
            headers=headers,
            timeout=30,
        ) as client:
            while True:
                if cancelled and cancelled():
                    raise InterruptedError("semantic_operation_cancelled")
                params: dict[str, str | int] = {"limit": 500}
                if next_after:
                    params["afterAppId"] = next_after
                response = client.get(
                    "/internal/v1/semantic/documents",
                    params=params,
                )
                response.raise_for_status()
                page = response.json()
                documents = page.get("documents") or []
                changed += self.store.upsert_document_page(
                    documents,
                    model_version=model_version,
                    seen_at=started,
                )
                seen += len(documents)
                if progress:
                    progress("syncing", seen, 0)
                next_after = page.get("nextAfterAppId")
                if not next_after:
                    break
        return seen, changed, self.store.finish_sweep(started)


def main() -> None:
    parser = argparse.ArgumentParser(description="Sincroniza MySQL con pgvector")
    parser.add_argument("--loop", action="store_true")
    arguments = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    indexer = SemanticIndexer()
    indexer.open()
    heartbeat = WorkerHeartbeat(
        indexer.database,
        "indexer",
        interval_seconds=indexer.settings.worker_heartbeat_interval_seconds,
    )
    heartbeat.start()
    try:
        while True:
            if arguments.loop and not indexer.settings.background_window_open():
                heartbeat.success()
                time.sleep(min(60.0, max(5.0, indexer.settings.index_interval_seconds)))
                continue
            try:
                report = indexer.run_once()
                logger.info("semantic_index_completed %s", report)
                heartbeat.success()
            except Exception as exception:
                heartbeat.failure(exception)
                logger.exception(
                    "semantic_index_failed error=%s",
                    exception.__class__.__name__,
                )
                if not arguments.loop:
                    raise
            if not arguments.loop:
                break
            time.sleep(max(5.0, indexer.settings.index_interval_seconds))
    finally:
        heartbeat.close()
        indexer.close()


if __name__ == "__main__":
    main()
