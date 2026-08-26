"""Implementa las responsabilidades del módulo `indexer`.
"""
from __future__ import annotations

import argparse
import logging
import time
import uuid
from collections.abc import Callable
from datetime import UTC, datetime

import httpx

from app.config import Settings, get_settings
from app.database import Database
from app.embeddings import EmbeddingRuntime
from app.heartbeat import WorkerHeartbeat
from app.store import SemanticStore

logger = logging.getLogger("semantic-indexer")
"""Estado global asociado a `logger`.
"""


class SemanticIndexer:
    """Representa el componente `SemanticIndexer`.
    """
    def __init__(
        self,
        settings: Settings | None = None,
        database: Database | None = None,
    ) -> None:
        """Inicializa una instancia de `SemanticIndexer`.
        """
        self.settings = settings or get_settings()
        """Estado de instancia asociado a `settings`.
        """
        self.database = database or Database(self.settings)
        """Estado de instancia asociado a `database`.
        """
        self.store = SemanticStore(self.database)
        """Estado de instancia asociado a `store`.
        """
        self._owns_database = database is None
        """Indica si esta instancia debe abrir y cerrar el pool."""

    def open(self) -> None:
        """Ejecuta `open` dentro de `SemanticIndexer`.
        """
        if self._owns_database:
            self.database.open()
            self.database.verify_schema()

    def close(self) -> None:
        """Ejecuta `close` dentro de `SemanticIndexer`.
        """
        if self._owns_database:
            self.database.close()

    def run_once(
        self,
        requested_model_version: str | None = None,
        *,
        progress: Callable[[str, int, int], None] | None = None,
        cancelled: Callable[[], bool] | None = None,
    ) -> dict[str, object]:
        """Ejecuta la operación `once`.

        Args:
            requested_model_version (str | None): Valor de `requested_model_version` utilizado por
                la operación.
            progress (Callable[[str, int, int], None] | None): Valor de `progress` utilizado por la
                operación.
            cancelled (Callable[[], bool] | None): Valor de `cancelled` utilizado por la operación.

        Returns:
            dict[str, object]: Mapa con los datos producidos por la operación.

        Throws:
            InterruptedError: Si no puede completarse la operación bajo las condiciones requeridas.
        """
        with self.database.exclusive_background_operation():
            return self._run_once(
                requested_model_version,
                progress=progress,
                cancelled=cancelled,
            )

    def _run_once(
        self,
        requested_model_version: str | None = None,
        *,
        progress: Callable[[str, int, int], None] | None = None,
        cancelled: Callable[[], bool] | None = None,
    ) -> dict[str, object]:
        """Ejecuta el barrido mientras la exclusión distribuida permanece adquirida."""
        model_version = requested_model_version or self.store.selected_model_version(
            self.settings.initial_model_version
        )
        model = self.store.model(model_version)
        sweep_started = datetime.now(UTC)
        next_after: str | None = None
        seen = 0
        changed = 0
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
                    seen_at=sweep_started,
                )
                seen += len(documents)
                if progress:
                    progress("syncing", seen, 0)
                next_after = page.get("nextAfterAppId")
                if not next_after:
                    break
        removed = self.store.finish_sweep(sweep_started)
        runtime = EmbeddingRuntime(
            model,
            device=self.settings.device,
            cache_dir=self.settings.model_cache_dir,
            batch_size=self.settings.index_batch_size,
        )
        owner = f"indexer-{uuid.uuid4()}"
        embedded = 0
        expected = len(self.store.active_documents())
        while True:
            if cancelled and cancelled():
                raise InterruptedError("semantic_operation_cancelled")
            jobs = self.store.claim_jobs(
                model_version=model_version,
                owner=owner,
                limit=self.settings.index_batch_size,
                lease_seconds=self.settings.index_lease_seconds,
            )
            if not jobs:
                break
            try:
                vectors = runtime.encode_documents([job["content"] for job in jobs])
                self.store.complete_jobs(
                    model_version=model_version,
                    jobs=jobs,
                    embeddings=vectors,
                )
                embedded += len(jobs)
                if progress:
                    progress("indexing", embedded, expected)
            except Exception as exception:
                self.store.fail_jobs(jobs, exception.__class__.__name__)
                raise
        coverage = self.store.coverage_and_promote(model_version)
        if progress:
            progress(
                "finalizing",
                int(coverage["indexed"]),
                int(coverage["expected"]),
            )
        return {
            "modelVersion": model_version,
            "seen": seen,
            "changed": changed,
            "removed": removed,
            "embedded": embedded,
            **coverage,
        }


def main() -> None:
    """Ejecuta el punto de entrada del módulo.
    """
    parser = argparse.ArgumentParser(description="Sincroniza MySQL con pgvector")
    parser.add_argument("--loop", action="store_true")
    parser.add_argument("--model-version")
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
                report = indexer.run_once(arguments.model_version)
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
