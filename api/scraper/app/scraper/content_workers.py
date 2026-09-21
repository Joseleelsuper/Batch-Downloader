"""Encola y ejecuta los workers de filtro de sistemas operativos y generación de descripciones."""

from __future__ import annotations

import asyncio
import uuid
from typing import Any

from app.core.config import Settings
from app.core.logging import get_logger
from app.core.url_protector import UrlProtector
from app.db.enums import (
    LongDescriptionStatus,
)
from app.db.models import ScraperWorkItem, SoftwareApp
from app.repositories.catalog import CatalogRepository
from app.repositories.logs import ResolverLogRepository
from app.repositories.pipeline import (
    QUEUE_SCRAPER_SO_FILTER,
    QUEUE_SO_FILTER_DESCRIPTOR,
    PipelineRepository,
)
from app.repositories.runs import worker_id
from app.scraper.description_enricher import (
    AppDescriptionEnricher,
    AppDescriptionLLMClient,
    description_input_hash,
)
from app.scraper.pipeline_runtime import (
    PipelineRuntime,
    async_session_local,
)
from app.scraper.pipeline_support import (
    claim_item,
    exception_detail,
    finish_item,
    queue_has_active_work,
    set_current,
)

logger = get_logger(__name__)



async def enqueue_descriptor_for_app(
    catalog: CatalogRepository,
    pipeline: PipelineRepository,
    run_id: uuid.UUID | None,
    software_app: Any,
    *,
    force: bool,
    priority: int = 0,
) -> ScraperWorkItem | None:
    """Calcula la huella de descripción, evita duplicar un resultado vigente y encola el
    descriptor cuando falta o se fuerza.

    Args:
        catalog: Repositorio que lee y actualiza aplicaciones.
        pipeline: Repositorio que encola trabajos.
        run_id: UUID de ejecución al que se atribuye el trabajo.
        software_app: Aplicación que se coloca en una cola derivada.
        force: Indica si se debe ignorar un resultado completado.
        priority: Prioridad numérica de la entrada en la cola.

    Returns:
        trabajo encolado o None si la descripción ya está vigente.
    """
    apps = await catalog.apps_for_description_enrichment(
        [software_app.id],
        include_completed=True,
    )
    app = apps[0] if apps else software_app
    input_hash = description_input_hash(app)
    current = (
        not force
        and app.long_description_status == LongDescriptionStatus.COMPLETED.value
        and bool(app.long_description)
        and app.long_description_input_hash == input_hash
    )
    if current:
        return None
    await catalog.mark_long_description_pending(app.id)
    return await pipeline.enqueue(
        QUEUE_SO_FILTER_DESCRIPTOR,
        app.winstall_id,
        app.name,
        {
            "software_app_id": str(app.id),
            "package_id": app.winstall_id,
            "input_hash": input_hash,
            "force": force,
        },
        run_id,
        priority=priority,
        force=force,
    )


async def enqueue_so_filter_for_app(
    pipeline: PipelineRepository,
    run_id: uuid.UUID | None,
    software_app: Any,
    *,
    force: bool = False,
    priority: int = 0,
) -> ScraperWorkItem:
    """Encola el filtro de sistemas operativos con la versión de la aplicación como huella de
    entrada.

    Args:
        pipeline: Repositorio que encola trabajos.
        run_id: UUID de ejecución al que se atribuye el trabajo.
        software_app: Aplicación que se coloca en una cola derivada.
        force: Indica si se debe ignorar un resultado completado.
        priority: Prioridad numérica de la entrada en la cola.

    Returns:
        trabajo de filtro encolado.
    """
    return await pipeline.enqueue(
        QUEUE_SCRAPER_SO_FILTER,
        software_app.winstall_id,
        software_app.name,
        {
            "software_app_id": str(software_app.id),
            "package_id": software_app.winstall_id,
            "input_hash": f"{software_app.version}:{run_id or 'backfill'}",
            "force": force,
        },
        run_id,
        priority=priority,
        force=force,
    )


