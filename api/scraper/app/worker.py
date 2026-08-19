"""Implementa las responsabilidades del módulo `worker`.
"""
from __future__ import annotations

import argparse
import asyncio
import uuid
from collections.abc import Awaitable, Callable
from functools import partial

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from sqlalchemy import select
from sqlalchemy.exc import TimeoutError as SQLAlchemyTimeoutError

from app.core.config import get_settings
from app.core.free_threading import assert_free_threaded_runtime
from app.core.logging import configure_logging, get_logger
from app.core.url_protector import UrlProtector
from app.db.enums import ResolutionStatus, ScrapeRunStatus, ScrapeScope, ValidationStatus
from app.db.models import ScrapeRun
from app.db.session import AsyncSessionLocal
from app.repositories.catalog import CatalogRepository, ResolvedSourceCreate
from app.repositories.catalog_projection import CatalogProjectionRepository
from app.repositories.logs import ResolverLogRepository
from app.repositories.pipeline import (
    QUEUE_FILTER_SCRAPER,
    QUEUE_SCRAPER_SO_FILTER,
    QUEUE_SEARCHER_FILTER,
    STATUS_COMPLETED,
    STATUS_DISCARDED,
    PipelineRepository,
)
from app.repositories.runs import ScrapeRunRepository
from app.scraper.candidates import extract_version, infer_architecture, registered_domain
from app.scraper.catalog_fetcher import (
    CatalogFetcher,
    DescriptorWorker,
    SOFilterWorker,
    ValidInstaller,
    enqueue_so_filter_for_app,
    infer_validated_operating_system,
    known_official_candidates,
    resolved_metadata,
)
from app.scraper.manual_installer import ManualInstallerWorker
from app.scraper.validator import DownloadValidator
from app.scraper.website_discovery import WebsiteAppDiscoveryWorker
from app.scraper.winstall import WinstallClient

logger = get_logger(__name__)
"""Estado global asociado a `logger`.
"""


