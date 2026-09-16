"""Dependencias compartidas del API semantico."""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI

from app.config import get_settings
from app.database import Database
from app.embeddings import EmbeddingRuntime
from app.heartbeat import WorkerHeartbeatStore
from app.http_policies import InternalServiceTokenGuard, SearchCapacityGuard
from app.model_validation import ModelDescriptor, load_model_manifest
from app.store import SemanticStore

settings = get_settings()
database = Database(settings)
store = SemanticStore(database)
heartbeat_store = WorkerHeartbeatStore(database)
runtime: EmbeddingRuntime | None = None
search_slots = asyncio.Semaphore(settings.search_concurrency)
require_internal_service_token = InternalServiceTokenGuard(
    settings.internal_service_token.get_secret_value()
)
require_search_capacity = SearchCapacityGuard(
    search_slots,
    settings.search_capacity_wait_seconds,
)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    """Abre el pool y verifica que la migracion ya se haya aplicado."""
    await asyncio.to_thread(database.open)
    await asyncio.to_thread(database.verify_schema)
    try:
        yield
    finally:
        await asyncio.to_thread(database.close)


def current_model_manifest() -> ModelDescriptor:
    """Lee la identidad de la carpeta actual; cambiarla requiere reiniciar el proceso."""
    return load_model_manifest(
        Path(settings.model_dir),
        manifest_name=settings.model_manifest_name,
    )


def runtime_for(model) -> EmbeddingRuntime:
    """Devuelve el unico runtime y rechaza desincronizaciones de carpeta o base de datos."""
    global runtime
    descriptor = current_model_manifest()
    if descriptor.model_version != model.model_version:
        raise RuntimeError("semantic_model_restart_required")
    if runtime is not None:
        if runtime.registered.model_version != model.model_version:
            raise RuntimeError("semantic_model_restart_required")
        return runtime
    runtime = EmbeddingRuntime(
        model,
        model_dir=settings.model_dir,
        cache_dir=settings.model_runtime_cache_dir,
        device=settings.device,
        batch_size=settings.index_batch_size,
    )
    return runtime