class DescriptorWorker:
    """Consume la cola de descripciones con concurrencia acotada y libera el presupuesto por
    aplicación.
    """

    def __init__(self, settings: Settings) -> None:
        """Configura identidad y cliente LLM del worker de descripciones.

        Args:
            settings: Configuración del servicio y sus límites.
        """
        self.settings = settings

        self.worker_id = f"descriptor:{worker_id()}"

        self.llm = AppDescriptionLLMClient(settings)


    async def run(self, runtime: PipelineRuntime) -> None:
        """Arranca consumidores hasta parada cooperativa y marca descriptor_done al finalizar.

        Args:
            runtime: Estado compartido del pipeline.
        """
        if not self.llm.has_provider():
            logger.warning("descriptor_worker_idle", reason="llm_provider_not_configured")
            runtime.descriptor_done.set()
            return
        workers = [
            asyncio.create_task(self._consume(runtime), name=f"descriptor-worker-{index}")
            for index in range(max(1, self.settings.llm_max_concurrency))
        ]
        try:
            await asyncio.gather(*workers)
        finally:
            runtime.descriptor_done.set()

    async def process_one(self) -> bool:
        """Procesa un único mensaje para pruebas o ejecución puntual.

        Returns:
            False si el proveedor no existe o la cola está vacía.
        """
        if not self.llm.has_provider():
            logger.warning("descriptor_process_one_skipped", reason="llm_provider_not_configured")
            return False
        item = await claim_item(self.settings, QUEUE_SO_FILTER_DESCRIPTOR, self.worker_id)
        if item is None:
            return False
        return await self._process_claimed_item(None, item)

    async def _consume(self, runtime: PipelineRuntime) -> None:
        """Reserva presupuesto y mensajes y espera a que termine la fase SO filter antes de
        salir.

        Args:
            runtime: Estado compartido del pipeline.
        """
        while not runtime.stop_event.is_set():
            if not await runtime.before_next_item():
                break
            if not await runtime.reserve_descriptor_attempt():
                logger.info("descriptor_budget_exhausted")
                break
            item = await claim_item(
                self.settings,
                QUEUE_SO_FILTER_DESCRIPTOR,
                self.worker_id,
                run_id=runtime.run_id,
            )
            if item is None:
                await runtime.release_descriptor_attempt()
                if runtime.so_filter_done.is_set():
                    break
                await asyncio.sleep(1)
                continue
            await self._process_claimed_item(runtime, item)

    async def _process_claimed_item(
        self,
        runtime: PipelineRuntime | None,
        item: ScraperWorkItem,
    ) -> bool:
        """Carga la aplicación, genera o reutiliza su descripción y completa, descarta o falla el
        mensaje.

        Args:
            runtime: Estado compartido del pipeline.
            item: Mensaje de pipeline reservado.

        Returns:
            True si se aceptó el resultado.
        """
        payload = item.payload_json or {}
        software_app_id = payload.get("software_app_id")
        if not software_app_id:
            await finish_item(self.settings, item, "discard", "missing_software_app_id")
            return False
        try:
            if runtime:
                await set_current(
                    self.settings,
                    runtime.run_id,
                    payload.get("package_id") or item.package_id,
                    item.app_name,
                    "descriptor_generating_description",
                )
            async with async_session_local()() as session:
                catalog = CatalogRepository(
                    session,
                    UrlProtector(self.settings.url_protection_secret),
                )
                logs = ResolverLogRepository(session)
                result = await AppDescriptionEnricher(
                    self.settings,
                    catalog,
                    logs,
                    llm=self.llm,
                ).enrich_app(
                    software_app_id,
                    force=bool(payload.get("force")),
                    release_database_connection=session.commit,
                )
                await session.commit()
            if result.status in {"completed", "skipped"}:
                await finish_item(self.settings, item, "complete", result.status)
                return True
            if result.status == "missing":
                await finish_item(self.settings, item, "discard", result.status)
                if runtime:
                    await runtime.release_descriptor_attempt()
                return False
            if result.status == "pending":
                await finish_item(self.settings, item, "fail", result.error or result.status)
                if runtime:
                    await runtime.release_descriptor_attempt()
                return False
            await finish_item(self.settings, item, "fail", result.error or result.status)
            return False
        except Exception as exc:
            await finish_item(self.settings, item, "fail", exc.__class__.__name__)
            logger.warning(
                "descriptor_app_failed",
                winstall_id=item.package_id,
                error=exc.__class__.__name__,
                detail=exception_detail(exc),
            )
            return False


