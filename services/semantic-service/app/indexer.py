"""Sincroniza el catálogo desde Scraper y construye embeddings por lotes con reservas y exclusión
de procesos de fondo.
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



class SemanticIndexer:
    """Coordina sincronización, limpieza del barrido y codificación de documentos para una
    versión concreta del modelo.

    See Also:
        app.store.SemanticStore: Persistencia transaccional y cobertura del índice.
    """
    def __init__(
        self,
        settings: Settings | None = None,
        database: Database | None = None,
    ) -> None:
        """Usa un pool cedido o crea uno propio y registra su propiedad para cerrarlo únicamente
        cuando corresponda.

        Args:
            settings: Configuración opcional; si se omite se carga desde el entorno del
                proceso.
            database: Pool cedido opcional; si se proporciona, el llamador conserva su ciclo
                de vida.
        """
        self.settings = settings or get_settings()

        self.database = database or Database(self.settings)

        self.store = SemanticStore(self.database)

        self._owns_database = database is None
        """Indica si esta instancia debe abrir y cerrar el pool."""

    def open(self) -> None:
        """Abre y verifica el esquema solo cuando el indexador es propietario del pool."""
        if self._owns_database:
            self.database.open()
            self.database.verify_schema()

    def close(self) -> None:
        """Cierra el pool únicamente si lo creó esta instancia."""
        if self._owns_database:
            self.database.close()

    def run_once(
        self,
        requested_model_version: str | None = None,
        *,
        progress: Callable[[str, int, int], None] | None = None,
        cancelled: Callable[[], bool] | None = None,
    ) -> dict[str, object]:
        """Obtiene la exclusión de trabajo de fondo antes de sincronizar e indexar una versión
        completa.

        Args:
            requested_model_version: Versión explícita; None utiliza la activa, seleccionada o
                inicial configurada.
            progress: Callback opcional con fase, unidades completadas y total; cero indica
                total todavía desconocido.
            cancelled: Consulta cooperativa opcional que solicita interrumpir el trabajo entre
                páginas y lotes.

        Returns:
            documentos vistos, modificados, retirados y codificados, junto con cobertura e
                identidad del índice.

        Raises:
            InterruptedError: Si se solicita cancelar entre páginas o lotes.
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
        """Sincroniza el catálogo, codifica trabajos reservados y recalcula cobertura al agotar
        los lotes disponibles.
        Los fallos de codificación reprograman el lote antes de propagarse.

        Args:
            requested_model_version: Versión explícita; None utiliza la activa, seleccionada o
                inicial configurada.
            progress: Callback opcional con fase, unidades completadas y total; cero indica
                total todavía desconocido.
            cancelled: Consulta cooperativa opcional que solicita interrumpir el trabajo entre
                páginas y lotes.

        Returns:
            reporte de sincronización y construcción del índice.
        """
        model_version = requested_model_version or self.store.selected_model_version(
            self.settings.initial_model_version
        )
        model = self.store.model(model_version)
        seen, changed, removed = self._synchronize_documents(model_version, progress, cancelled)
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

    def _synchronize_documents(
        self, model_version: str,
        progress: Callable[[str, int, int], None] | None,
        cancelled: Callable[[], bool] | None,
    ) -> tuple[int, int, int]:
        """Recorre páginas de 500 documentos usando cursor y token interno; solo al terminar
        elimina los no vistos.

        Args:
            model_version: Identidad inmutable del modelo y revisión cuyos embeddings se
                procesan.
            progress: Callback opcional con fase, unidades completadas y total; cero indica
                total todavía desconocido.
            cancelled: Consulta cooperativa opcional que solicita interrumpir el trabajo entre
                páginas y lotes.

        Returns:
            cantidades de documentos recibidos, cambiados y retirados.

        Raises:
            InterruptedError: Si se cancela antes de la siguiente página.
            httpx.HTTPError: Si falla el transporte o Scraper rechaza la petición.
        """
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
        return seen, changed, removed


def main() -> None:
    """Ejecuta un barrido o un bucle con --loop, respetando ventanas de fondo y publicando salud
    por iteración.
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