class ContentEnrichmentSupervisor:
    """Representa el componente `ContentEnrichmentSupervisor`.
    """

    def __init__(self) -> None:
        """Inicializa una instancia de `ContentEnrichmentSupervisor`.
        """
        self.settings = get_settings()
        """Estado de instancia asociado a `settings`.
        """

    async def run(self) -> None:
        """Ejecuta `run` dentro de `ContentEnrichmentSupervisor`.
        """
        async with AsyncSessionLocal() as session:
            pipeline = PipelineRepository(session)
            recovered = await pipeline.reset_expired_leases()
            orphaned = await pipeline.recover_orphaned_run_items()
            await session.commit()
        if recovered or orphaned:
            logger.info(
                "content_enrichment_leases_recovered",
                expired=recovered,
                orphaned=orphaned,
            )

        workers = [
            asyncio.create_task(
                self._restart_on_pool_timeout(
                    "descriptor",
                    self._consume_descriptions,
                ),
                name="descriptor-supervisor",
            ),
            asyncio.create_task(
                self._restart_on_pool_timeout(
                    "manual-installer",
                    self._consume_manual_installers,
                ),
                name="manual-installer-supervisor",
            ),
            asyncio.create_task(
                self._restart_on_pool_timeout(
                    "website-discovery",
                    self._consume_website_discoveries,
                ),
                name="website-discovery-supervisor",
            ),
        ]
        workers.extend(
            asyncio.create_task(
                self._restart_on_pool_timeout(
                    f"so-filter-{index}",
                    partial(self._consume_so_filters, index),
                ),
                name=f"so-filter-supervisor-{index}",
            )
            for index in range(max(1, self.settings.so_filter_concurrency))
        )
        try:
            await asyncio.gather(*workers)
        finally:
            for task in workers:
                task.cancel()
            await asyncio.gather(*workers, return_exceptions=True)

    async def _restart_on_pool_timeout(
        self,
        component: str,
        consumer: Callable[[], Awaitable[None]],
    ) -> None:
        """Reintenta un consumidor cuando la contención agota temporalmente el pool.

        Los demás errores siguen propagándose para que Docker reinicie un scheduler
        realmente averiado. Un timeout de adquisición, en cambio, es esperable bajo
        la concurrencia interna y no debe derribar todos los consumidores.

        Args:
            component (str): Nombre estable del consumidor afectado.
            consumer (Callable[[], Awaitable[None]]): Bucle de consumo supervisado.
        """
        while True:
            try:
                await consumer()
            except asyncio.CancelledError:
                raise
            except SQLAlchemyTimeoutError:
                logger.warning(
                    "content_enrichment_pool_timeout",
                    component=component,
                    pool_size=self.settings.database_pool_max,
                    timeout_seconds=self.settings.database_pool_timeout_seconds,
                )
                await asyncio.sleep(1)

    async def _consume_descriptions(self) -> None:
        """Ejecuta el paso interno `_consume_descriptions`.
        """
        worker = DescriptorWorker(self.settings)
        while True:
            if await self._scrape_run_active():
                await asyncio.sleep(1)
                continue
            if await self._paused_or_stopping():
                await asyncio.sleep(1)
                continue
            if not worker.llm.has_provider():
                await asyncio.sleep(15)
                continue
            processed = await worker.process_one()
            if not processed:
                await asyncio.sleep(1)

    async def _consume_manual_installers(self) -> None:
        """Ejecuta el paso interno `_consume_manual_installers`.
        """
        worker = ManualInstallerWorker(self.settings)
        while True:
            processed = await worker.process_one()
            if not processed:
                await asyncio.sleep(1)

    async def _consume_website_discoveries(self) -> None:
        """Ejecuta el paso interno `_consume_website_discoveries`.
        """
        worker = WebsiteAppDiscoveryWorker(self.settings)
        while True:
            processed = await worker.process_one()
            if not processed:
                await asyncio.sleep(1)

    async def _consume_so_filters(self, index: int) -> None:
        """Ejecuta el paso interno `_consume_so_filters`.

        Args:
            index (int): Valor de `index` utilizado por la operación.
        """
        worker = SOFilterWorker(self.settings)
        while True:
            if await self._paused_or_stopping():
                await asyncio.sleep(1)
                continue
            if index == 0:
                try:
                    await self._enqueue_pending_so_filters()
                except asyncio.CancelledError:
                    raise
                except Exception as exc:  # noqa: BLE001 - mantiene activo el supervisor
                    logger.exception(
                        "so_filter_backfill_failed",
                        error=exc.__class__.__name__,
                    )
                    await asyncio.sleep(5)
                    continue
            processed = await worker.process_one()
            if not processed:
                await asyncio.sleep(1)

    async def _enqueue_pending_so_filters(self) -> None:
        """Ejecuta el paso interno `_enqueue_pending_so_filters`.
        """
        async with AsyncSessionLocal() as session:
            catalog = CatalogRepository(
                session,
                UrlProtector(self.settings.url_protection_secret),
            )
            pipeline = PipelineRepository(session)
            apps = await catalog.apps_pending_os_filter()
            package_ids = [app.winstall_id for app in apps]
            statuses = await pipeline.item_statuses(
                QUEUE_SCRAPER_SO_FILTER,
                package_ids,
            )
            upstream_active = await pipeline.active_package_ids(
                (QUEUE_SEARCHER_FILTER, QUEUE_FILTER_SCRAPER),
                package_ids,
            )
            for app in apps:
                if app.winstall_id in upstream_active:
                    continue
                status = statuses.get(app.winstall_id)
                if status is not None and status not in {STATUS_COMPLETED, STATUS_DISCARDED}:
                    continue
                await enqueue_so_filter_for_app(
                    pipeline,
                    None,
                    app,
                    force=True,
                )
            await session.commit()

    async def _paused_or_stopping(self) -> bool:
        """Ejecuta el paso interno `_paused_or_stopping`.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        async with AsyncSessionLocal() as session:
            run = await session.scalar(
                select(ScrapeRun)
                .where(ScrapeRun.status == ScrapeRunStatus.RUNNING.value)
                .order_by(ScrapeRun.started_at.desc())
                .limit(1)
            )
            return bool(run and (run.paused_at is not None or run.stop_requested))

    async def _scrape_run_active(self) -> bool:
        """Reserva el pool acotado para el pipeline mientras exista un run activo.

        Las descripciones se conservan en su cola durable y se enriquecen al terminar
        el scrape. Así una llamada LLM no ocupa una de las dos conexiones durante el
        procesamiento de instaladores.
        """
        async with AsyncSessionLocal() as session:
            run_id = await session.scalar(
                select(ScrapeRun.id)
                .where(ScrapeRun.status == ScrapeRunStatus.RUNNING.value)
                .limit(1)
            )
            return run_id is not None


async def scrape_once(
    recover_running: bool = False,
    *,
    scope: ScrapeScope = ScrapeScope.INCREMENTAL,
    selected_app_ids: list[uuid.UUID] | None = None,
    request_id: uuid.UUID | None = None,
) -> None:
    """Ejecuta la operación `scrape_once`.

    Args:
        recover_running (bool): Valor de `recover_running` utilizado por la operación.
    """
    settings = get_settings()
    async with AsyncSessionLocal() as session:
        try:
            counters = await CatalogFetcher(settings, session).scrape_once(
                recover_running=recover_running,
                scope=scope,
                selected_app_ids=selected_app_ids,
                request_id=request_id,
            )
        except Exception as exc:
            await session.rollback()
            logger.warning("scrape_once_failed", error=exc.__class__.__name__)
            raise
    logger.info("scrape_finished", scope=scope.value, **counters.__dict__)


async def enqueue_scrape_request(
    scope: ScrapeScope,
    *,
    created_by: str,
    app_ids: list[str] | None = None,
) -> uuid.UUID:
    """Persiste una solicitud para que el coordinador la reclame sin solapamientos."""
    settings = get_settings()
    async with AsyncSessionLocal() as session:
        request = await ScrapeRunRepository(session, settings).enqueue_run_request(
            scope=scope,
            app_ids=app_ids,
            created_by=created_by,
        )
        await session.commit()
        return request.id


async def run_request_dispatcher() -> None:
    """Reclama solicitudes durables de una en una y conserva las pendientes."""
    settings = get_settings()
    while True:
        request_id: uuid.UUID | None = None
        try:
            async with AsyncSessionLocal() as session:
                repository = ScrapeRunRepository(session, settings)
                request = await repository.next_pending_run_request()
                if request is None:
                    await session.rollback()
                else:
                    request_id = request.id
                    try:
                        scope = ScrapeScope(request.scope or ScrapeScope.INCREMENTAL.value)
                        selected_ids = [
                            uuid.UUID(value) for value in (request.app_ids_json or [])
                        ]
                        if (scope == ScrapeScope.SELECTED) != bool(selected_ids):
                            raise ValueError("invalid_scope_selection")
                        if len(selected_ids) > 500:
                            raise ValueError("selected_scope_limit_exceeded")
                    except (TypeError, ValueError):
                        await repository.consume_command(
                            request,
                            status="failed",
                            message="Invalid durable scrape request payload.",
                        )
                        await session.commit()
                    else:
                        await CatalogFetcher(settings, session).scrape_once(
                            scope=scope,
                            selected_app_ids=selected_ids,
                            request_id=request.id,
                        )
        except Exception as exc:
            logger.warning(
                "scrape_request_dispatch_failed",
                request_id=str(request_id) if request_id else None,
                error=exc.__class__.__name__,
            )
        await asyncio.sleep(2)


async def repair_platforms() -> None:
    """Ejecuta la operación `repair_platforms`.
    """
    settings = get_settings()
    async with AsyncSessionLocal() as session:
        catalog = CatalogRepository(session, UrlProtector(settings.url_protection_secret))
        repaired = await catalog.repair_resolved_source_platforms()
        await session.commit()
    logger.info("platform_repair_finished", repaired=repaired)


async def repair_source_statuses() -> None:
    """Ejecuta la operación `repair_source_statuses`.
    """
    settings = get_settings()
    async with AsyncSessionLocal() as session:
        catalog = CatalogRepository(session, UrlProtector(settings.url_protection_secret))
        repaired = await catalog.repair_source_statuses()
        await session.commit()
    logger.info("source_status_repair_finished", repaired=repaired)


async def maintain_catalog_projection(*, repair: bool) -> None:
    """Ejecuta la operación `maintain_catalog_projection`.

    Args:
        repair (bool): Valor de `repair` utilizado por la operación.

    Throws:
        RuntimeError: Si el estado de ejecución impide completar la operación.
    """
    async with AsyncSessionLocal() as session:
        projection = CatalogProjectionRepository(session)
        report = await projection.repair() if repair else await projection.check()
    logger.info(
        "catalog_projection_checked",
        repair=repair,
        **report.log_fields(),
    )
    if not report.consistent:
        raise RuntimeError("catalog_projection_inconsistent")


async def repair_known_apps() -> None:
    """Ejecuta la operación `repair_known_apps`.
    """
    settings = get_settings()
    repaired = 0
    async with WinstallClient(settings) as winstall:
        for package_id in ("EpicGames.EpicGamesLauncher", "ItchIo.Itch"):
            app = await winstall.get_app(package_id)
            candidates = known_official_candidates(app)
            if not candidates:
                continue
            async with AsyncSessionLocal() as session:
                catalog = CatalogRepository(session, UrlProtector(settings.url_protection_secret))
                logs = ResolverLogRepository(session)
                software_app = await catalog.upsert_winstall_app(app)
                validator = DownloadValidator(settings)
                for candidate in candidates:
                    result = await validator.validate(candidate)
                    if not result.ok:
                        await logs.add(
                            phase="known_official",
                            status="rejected",
                            safe_metadata={
                                "winstall_id": app.package_id,
                                "reason": result.reason,
                                "domain": registered_domain(candidate.url),
                            },
                        )
                        continue
                    operating_system = infer_validated_operating_system(candidate, result)
                    if not operating_system:
                        continue
                    source = await catalog.ensure_download_source(
                        software_app_id=software_app.id,
                        app=app,
                        operating_system=operating_system,
                        architecture=infer_architecture(candidate),
                        initial_url=app.homepage,
                    )
                    await catalog.expire_valid_resolved_sources(source.id)
                    installer = ValidInstaller(
                        candidate=candidate,
                        result=result,
                        status=ResolutionStatus.DIRECT,
                        operating_system=operating_system,
                        architecture=infer_architecture(candidate),
                        version=extract_version(candidate) or app.latest_version,
                    )
                    await catalog.save_resolved_source(
                        ResolvedSourceCreate(
                            source_id=source.id,
                            url=result.final_url or candidate.url,
                            final_domain=result.final_domain
                            or registered_domain(result.final_url or candidate.url)
                            or "",
                            filename=result.filename,
                            extension=result.extension,
                            content_type=result.content_type,
                            size_bytes=result.size_bytes,
                            version=installer.version,
                            score=max(candidate.score, 250),
                            status=ResolutionStatus.DIRECT,
                            validation_status=ValidationStatus.VALID,
                            release_rank=0,
                            is_latest=True,
                            version_status="latest",
                            metadata=resolved_metadata(installer, True),
                        )
                    )
                    await logs.add(
                        phase="known_official",
                        status="direct",
                        download_source_id=source.id,
                        safe_metadata={
                            "winstall_id": app.package_id,
                            "domain": result.final_domain,
                            "extension": result.extension,
                        },
                    )
                    repaired += 1
                await session.commit()
    logger.info("known_apps_repair_finished", repaired=repaired)


async def run_startup_scrape() -> None:
    """Ejecuta la operación `startup_scrape`.
    """
    try:
        await repair_known_apps()
    except Exception as exc:
        logger.warning(
            "startup_known_apps_repair_failed",
            error=exc.__class__.__name__,
        )
    recovered = await recover_scheduler_runs()
    if recovered:
        logger.warning("scrape_running_locks_recovered", recovered=recovered)
    await enqueue_scrape_request(
        ScrapeScope.INCREMENTAL,
        created_by="scheduler:startup",
    )


async def recover_scheduler_runs() -> int:
    """Cierra leases de coordinador interrumpidas antes de aceptar trabajo nuevo."""
    settings = get_settings()
    async with AsyncSessionLocal() as session:
        recovered = await ScrapeRunRepository(session, settings).recover_running(
            "Recovered before startup because the scheduler container was restarted."
        )
        await session.commit()
    return recovered


async def run_scheduler() -> None:
    """Ejecuta la operación `scheduler`.
    """
    settings = get_settings()
    scheduler = AsyncIOScheduler(timezone=settings.scheduler_zoneinfo)
    scheduler.add_job(
        enqueue_scrape_request,
        trigger="cron",
        day_of_week="mon-sat",
        hour=settings.scheduler_hour,
        minute=settings.scheduler_minute,
        kwargs={
            "scope": ScrapeScope.INCREMENTAL,
            "created_by": "scheduler:incremental",
        },
        id="incremental-winstall-scrape",
        replace_existing=True,
        max_instances=1,
        coalesce=True,
    )
    scheduler.add_job(
        enqueue_scrape_request,
        trigger="cron",
        day_of_week="sun",
        hour=settings.scheduler_hour,
        minute=settings.scheduler_minute,
        kwargs={
            "scope": ScrapeScope.FULL,
            "created_by": "scheduler:full",
        },
        id="weekly-full-winstall-scrape",
        replace_existing=True,
        max_instances=1,
        coalesce=True,
    )
    scheduler.start()
    enrichment_task = asyncio.create_task(
        ContentEnrichmentSupervisor().run(),
        name="content-enrichment-supervisor",
    )
    dispatcher_task = asyncio.create_task(
        run_request_dispatcher(),
        name="scrape-request-dispatcher",
    )
    logger.info(
        "scheduler_started",
        hour=settings.scheduler_hour,
        minute=settings.scheduler_minute,
        timezone=settings.scheduler_timezone,
        run_on_startup=settings.run_on_startup,
    )
    if settings.run_on_startup:
        scheduler.add_job(
            run_startup_scrape,
            trigger="date",
            id="startup-winstall-scrape",
            replace_existing=True,
            max_instances=1,
        )
        logger.info("startup_scrape_scheduled")
    try:
        # El supervisor es el trabajo persistente real del contenedor. Si termina por
        # una excepción no controlada, se propaga el fallo para que Docker pueda
        # reiniciar el proceso en lugar de dejar un scheduler aparentemente sano.
        await asyncio.gather(enrichment_task, dispatcher_task)
    finally:
        for task in (enrichment_task, dispatcher_task):
            if not task.done():
                task.cancel()
        await asyncio.gather(enrichment_task, dispatcher_task, return_exceptions=True)
        scheduler.shutdown(wait=False)


def main() -> None:
    """Ejecuta el punto de entrada del módulo.
    """
    configure_logging()
    assert_free_threaded_runtime()
    parser = argparse.ArgumentParser(description="Batch Downloader scraper worker")
    parser.add_argument(
        "command",
        choices=(
            "scrape-once",
            "scheduler",
            "repair-platforms",
            "repair-source-statuses",
            "repair-known-apps",
            "catalog-projection-check",
            "catalog-projection-repair",
        ),
    )
    args = parser.parse_args()
    if args.command == "scrape-once":
        asyncio.run(scrape_once())
    elif args.command == "repair-platforms":
        asyncio.run(repair_platforms())
    elif args.command == "repair-source-statuses":
        asyncio.run(repair_source_statuses())
    elif args.command == "repair-known-apps":
        asyncio.run(repair_known_apps())
    elif args.command == "catalog-projection-check":
        asyncio.run(maintain_catalog_projection(repair=False))
    elif args.command == "catalog-projection-repair":
        asyncio.run(maintain_catalog_projection(repair=True))
    else:
        asyncio.run(run_scheduler())


if __name__ == "__main__":
    main()