class SOFilterWorker:
    """Actualiza plataformas verificadas y dispara la generación de descripción posterior."""

    def __init__(self, settings: Settings) -> None:
        """Configura identidad del worker de filtro.

        Args:
            settings: Configuración del servicio y sus límites.
        """
        self.settings = settings

        self.worker_id = f"so-filter:{worker_id()}"


    async def run(self, runtime: PipelineRuntime) -> None:
        """Consume filtro hasta que no queden trabajos activos después del scraper.

        Args:
            runtime: Estado compartido del pipeline.
        """
        while not runtime.stop_event.is_set():
            if not await runtime.before_next_item():
                break
            processed = await self.process_one(runtime)
            if not processed:
                if runtime.scraper_done.is_set() and not await queue_has_active_work(
                    self.settings,
                    QUEUE_SCRAPER_SO_FILTER,
                    runtime.run_id,
                ):
                    break
                await asyncio.sleep(1)

    async def process_one(self, runtime: PipelineRuntime | None = None) -> bool:
        """Reserva y procesa un mensaje de filtro.

        Args:
            runtime: Estado compartido del pipeline.

        Returns:
            False si no existe trabajo.
        """
        item = await claim_item(
            self.settings,
            QUEUE_SCRAPER_SO_FILTER,
            self.worker_id,
            run_id=runtime.run_id if runtime is not None else None,
        )
        if item is None:
            return False
        return await self._process_claimed_item(item, runtime)

    async def _process_claimed_item(
        self,
        item: ScraperWorkItem,
        runtime: PipelineRuntime | None,
    ) -> bool:
        """Recalcula plataformas, encola descriptor y reintenta fallos según el límite
        configurado.

        Args:
            item: Mensaje de pipeline reservado.
            runtime: Estado compartido del pipeline.

        Returns:
            True cuando el mensaje se completa o descarta.
        """
        software_app_id = (item.payload_json or {}).get("software_app_id")
        if not software_app_id:
            await finish_item(self.settings, item, "discard", "missing_software_app_id")
            return True
        try:
            app_id = uuid.UUID(str(software_app_id))
        except TypeError, ValueError:
            await finish_item(self.settings, item, "discard", "invalid_software_app_id")
            return True

        try:
            if runtime is not None:
                await set_current(
                    self.settings,
                    runtime.run_id,
                    item.package_id,
                    item.app_name,
                    "so_filter_deriving_operating_systems",
                )
            async with async_session_local()() as session:
                catalog = CatalogRepository(
                    session,
                    UrlProtector(self.settings.url_protection_secret),
                )
                pipeline = PipelineRepository(session)
                software_app = await session.get(SoftwareApp, app_id)
                if software_app is None:
                    await session.commit()
                    await finish_item(self.settings, item, "discard", "software_app_missing")
                    return True
                systems = await catalog.sources.refresh_operating_systems(app_id)
                await enqueue_descriptor_for_app(
                    catalog,
                    pipeline,
                    item.run_id,
                    software_app,
                    force=False,
                )
                await session.commit()
            await finish_item(
                self.settings,
                item,
                "complete",
                ",".join(systems or []) or "no_verified_installers",
            )
            return True
        except Exception as exc:
            action = "requeue" if item.attempts < self.settings.so_filter_max_attempts else "fail"
            await finish_item(
                self.settings,
                item,
                action,
                exc.__class__.__name__,
                delay_seconds=min(300, 2 ** max(1, item.attempts)),
            )
            logger.warning(
                "so_filter_failed",
                winstall_id=item.package_id,
                error=exc.__class__.__name__,
                detail=exception_detail(exc),
            )
            return True
