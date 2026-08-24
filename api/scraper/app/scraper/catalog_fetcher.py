"""Implementa las responsabilidades del módulo `catalog_fetcher`."""

from __future__ import annotations

import asyncio
import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.logging import get_logger
from app.core.url_protector import UrlProtector
from app.db.enums import (
    ScrapeRunStatus,
    ScrapeScope,
)
from app.db.models import ScraperCommand
from app.repositories.catalog import CatalogRepository
from app.repositories.pipeline import (
    PipelineRepository,
)
from app.repositories.runs import ScrapeRunRepository
from app.scraper.content_workers import SOFilterWorker
from app.scraper.filter_worker import FilterWorker
from app.scraper.pipeline_runtime import (
    PipelineRuntime,
    ScrapeCounters,
    async_session_local,
)
from app.scraper.pipeline_support import (
    first_task_failure,
    is_stale_control_command,
)
from app.scraper.platform_worker import PlatformScraperWorker
from app.scraper.searcher_worker import SearcherWorker

logger = get_logger(__name__)
"""Estado global asociado a `logger`.
"""


class CatalogFetcher:
    """Representa el componente `CatalogFetcher`."""

    def __init__(self, settings: Settings, session: AsyncSession) -> None:
        """Inicializa una instancia de `CatalogFetcher`.

        Args:
            settings (Settings): Configuración del servicio.
            session (AsyncSession): Sesión de base de datos utilizada por la operación.
        """
        self.settings = settings
        """Estado de instancia asociado a `settings`.
        """
        self.session = session
        """Estado de instancia asociado a `session`.
        """
        self.url_protector = UrlProtector(settings.url_protection_secret)
        """Estado de instancia asociado a `url_protector`.
        """
        self.catalog = CatalogRepository(session, self.url_protector)
        """Estado de instancia asociado a `catalog`.
        """
        self.runs = ScrapeRunRepository(session, settings)
        """Estado de instancia asociado a `runs`.
        """

    async def scrape_once(
        self,
        recover_running: bool = False,
        *,
        scope: ScrapeScope = ScrapeScope.INCREMENTAL,
        selected_app_ids: list[uuid.UUID] | None = None,
        request_id: uuid.UUID | None = None,
    ) -> ScrapeCounters:
        """Ejecuta `scrape_once` dentro de `CatalogFetcher`.

        Args:
            recover_running (bool): Valor de `recover_running` utilizado por la operación.

        Returns:
            ScrapeCounters: Resultado producido por la operación.

        Throws:
            worker_error: Si no puede completarse la operación bajo las condiciones requeridas.
        """
        if recover_running:
            recovered = await self.runs.recover_running(
                "Recovered before startup scrape because the scheduler container was restarted."
            )
            if recovered:
                logger.warning("scrape_running_locks_recovered", recovered=recovered)
                await self.session.commit()

        selected_app_ids = selected_app_ids or []
        if scope == ScrapeScope.SELECTED and not selected_app_ids:
            raise ValueError("selected_scope_requires_app_ids")
        if scope != ScrapeScope.SELECTED and selected_app_ids:
            raise ValueError("app_ids_only_allowed_for_selected_scope")
        if len(selected_app_ids) > 500:
            raise ValueError("selected_scope_limit_exceeded")

        run = await self.runs.acquire(scope=scope, request_id=request_id)
        if run is None:
            logger.info("scrape_skipped", reason="active_recent_run")
            return ScrapeCounters()
        run_id = run.id
        if request_id is not None:
            request = await self.session.get(ScraperCommand, request_id)
            if request is not None:
                await self.runs.mark_run_request_started(request, run_id)
        await self.session.commit()

        repaired_platforms = await self.catalog.repair_resolved_source_platforms()
        if repaired_platforms:
            await self.session.commit()
            logger.warning("resolved_source_platforms_repaired", count=repaired_platforms)

        async with async_session_local()() as session:
            pipeline = PipelineRepository(session)
            recovered_items = await pipeline.reset_expired_leases()
            orphaned_run_items = await pipeline.recover_orphaned_run_items()
            pruned_snapshots = await pipeline.prune_expired_snapshots()
            pending_work = await pipeline.has_pending_work()
            await session.commit()
        if recovered_items:
            logger.warning("scraper_pipeline_leases_recovered", count=recovered_items)
        if orphaned_run_items:
            logger.warning("scraper_orphaned_run_items_recovered", count=orphaned_run_items)
        if pruned_snapshots:
            logger.info("scraper_snapshots_pruned", count=pruned_snapshots)

        runtime = PipelineRuntime(
            settings=self.settings,
            run_id=run_id,
            run_started_at=run.started_at,
            scope=scope,
            selected_app_ids=tuple(selected_app_ids),
            request_id=request_id,
        )
        logger.info(
            "scrape_started",
            run_id=str(run_id),
            scope=scope.value,
            request_id=str(request_id) if request_id else None,
            recover_running=recover_running,
            pending_work=pending_work,
            max_apps=self.settings.scrape_max_apps,
        )

        monitor_tasks = [
            asyncio.create_task(self._command_monitor(runtime), name="scraper-command-monitor"),
            asyncio.create_task(self._heartbeat(runtime), name="scraper-heartbeat"),
        ]

        worker_error: BaseException | None = None
        try:
            async with asyncio.TaskGroup() as workers:
                workers.create_task(
                    SearcherWorker(self.settings).run(runtime),
                    name="scraper-searcher",
                )
                workers.create_task(
                    FilterWorker(self.settings).run(runtime),
                    name="scraper-filter",
                )
                workers.create_task(
                    self._run_scraper_workers(runtime),
                    name="scraper-workers",
                )
                workers.create_task(
                    self._run_so_filter_workers(runtime),
                    name="scraper-so-filter-workers",
                )
        except Exception as exc:
            worker_error = first_task_failure(exc)
            runtime.stop_event.set()
        finally:
            runtime.all_workers_done.set()
            await asyncio.gather(*monitor_tasks, return_exceptions=True)

        final_status = (
            ScrapeRunStatus.FAILED
            if worker_error
            else ScrapeRunStatus.PARTIAL
            if runtime.stopped_by_command or runtime.counters.apps_transient_failed
            else ScrapeRunStatus.COMPLETED
        )
        await self.runs.finish(
            run_id,
            final_status,
            error_summary=worker_error.__class__.__name__
            if worker_error
            else ("Stopped by admin command" if runtime.stopped_by_command else None),
            **runtime.counters.__dict__,
        )
        if request_id is not None:
            await self.runs.finish_run_request(
                request_id,
                status=final_status.value,
                message=(
                    worker_error.__class__.__name__
                    if worker_error
                    else "Stopped by admin command"
                    if runtime.stopped_by_command
                    else None
                ),
            )
        await self.session.commit()
        if worker_error:
            raise worker_error
        return runtime.counters

    async def _command_monitor(self, runtime: PipelineRuntime) -> None:
        """Ejecuta el paso interno `_command_monitor`.

        Args:
            runtime (PipelineRuntime): Valor de `runtime` utilizado por la operación.
        """
        while not runtime.all_workers_done.is_set():
            async with async_session_local()() as session:
                runs = ScrapeRunRepository(session, self.settings)
                command = await runs.next_pending_command()
                if command:
                    if is_stale_control_command(command, runtime.run_started_at):
                        await runs.consume_command(
                            command,
                            status="rejected",
                            message="Ignored stale command from an older scraper run.",
                        )
                    elif command.command == "pause":
                        runtime.pause_event.set()
                        await runs.consume_command(command)
                        await runs.mark_paused(runtime.run_id)
                    elif command.command == "resume":
                        runtime.pause_event.clear()
                        await runs.consume_command(command)
                        await runs.set_current(runtime.run_id, None, None, "running")
                    elif command.command in {"stop", "force_stop"}:
                        runtime.stopped_by_command = True
                        runtime.stop_event.set()
                        runtime.pause_event.clear()
                        await runs.mark_stop_requested(runtime.run_id)
                        await runs.consume_command(command)
                    elif command.command == "run_once":
                        await runs.consume_command(
                            command,
                            status="rejected",
                            message="A scraper run is already active.",
                        )
                    else:
                        await runs.consume_command(
                            command,
                            status="failed",
                            message=f"Unsupported command: {command.command}",
                        )
                    await session.commit()
            await asyncio.sleep(2)

    async def _heartbeat(self, runtime: PipelineRuntime) -> None:
        """Ejecuta el paso interno `_heartbeat`.

        Args:
            runtime (PipelineRuntime): Valor de `runtime` utilizado por la operación.
        """
        while not runtime.all_workers_done.is_set():
            async with async_session_local()() as session:
                runs = ScrapeRunRepository(session, self.settings)
                pipeline = PipelineRepository(session)
                await runs.heartbeat(runtime.run_id, **runtime.counters.__dict__)
                await pipeline.save_metric_snapshot(runtime.run_id)
                await session.commit()
            await asyncio.sleep(5)

    async def _run_scraper_workers(self, runtime: PipelineRuntime) -> None:
        """Ejecuta el paso interno `_run_scraper_workers`.

        Args:
            runtime (PipelineRuntime): Valor de `runtime` utilizado por la operación.
        """
        workers = [
            PlatformScraperWorker(self.settings)
            for _ in range(max(1, self.settings.scrape_concurrency))
        ]
        try:
            await asyncio.gather(*(worker.run(runtime) for worker in workers))
        finally:
            runtime.scraper_done.set()

    async def _run_so_filter_workers(self, runtime: PipelineRuntime) -> None:
        """Ejecuta el paso interno `_run_so_filter_workers`.

        Args:
            runtime (PipelineRuntime): Valor de `runtime` utilizado por la operación.
        """
        workers = [
            SOFilterWorker(self.settings)
            for _ in range(max(1, self.settings.so_filter_concurrency))
        ]
        try:
            await asyncio.gather(*(worker.run(runtime) for worker in workers))
        finally:
            runtime.so_filter_done.set()
