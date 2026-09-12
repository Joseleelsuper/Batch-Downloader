"""Compone dependencias del API semántico y conserva hasta dos runtimes locales ordenados por
uso.
Comparte configuración y pools entre routers, pero cada consulta utiliza la transacción de su
almacén. Los guardas verifican token interno y capacidad antes de ejecutar búsquedas.
"""
from __future__ import annotations

import asyncio
from collections import OrderedDict
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.admin_store import SemanticAdminStore
from app.config import get_settings
from app.database import Database
from app.embeddings import EmbeddingRuntime
from app.heartbeat import WorkerHeartbeatStore
from app.http_policies import InternalServiceTokenGuard, SearchCapacityGuard
from app.store import SemanticStore

settings = get_settings()

database = Database(settings)

store = SemanticStore(database)

admin_store = SemanticAdminStore(database)

heartbeat_store = WorkerHeartbeatStore(database)
"""Estado persistente de salud de los procesos semánticos."""
runtime_cache: OrderedDict[str, EmbeddingRuntime] = OrderedDict()

search_slots = asyncio.Semaphore(settings.search_concurrency)
"""Plazas de búsqueda que protegen CPU y el pool de PostgreSQL."""
require_internal_service_token = InternalServiceTokenGuard(
    settings.internal_service_token.get_secret_value()
)
"""Wrapper compartido de autorización para endpoints internos."""
require_search_capacity = SearchCapacityGuard(
    search_slots,
    settings.search_capacity_wait_seconds,
)
"""Wrapper de admisión aplicado antes de ejecutar una búsqueda."""


@asynccontextmanager
async def lifespan(_app: FastAPI):
    """Abre el pool y verifica la versión del esquema antes de aceptar peticiones; al salir
    cierra el pool.

    Args:
        _app: Aplicación FastAPI propietaria de este ciclo de vida.

    Yields:
        control al servidor mientras las dependencias están disponibles.

    Raises:
        RuntimeError: Si el esquema o sus checksums no coinciden con las migraciones del
            servicio.

    See Also:
        app.database.Database.verify_schema: Validación de migraciones sin alterar el esquema.
    """
    await asyncio.to_thread(database.open)
    await asyncio.to_thread(database.verify_schema)
    try:
        yield
    finally:
        await asyncio.to_thread(database.close)


def runtime_for(model):
    """Reutiliza el runtime del modelo y actualiza su posición de uso; al añadir un tercero
    expulsa el menos reciente.

    Args:
        model: Modelo registrado con revisión, dimensiones, prefijos y ruta local de
            artefactos.

    Returns:
        runtime local del modelo; la construcción no descarga ni entrena artefactos.

    See Also:
        app.embeddings.EmbeddingRuntime: Carga y codificación offline del modelo seleccionado.
    """
    runtime = runtime_cache.get(model.model_version)
    if runtime is None:
        runtime = EmbeddingRuntime(
            model,
            device=settings.device,
            cache_dir=settings.model_cache_dir,
        )
        runtime_cache[model.model_version] = runtime
        while len(runtime_cache) > 2:
            runtime_cache.popitem(last=False)
    else:
        runtime_cache.move_to_end(model.model_version)
    return runtime


