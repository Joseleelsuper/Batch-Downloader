"""Implementa las responsabilidades del módulo `catalog_fetcher`.
"""
from __future__ import annotations

import asyncio
import uuid
from dataclasses import dataclass, field, replace
from datetime import datetime
from typing import Any
from urllib.parse import urlparse, urlunparse

import httpx
from packaging.version import InvalidVersion, Version
from sqlalchemy.exc import OperationalError, StatementError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.cpu_pool import run_cpu_bound
from app.core.json_safe import json_safe
from app.core.logging import get_logger
from app.core.url_protector import UrlProtector
from app.db.enums import LongDescriptionStatus, ResolutionStatus, ScrapeRunStatus, ValidationStatus
from app.db.models import ScraperWorkItem, SoftwareApp
from app.repositories.catalog import CatalogRepository, ResolvedSourceCreate
from app.repositories.logs import ResolverLogRepository
from app.repositories.pipeline import (
    QUEUE_FILTER_SCRAPER,
    QUEUE_SCRAPER_SO_FILTER,
    QUEUE_SEARCHER_FILTER,
    QUEUE_SO_FILTER_DESCRIPTOR,
    PipelineRepository,
)
from app.repositories.runs import ScrapeRunRepository, worker_id
from app.scraper.candidates import (
    InstallerCandidate,
    candidate_has_download_intent,
    candidate_variants,
    detect_extension,
    extract_candidates,
    extract_version,
    infer_architecture,
    infer_operating_system,
    is_download_candidate,
    operating_system_for_extension,
    registered_domain,
    score_candidate,
)
from app.scraper.description_enricher import (
    AppDescriptionEnricher,
    AppDescriptionLLMClient,
    description_input_hash,
)
from app.scraper.github import GitHubReleaseResolver, parse_github_repo
from app.scraper.icon_resolver import IconResolver
from app.scraper.playwright_fallback import PlaywrightCandidateCollector
from app.scraper.strategies import (
    CandidateResolverStrategy,
    CandidateResolverStrategyRegistry,
    ScrapeRuntime,
)
from app.scraper.validator import DownloadValidator, ValidationResult, domain_has_public_dns
from app.scraper.winstall import WinstallApp, WinstallClient, parse_winstall_app

logger = get_logger(__name__)
"""Estado global asociado a `logger`.
"""


def async_session_local():
    """Ejecuta la operación `async_session_local`.
    """
    from app.db.session import AsyncSessionLocal

    return AsyncSessionLocal


@dataclass
class ScrapeCounters:
    """Representa el componente `ScrapeCounters`.
    """
    apps_discovered: int = 0
    """Atributo de clase `apps_discovered` de `ScrapeCounters`.
    """
    apps_resolved: int = 0
    """Atributo de clase `apps_resolved` de `ScrapeCounters`.
    """
    apps_failed: int = 0
    """Atributo de clase `apps_failed` de `ScrapeCounters`.
    """
    apps_skipped: int = 0
    """Atributo de clase `apps_skipped` de `ScrapeCounters`.
    """


@dataclass
class PipelineRuntime:
    """Mantiene el estado de ejecución de `Pipeline`.
    """
    settings: Settings
    """Atributo de clase `settings` de `PipelineRuntime`.
    """
    run_id: uuid.UUID
    """Atributo de clase `run_id` de `PipelineRuntime`.
    """
    run_started_at: datetime
    """Atributo de clase `run_started_at` de `PipelineRuntime`.
    """
    counters: ScrapeCounters = field(default_factory=ScrapeCounters)
    """Atributo de clase `counters` de `PipelineRuntime`.
    """
    stop_event: asyncio.Event = field(default_factory=asyncio.Event)
    """Atributo de clase `stop_event` de `PipelineRuntime`.
    """
    pause_event: asyncio.Event = field(default_factory=asyncio.Event)
    """Atributo de clase `pause_event` de `PipelineRuntime`.
    """
    searcher_done: asyncio.Event = field(default_factory=asyncio.Event)
    """Atributo de clase `searcher_done` de `PipelineRuntime`.
    """
    filter_done: asyncio.Event = field(default_factory=asyncio.Event)
    """Atributo de clase `filter_done` de `PipelineRuntime`.
    """
    scraper_done: asyncio.Event = field(default_factory=asyncio.Event)
    """Atributo de clase `scraper_done` de `PipelineRuntime`.
    """
    so_filter_done: asyncio.Event = field(default_factory=asyncio.Event)
    """Atributo de clase `so_filter_done` de `PipelineRuntime`.
    """
    descriptor_done: asyncio.Event = field(default_factory=asyncio.Event)
    """Atributo de clase `descriptor_done` de `PipelineRuntime`.
    """
    all_workers_done: asyncio.Event = field(default_factory=asyncio.Event)
    """Atributo de clase `all_workers_done` de `PipelineRuntime`.
    """
    stopped_by_command: bool = False
    """Atributo de clase `stopped_by_command` de `PipelineRuntime`.
    """
    _counter_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    """Atributo de clase `_counter_lock` de `PipelineRuntime`.
    """
    _descriptor_budget_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    """Atributo de clase `_descriptor_budget_lock` de `PipelineRuntime`.
    """
    _descriptor_attempts: int = 0
    """Atributo de clase `_descriptor_attempts` de `PipelineRuntime`.
    """

    async def before_next_item(self) -> bool:
        """Ejecuta `before_next_item` dentro de `PipelineRuntime`.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        while self.pause_event.is_set() and not self.stop_event.is_set():
            await asyncio.sleep(1)
        return not self.stop_event.is_set()

    async def increment(self, field_name: str, amount: int = 1) -> None:
        """Ejecuta `increment` dentro de `PipelineRuntime`.

        Args:
            field_name (str): Valor de `field_name` utilizado por la operación.
            amount (int): Valor de `amount` utilizado por la operación.
        """
        async with self._counter_lock:
            setattr(self.counters, field_name, getattr(self.counters, field_name) + amount)

    async def reserve_descriptor_attempt(self) -> bool:
        """Ejecuta `reserve_descriptor_attempt` dentro de `PipelineRuntime`.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        async with self._descriptor_budget_lock:
            maximum = self.settings.llm_max_apps_per_run
            if maximum > 0 and self._descriptor_attempts >= maximum:
                return False
            self._descriptor_attempts += 1
            return True

    async def release_descriptor_attempt(self) -> None:
        """Libera la operación `descriptor_attempt`.
        """
        async with self._descriptor_budget_lock:
            self._descriptor_attempts = max(0, self._descriptor_attempts - 1)


@dataclass(frozen=True)
class ValidInstaller:
    """Representa el componente `ValidInstaller`.
    """
    candidate: InstallerCandidate
    """Atributo de clase `candidate` de `ValidInstaller`.
    """
    result: ValidationResult
    """Atributo de clase `result` de `ValidInstaller`.
    """
    status: ResolutionStatus
    """Atributo de clase `status` de `ValidInstaller`.
    """
    operating_system: str
    """Atributo de clase `operating_system` de `ValidInstaller`.
    """
    architecture: str
    """Atributo de clase `architecture` de `ValidInstaller`.
    """
    version: str | None
    """Atributo de clase `version` de `ValidInstaller`.
    """


@dataclass
class CandidateValidationDiagnostics:
    """Representa el componente `CandidateValidationDiagnostics`.
    """
    discovered: int = 0
    """Atributo de clase `discovered` de `CandidateValidationDiagnostics`.
    """
    eligible: int = 0
    """Atributo de clase `eligible` de `CandidateValidationDiagnostics`.
    """
    attempted: int = 0
    """Atributo de clase `attempted` de `CandidateValidationDiagnostics`.
    """
    valid: int = 0
    """Atributo de clase `valid` de `CandidateValidationDiagnostics`.
    """
    skipped: dict[str, int] = field(default_factory=dict)
    """Atributo de clase `skipped` de `CandidateValidationDiagnostics`.
    """
    rejected: dict[str, int] = field(default_factory=dict)
    """Atributo de clase `rejected` de `CandidateValidationDiagnostics`.
    """
    errors: dict[str, int] = field(default_factory=dict)
    """Atributo de clase `errors` de `CandidateValidationDiagnostics`.
    """

    def skip(self, reason: str) -> None:
        """Ejecuta `skip` dentro de `CandidateValidationDiagnostics`.

        Args:
            reason (str): Valor de `reason` utilizado por la operación.
        """
        self.skipped[reason] = self.skipped.get(reason, 0) + 1

    def reject(self, reason: str | None) -> None:
        """Ejecuta `reject` dentro de `CandidateValidationDiagnostics`.

        Args:
            reason (str | None): Valor de `reason` utilizado por la operación.
        """
        key = reason or "unknown"
        self.rejected[key] = self.rejected.get(key, 0) + 1

    def error(self, exc: Exception) -> None:
        """Ejecuta `error` dentro de `CandidateValidationDiagnostics`.

        Args:
            exc (Exception): Valor de `exc` utilizado por la operación.
        """
        key = exc.__class__.__name__
        self.errors[key] = self.errors.get(key, 0) + 1

    def as_metadata(self) -> dict[str, Any]:
        """Ejecuta `as_metadata` dentro de `CandidateValidationDiagnostics`.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.
        """
        return {
            "discovered": self.discovered,
            "eligible": self.eligible,
            "attempted": self.attempted,
            "valid": self.valid,
            "skipped": self.skipped,
            "rejected": self.rejected,
            "errors": self.errors,
        }


class CatalogFetcher:
    """Representa el componente `CatalogFetcher`.
    """
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

    async def scrape_once(self, recover_running: bool = False) -> ScrapeCounters:
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

        run = await self.runs.acquire()
        if run is None:
            logger.info("scrape_skipped", reason="active_recent_run")
            return ScrapeCounters()
        run_id = run.id
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
        )
        logger.info(
            "scrape_started",
            run_id=str(run_id),
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
            if runtime.stopped_by_command or runtime.counters.apps_failed
            else ScrapeRunStatus.COMPLETED
        )
        await self.runs.finish(
            run_id,
            final_status,
            error_summary=worker_error.__class__.__name__ if worker_error else (
                "Stopped by admin command" if runtime.stopped_by_command else None
            ),
            **runtime.counters.__dict__,
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


class SearcherWorker:
    """Ejecuta el procesamiento en segundo plano de `Searcher`.
    """
    def __init__(self, settings: Settings) -> None:
        """Inicializa una instancia de `SearcherWorker`.

        Args:
            settings (Settings): Configuración del servicio.
        """
        self.settings = settings
        """Estado de instancia asociado a `settings`.
        """
        self.worker_id = f"searcher:{worker_id()}"
        """Estado de instancia asociado a `worker_id`.
        """

    async def run(self, runtime: PipelineRuntime) -> None:
        """Ejecuta `run` dentro de `SearcherWorker`.

        Args:
            runtime (PipelineRuntime): Valor de `runtime` utilizado por la operación.
        """
        try:
            async with WinstallClient(self.settings) as winstall:
                async for lightweight_app in winstall.iter_apps():
                    if not await runtime.before_next_item():
                        break
                    if (
                        self.settings.scrape_max_apps > 0
                        and runtime.counters.apps_discovered >= self.settings.scrape_max_apps
                    ):
                        break
                    if not await self._wait_for_backpressure(runtime):
                        break
                    await set_current(
                        self.settings,
                        runtime.run_id,
                        lightweight_app.package_id,
                        getattr(lightweight_app, "name", None),
                        "searcher_fetching_winstall_app",
                    )
                    try:
                        app = await winstall.get_app(lightweight_app.package_id)
                    except Exception:
                        app = lightweight_app
                    try:
                        page_links = await winstall.get_page_links(app.package_id)
                    except Exception:
                        page_links = None

                    official_url = (
                        page_links.official_url
                        if page_links and page_links.official_url
                        else app.homepage
                    )
                    payload: dict[str, Any] = {
                        "package_id": app.package_id,
                        "winstall_url": f"{self.settings.winstall_base_url}/apps/{app.package_id}",
                        "official_url": official_url,
                        "source_code_url": page_links.source_code_url if page_links else None,
                        "winstall_download_urls": [
                            download.url
                            for download in (page_links.downloads if page_links else [])
                        ],
                        "winstall_downloads": [
                            {
                                "url": download.url,
                                "label": download.label,
                                "context": download.context,
                            }
                            for download in (page_links.downloads if page_links else [])
                        ],
                        "app": app.raw,
                    }
                    async with async_session_local()() as session:
                        pipeline = PipelineRepository(session)
                        await pipeline.enqueue(
                            QUEUE_SEARCHER_FILTER,
                            app.package_id,
                            app.name,
                            payload,
                            runtime.run_id,
                        )
                        depth = await pipeline.queue_depth(QUEUE_SEARCHER_FILTER)
                        await pipeline.save_snapshot(
                            run_id=runtime.run_id,
                            worker_id=self.worker_id,
                            stage="searcher",
                            package_id=app.package_id,
                            app_name=app.name,
                            url=payload["winstall_url"],
                            html=None,
                        )
                        await session.commit()
                    logger.info(
                        "searcher_item_enqueued",
                        queue=QUEUE_SEARCHER_FILTER,
                        winstall_id=app.package_id,
                        depth=depth,
                    )
                    await runtime.increment("apps_discovered")
        finally:
            runtime.searcher_done.set()

    async def _wait_for_backpressure(self, runtime: PipelineRuntime) -> bool:
        """Ejecuta el paso interno `_wait_for_backpressure`.

        Args:
            runtime (PipelineRuntime): Valor de `runtime` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        limit = self.settings.scrape_searcher_backpressure_limit
        if limit <= 0:
            return True
        while not runtime.stop_event.is_set():
            if not await runtime.before_next_item():
                return False
            async with async_session_local()() as session:
                depth = await PipelineRepository(session).queue_depth(QUEUE_SEARCHER_FILTER)
            if depth < limit:
                return True
            await set_current(
                self.settings,
                runtime.run_id,
                None,
                None,
                "searcher_waiting_for_filter_backpressure",
            )
            logger.info(
                "searcher_backpressure_wait",
                queue=QUEUE_SEARCHER_FILTER,
                depth=depth,
                limit=limit,
            )
            await asyncio.sleep(self.settings.scrape_searcher_backpressure_sleep_seconds)
        return False


class FilterWorker:
    """Ejecuta el procesamiento en segundo plano de `Filter`.
    """
    def __init__(self, settings: Settings) -> None:
        """Inicializa una instancia de `FilterWorker`.

        Args:
            settings (Settings): Configuración del servicio.
        """
        self.settings = settings
        """Estado de instancia asociado a `settings`.
        """
        self.worker_id = f"filter:{worker_id()}"
        """Estado de instancia asociado a `worker_id`.
        """
        self.validator = DownloadValidator(settings)
        """Estado de instancia asociado a `validator`.
        """
        self.github = GitHubReleaseResolver(settings)
        """Estado de instancia asociado a `github`.
        """

    async def run(self, runtime: PipelineRuntime) -> None:
        """Ejecuta `run` dentro de `FilterWorker`.

        Args:
            runtime (PipelineRuntime): Valor de `runtime` utilizado por la operación.
        """
        while not runtime.stop_event.is_set():
            if not await runtime.before_next_item():
                break
            item = await claim_item(self.settings, QUEUE_SEARCHER_FILTER, self.worker_id)
            if item is None:
                if runtime.searcher_done.is_set():
                    break
                await asyncio.sleep(1)
                continue
            try:
                payload = item.payload_json or {}
                package_id = payload_package_id(payload, item)
                app = parse_payload_app(payload, package_id)
                await set_current(
                    self.settings,
                    runtime.run_id,
                    app.package_id,
                    app.name,
                    "filter_validating_app",
                )
                async with async_session_local()() as session:
                    catalog = CatalogRepository(
                        session,
                        UrlProtector(self.settings.url_protection_secret),
                    )
                    if not await catalog.should_scrape_winstall_package(app.package_id):
                        await finish_item(self.settings, item, "discard", "already_exists")
                        await runtime.increment("apps_skipped")
                        continue

                official_url = payload.get("official_url") or app.homepage
                official_valid = await self._official_page_valid(official_url)
                fallback_valid = False
                if not official_valid:
                    fallback_valid = await self._fallback_download_valid(payload, app)
                if not official_valid and not fallback_valid:
                    await finish_item(
                        self.settings,
                        item,
                        "discard",
                        "no_valid_official_or_fallback",
                    )
                    await runtime.increment("apps_failed")
                    continue

                payload["filter"] = {
                    "official_valid": official_valid,
                    "fallback_valid": fallback_valid,
                    "use_official": official_valid,
                }
                async with async_session_local()() as session:
                    pipeline = PipelineRepository(session)
                    await pipeline.enqueue(
                        QUEUE_FILTER_SCRAPER,
                        app.package_id,
                        app.name,
                        payload,
                        runtime.run_id,
                    )
                    await pipeline.save_snapshot(
                        run_id=runtime.run_id,
                        worker_id=self.worker_id,
                        stage="filter",
                        package_id=app.package_id,
                        app_name=app.name,
                        url=official_url,
                        html=None,
                    )
                    await session.commit()
                await finish_item(self.settings, item, "complete", None)
            except Exception as exc:
                await finish_item(self.settings, item, "fail", exc.__class__.__name__)
                await runtime.increment("apps_failed")
                logger.warning(
                    "filter_app_failed",
                    winstall_id=item.package_id,
                    error=exc.__class__.__name__,
                )
        runtime.filter_done.set()

    async def _official_page_valid(self, url: str | None) -> bool:
        """Ejecuta el paso interno `_official_page_valid`.

        Args:
            url (str | None): URL del recurso que debe procesarse.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        if not url:
            return False
        parsed_domain = registered_domain(url)
        if not parsed_domain:
            return False
        try:
            host = httpx.URL(url).host
        except Exception:
            return False
        if not await domain_has_public_dns(host):
            return False
        try:
            async with httpx.AsyncClient(
                timeout=self.settings.request_timeout_seconds,
                follow_redirects=True,
                headers={"User-Agent": "BatchDownloaderScraper/0.1"},
            ) as client:
                response = await client.get(url)
        except Exception:
            return False
        if response.status_code >= 400:
            return False
        content_type = response.headers.get("content-type", "").lower()
        return not content_type or "html" in content_type

    async def _fallback_download_valid(self, payload: dict[str, Any], app: WinstallApp) -> bool:
        """Ejecuta el paso interno `_fallback_download_valid`.

        Args:
            payload (dict[str, Any]): Carga de datos recibida por la operación.
            app (WinstallApp): Aplicación sobre la que se realiza la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        candidates = fallback_candidates(payload, app)
        if await self._candidate_group_has_valid_download(app, candidates):
            return True
        refreshed = await self._collect_winstall_github_candidates(app, candidates)
        return await self._candidate_group_has_valid_download(app, refreshed)

    async def _candidate_group_has_valid_download(
        self,
        app: WinstallApp,
        candidates: list[InstallerCandidate],
    ) -> bool:
        """Ejecuta el paso interno `_candidate_group_has_valid_download`.

        Args:
            app (WinstallApp): Aplicación sobre la que se realiza la operación.
            candidates (list[InstallerCandidate]): Valor de `candidates` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        scored = await run_cpu_bound(
            prepare_scored_candidates,
            candidates,
            app.name,
            app.package_id,
            app.publisher,
            app.latest_version,
        )
        for candidate in scored[:48]:
            if candidate.score <= 0:
                continue
            try:
                result = await self.validator.validate(candidate)
            except Exception:
                continue
            if result.ok:
                return True
        return False

    async def _collect_winstall_github_candidates(
        self,
        app: WinstallApp,
        candidates: list[InstallerCandidate],
    ) -> list[InstallerCandidate]:
        """Ejecuta el paso interno `_collect_winstall_github_candidates`.

        Args:
            app (WinstallApp): Aplicación sobre la que se realiza la operación.
            candidates (list[InstallerCandidate]): Valor de `candidates` utilizado por la operación.

        Returns:
            list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
        """
        refreshed: list[InstallerCandidate] = []
        seen_repositories: set[tuple[str, str]] = set()
        for candidate in candidates:
            repo = parse_github_repo(candidate.url)
            if not repo:
                continue
            repo_key = (repo.owner.lower(), repo.name.lower())
            if repo_key in seen_repositories:
                continue
            seen_repositories.add(repo_key)
            try:
                async with asyncio.timeout(github_collection_timeout_seconds(self.settings)):
                    release_candidates = await self.github.collect(
                        candidate.url,
                        app.latest_version,
                    )
            except Exception:
                continue
            for release_candidate in release_candidates:
                refreshed.append(
                    InstallerCandidate(
                        url=release_candidate.url,
                        source=f"winstall_{release_candidate.source}",
                        label=release_candidate.label or candidate.label,
                        context=release_candidate.context or candidate.context,
                        asset_kind=release_candidate.asset_kind or candidate.asset_kind,
                        referer=candidate.referer,
                    )
                )
        refreshed.extend(await self._collect_winstall_parent_index_candidates(candidates))
        return dedupe_candidates(refreshed)

    async def _collect_winstall_parent_index_candidates(
        self,
        candidates: list[InstallerCandidate],
    ) -> list[InstallerCandidate]:
        """Ejecuta el paso interno `_collect_winstall_parent_index_candidates`.

        Args:
            candidates (list[InstallerCandidate]): Valor de `candidates` utilizado por la operación.

        Returns:
            list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
        """
        parent_pages: dict[str, InstallerCandidate] = {}
        for candidate in dedupe_candidates(candidates):
            parent_url = winstall_parent_index_url(candidate.url)
            if parent_url:
                parent_pages.setdefault(parent_url, candidate)
            if len(parent_pages) >= 6:
                break
        if not parent_pages:
            return []

        async def fetch_parent(
            client: httpx.AsyncClient,
            parent_url: str,
        ) -> list[InstallerCandidate]:
            """Recupera la operación `parent`.

            Args:
                client (httpx.AsyncClient): Cliente utilizado para ejecutar el escenario.
                parent_url (str): Dirección de `parent` que debe procesarse.

            Returns:
                list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
            """
            parsed = urlparse(parent_url)
            if not await domain_has_public_dns(parsed.hostname):
                return []
            try:
                async with client.stream("GET", parent_url) as response:
                    if not response.is_success:
                        return []
                    if "html" not in response.headers.get("content-type", "").lower():
                        return []
                    content = bytearray()
                    async for chunk in response.aiter_bytes():
                        remaining = 1_000_000 - len(content)
                        if remaining <= 0:
                            break
                        content.extend(chunk[:remaining])
                    html = bytes(content).decode("utf-8", errors="ignore")
                    base_url = str(response.url)
            except Exception:
                return []

            parent_domain = registered_domain(base_url)
            refreshed: list[InstallerCandidate] = []
            for item in extract_candidates(html, base_url):
                if not item.extension or registered_domain(item.url) != parent_domain:
                    continue
                refreshed.append(
                    InstallerCandidate(
                        url=item.url,
                        source="winstall_parent_index",
                        label=item.label,
                        context=item.context,
                        asset_kind="winstall_download",
                        referer=base_url,
                    )
                )
                if len(refreshed) >= 500:
                    break
            return refreshed

        try:
            async with httpx.AsyncClient(
                timeout=self.settings.request_timeout_seconds,
                follow_redirects=False,
                headers={"User-Agent": "Mozilla/5.0 BatchDownloaderScraper/0.1"},
            ) as client:
                batches = await asyncio.gather(
                    *(fetch_parent(client, parent_url) for parent_url in parent_pages),
                    return_exceptions=True,
                )
        except Exception:
            return []

        refreshed: list[InstallerCandidate] = []
        for batch in batches:
            if isinstance(batch, list):
                refreshed.extend(batch)
        return dedupe_candidates(refreshed)


class PlatformScraperWorker:
    """Ejecuta el procesamiento en segundo plano de `PlatformScraper`.
    """
    def __init__(
        self,
        settings: Settings,
        candidate_resolvers: CandidateResolverStrategyRegistry | None = None,
    ) -> None:
        """Inicializa una instancia de `PlatformScraperWorker`.

        Args:
            settings (Settings): Configuración del servicio.
            candidate_resolvers (CandidateResolverStrategyRegistry | None): Valor de
                `candidate_resolvers`
                utilizado por la
                operación.
        """
        self.settings = settings
        """Estado de instancia asociado a `settings`.
        """
        self.worker_id = f"scraper:{worker_id()}"
        """Estado de instancia asociado a `worker_id`.
        """
        self.url_protector = UrlProtector(settings.url_protection_secret)
        """Estado de instancia asociado a `url_protector`.
        """
        self.validator = DownloadValidator(settings)
        """Estado de instancia asociado a `validator`.
        """
        self.playwright = PlaywrightCandidateCollector(settings)
        """Estado de instancia asociado a `playwright`.
        """
        self.github = GitHubReleaseResolver(settings)
        """Estado de instancia asociado a `github`.
        """
        self.icon_resolver = IconResolver(settings)
        """Estado de instancia asociado a `icon_resolver`.
        """
        self.candidate_resolvers = candidate_resolvers or CandidateResolverStrategyRegistry(
            (
                CandidateResolverStrategy(
                    name="github_releases",
                    predicate=lambda url: parse_github_repo(url) is not None,
                    callback=self._collect_github_official_candidates,
                ),
                CandidateResolverStrategy(
                    name="html_landing_playwright",
                    predicate=lambda _url: True,
                    callback=self._collect_html_official_candidates,
                ),
            )
        )
        """Estado de instancia asociado a `candidate_resolvers`.
        """

    async def run(self, runtime: PipelineRuntime) -> None:
        """Ejecuta `run` dentro de `PlatformScraperWorker`.

        Args:
            runtime (PipelineRuntime): Valor de `runtime` utilizado por la operación.
        """
        logger.info("platform_scraper_worker_started", worker_id=self.worker_id)
        while not runtime.stop_event.is_set():
            if not await runtime.before_next_item():
                break
            try:
                item = await claim_item(self.settings, QUEUE_FILTER_SCRAPER, self.worker_id)
            except OperationalError as exc:
                logger.warning(
                    "scraper_claim_retry",
                    worker_id=self.worker_id,
                    error="OperationalError",
                    detail=exception_detail(exc),
                )
                await asyncio.sleep(0.25)
                continue
            except Exception as exc:
                logger.warning(
                    "scraper_claim_retry",
                    worker_id=self.worker_id,
                    error=exc.__class__.__name__,
                    detail=exception_detail(exc),
                )
                await asyncio.sleep(1)
                continue
            if item is None:
                if runtime.searcher_done.is_set() and runtime.filter_done.is_set():
                    break
                await asyncio.sleep(1)
                continue
            try:
                async with asyncio.timeout(self.settings.scrape_app_timeout_seconds):
                    resolved = await self._scrape_item(runtime, item)
                await finish_item(self.settings, item, "complete", None)
                if resolved:
                    await runtime.increment("apps_resolved")
                else:
                    await runtime.increment("apps_failed")
            except TimeoutError:
                await finish_item(
                    self.settings,
                    item,
                    "fail",
                    f"Timeout after {self.settings.scrape_app_timeout_seconds:.0f}s",
                )
                await runtime.increment("apps_failed")
                logger.warning(
                    "scrape_app_timeout",
                    winstall_id=item.package_id,
                    timeout_seconds=self.settings.scrape_app_timeout_seconds,
                )
            except OperationalError as exc:
                if is_transient_mysql_lock_error(exc) and item.attempts < 4:
                    await finish_item(self.settings, item, "requeue", "mysql_lock_retry")
                    logger.warning(
                        "scraper_app_requeued",
                        winstall_id=item.package_id,
                        reason="mysql_lock_retry",
                        attempts=item.attempts,
                    )
                    continue
                await finish_item(self.settings, item, "fail", "OperationalError")
                await runtime.increment("apps_failed")
                logger.warning(
                    "scraper_app_failed",
                    winstall_id=item.package_id,
                    error="OperationalError",
                    detail=exception_detail(exc),
                )
            except Exception as exc:
                await finish_item(self.settings, item, "fail", exc.__class__.__name__)
                await runtime.increment("apps_failed")
                logger.warning(
                    "scraper_app_failed",
                    winstall_id=item.package_id,
                    error=exc.__class__.__name__,
                    detail=exception_detail(exc),
                )

    async def _scrape_item(self, runtime: PipelineRuntime, item: ScraperWorkItem) -> bool:
        """Ejecuta el paso interno `_scrape_item`.

        Args:
            runtime (PipelineRuntime): Valor de `runtime` utilizado por la operación.
            item (ScraperWorkItem): Valor de `item` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        item_started_at = asyncio.get_running_loop().time()
        payload = item.payload_json or {}
        package_id = payload_package_id(payload, item)
        app = parse_payload_app(payload, package_id)
        official_url = payload.get("official_url") or app.homepage
        await set_current(
            self.settings,
            runtime.run_id,
            app.package_id,
            app.name,
            "scraper_upserting_app",
        )
        async with async_session_local()() as session:
            catalog = CatalogRepository(session, self.url_protector)
            logs = ResolverLogRepository(session)
            software_app, _created = await catalog.upsert_winstall_app_with_created(app)
            await self._enrich_github_icon(catalog, logs, software_app, app)
            await session.commit()

        direct_candidates: list[InstallerCandidate] = []
        fallback = fallback_candidates(payload, app)
        fallback_validation_task = asyncio.create_task(
            self._validate_candidate_group(
                app,
                fallback,
                ResolutionStatus.FALLBACK,
                max_candidates=48,
                max_valid=12,
            )
        )
        filter_info = payload.get("filter") or {}
        try:
            if should_collect_official_installers(
                app,
                official_url,
                use_official=bool(filter_info.get("use_official")),
                fallback=fallback,
            ):
                await set_current(
                    self.settings,
                    runtime.run_id,
                    app.package_id,
                    app.name,
                    "scraper_collecting_official_installers",
                )
                collection_budget = max(
                    8.0,
                    min(30.0, self.settings.scrape_app_timeout_seconds * 0.34),
                )
                try:
                    async with asyncio.timeout(collection_budget):
                        direct_candidates = await self._collect_official_candidates(
                            runtime,
                            app,
                            official_url
                            or app.homepage
                            or known_official_candidates(app)[0].url,
                        )
                except TimeoutError:
                    logger.warning(
                        "official_candidate_collection_timeout",
                        winstall_id=app.package_id,
                        timeout_seconds=collection_budget,
                    )

            direct_validation_task = asyncio.create_task(
                self._validate_candidate_group(
                    app,
                    direct_candidates,
                    ResolutionStatus.DIRECT,
                    max_candidates=96,
                    max_valid=12,
                )
            )
            (direct_valid, direct_diagnostics), (
                fallback_valid,
                fallback_diagnostics,
            ) = await asyncio.gather(direct_validation_task, fallback_validation_task)
        finally:
            if not fallback_validation_task.done():
                fallback_validation_task.cancel()
                await asyncio.gather(fallback_validation_task, return_exceptions=True)

        valid_installers = dedupe_valid_installers([*direct_valid, *fallback_valid])
        validation_diagnostics = {
            "direct": direct_diagnostics.as_metadata(),
            "fallback": fallback_diagnostics.as_metadata(),
        }
        if not valid_installers:
            elapsed = asyncio.get_running_loop().time() - item_started_at
            remaining = self.settings.scrape_app_timeout_seconds - elapsed - 5.0
            if remaining > 5.0:
                try:
                    async with asyncio.timeout(min(25.0, remaining)):
                        refreshed_fallback = await self._collect_winstall_github_candidates(
                            app,
                            fallback,
                        )
                        refreshed_valid, refreshed_diagnostics = (
                            await self._validate_candidate_group(
                                app,
                                refreshed_fallback,
                                ResolutionStatus.FALLBACK,
                                max_candidates=48,
                                max_valid=12,
                            )
                        )
                except TimeoutError:
                    validation_diagnostics["fallback_refresh"] = {
                        "errors": {"RefreshBudgetExceeded": 1}
                    }
                else:
                    valid_installers = dedupe_valid_installers(
                        [*valid_installers, *refreshed_valid]
                    )
                    validation_diagnostics["fallback_refresh"] = (
                        refreshed_diagnostics.as_metadata()
                    )

        await set_current(
            self.settings,
            runtime.run_id,
            app.package_id,
            app.name,
            "scraper_saving_installers",
        )
        async with async_session_local()() as session:
            catalog = CatalogRepository(session, self.url_protector)
            logs = ResolverLogRepository(session)
            pipeline = PipelineRepository(session)
            software_app = await catalog.upsert_winstall_app(app)
            if not valid_installers:
                source = await catalog.default_source_for_app(software_app.id)
                if source:
                    await catalog.mark_source_status(
                        source.id,
                        ResolutionStatus.REQUIRES_MANUAL_REVIEW
                        if official_url
                        else ResolutionStatus.MISSING,
                    )
                    await logs.add(
                        phase="resolve",
                        status="requires_manual_review",
                        download_source_id=source.id,
                        message="No safe installer candidate was found.",
                        safe_metadata={
                            "winstall_id": app.package_id,
                            "candidate_diagnostics": validation_diagnostics,
                        },
                    )
                await enqueue_so_filter_for_app(
                    pipeline,
                    runtime.run_id,
                    software_app,
                )
                await session.commit()
                return False
            await self._save_valid_installers(
                catalog,
                logs,
                software_app.id,
                app,
                official_url,
                valid_installers,
            )
            await enqueue_so_filter_for_app(
                pipeline,
                runtime.run_id,
                software_app,
            )
            await session.commit()
            return True

    async def _enrich_github_icon(
        self,
        catalog: CatalogRepository,
        logs: ResolverLogRepository,
        software_app: SoftwareApp,
        app: WinstallApp,
    ) -> None:
        """Ejecuta el paso interno `_enrich_github_icon`.

        Args:
            catalog (CatalogRepository): Valor de `catalog` utilizado por la operación.
            logs (ResolverLogRepository): Valor de `logs` utilizado por la operación.
            software_app (SoftwareApp): Valor de `software_app` utilizado por la operación.
            app (WinstallApp): Aplicación sobre la que se realiza la operación.
        """
        from app.repositories.catalog import is_github_homepage, is_replaceable_github_icon

        if not (
            is_github_homepage(software_app.official_url)
            and is_replaceable_github_icon(software_app.icon_url)
        ):
            return
        try:
            result = await self.icon_resolver.resolve(
                replace(
                    app,
                    homepage=software_app.official_url,
                    icon_url=software_app.icon_url,
                )
            )
        except Exception as exc:
            logger.warning(
                "scraper_inline_icon_failed",
                winstall_id=software_app.winstall_id,
                error=exc.__class__.__name__,
            )
            return
        if result is None:
            await logs.add(
                phase="icon",
                status="discarded",
                message="no_safe_github_image",
                safe_metadata={"winstall_id": software_app.winstall_id},
            )
            return
        updated = await catalog.update_icon_url(software_app.id, result.url)
        await logs.add(
            phase="icon",
            status="resolved" if updated else "skipped",
            safe_metadata={
                "winstall_id": software_app.winstall_id,
                "source": result.source,
                "domain": registered_domain(result.url),
            },
        )

    async def _collect_official_candidates(
        self,
        runtime: PipelineRuntime,
        app: WinstallApp,
        official_url: str,
    ) -> list[InstallerCandidate]:
        """Ejecuta el paso interno `_collect_official_candidates`.

        Args:
            runtime (PipelineRuntime): Valor de `runtime` utilizado por la operación.
            app (WinstallApp): Aplicación sobre la que se realiza la operación.
            official_url (str): Dirección de `official` que debe procesarse.

        Returns:
            list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
        """
        known_candidates = known_official_candidates(app)
        if use_only_known_official_candidates(app, known_candidates):
            return known_candidates
        strategy = self.candidate_resolvers.find(official_url)
        if strategy is None:
            return known_candidates
        collected = await strategy.collect(runtime, app, official_url)
        return dedupe_candidates([*known_candidates, *collected])

    async def _collect_github_official_candidates(
        self,
        _runtime: ScrapeRuntime,
        app: WinstallApp,
        official_url: str,
    ) -> list[InstallerCandidate]:
        """Ejecuta el paso interno `_collect_github_official_candidates`.

        Args:
            _runtime (ScrapeRuntime): Valor de `_runtime` utilizado por la operación.
            app (WinstallApp): Aplicación sobre la que se realiza la operación.
            official_url (str): Dirección de `official` que debe procesarse.

        Returns:
            list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
        """
        try:
            async with asyncio.timeout(github_collection_timeout_seconds(self.settings)):
                return await self.github.collect(official_url, app.latest_version)
        except Exception:
            return []

    async def _collect_html_official_candidates(
        self,
        runtime: ScrapeRuntime,
        app: WinstallApp,
        official_url: str,
    ) -> list[InstallerCandidate]:
        """Ejecuta el paso interno `_collect_html_official_candidates`.

        Args:
            runtime (ScrapeRuntime): Valor de `runtime` utilizado por la operación.
            app (WinstallApp): Aplicación sobre la que se realiza la operación.
            official_url (str): Dirección de `official` que debe procesarse.

        Returns:
            list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
        """
        html = ""
        try:
            async with httpx.AsyncClient(
                timeout=self.settings.request_timeout_seconds,
                follow_redirects=True,
                headers={"User-Agent": "BatchDownloaderScraper/0.1"},
            ) as client:
                response = await client.get(official_url)
                response.raise_for_status()
                if "html" in response.headers.get("content-type", "").lower():
                    html = response.text
        except Exception:
            html = ""

        async with async_session_local()() as session:
            pipeline = PipelineRepository(session)
            await pipeline.save_snapshot(
                run_id=runtime.run_id,
                worker_id=self.worker_id,
                stage="scraper",
                package_id=app.package_id,
                app_name=app.name,
                url=official_url,
                html=html,
            )
            await session.commit()

        candidates = (
            await run_cpu_bound(extract_candidates, html, official_url)
            if html
            else []
        )
        candidates.extend(await self._collect_download_landing_candidates(official_url, candidates))
        if not any(is_actionable_installer_candidate(candidate) for candidate in candidates):
            try:
                candidates.extend(await self.playwright.collect(official_url))
            except Exception:
                pass
        return dedupe_candidates(candidates)

    async def _collect_download_landing_candidates(
        self,
        official_url: str,
        candidates: list[InstallerCandidate],
    ) -> list[InstallerCandidate]:
        """Ejecuta el paso interno `_collect_download_landing_candidates`.

        Args:
            official_url (str): Dirección de `official` que debe procesarse.
            candidates (list[InstallerCandidate]): Valor de `candidates` utilizado por la operación.

        Returns:
            list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
        """
        official_domain = registered_domain(official_url)
        landing_pages = [
            candidate
            for candidate in candidates
            if is_download_landing_page(candidate, official_url, official_domain)
        ][:6]
        if not landing_pages:
            return []

        async def fetch_landing(
            client: httpx.AsyncClient,
            landing: InstallerCandidate,
        ) -> list[InstallerCandidate]:
            """Recupera la operación `landing`.

            Args:
                client (httpx.AsyncClient): Cliente utilizado para ejecutar el escenario.
                landing (InstallerCandidate): Valor de `landing` utilizado por la operación.

            Returns:
                list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
            """
            try:
                response = await client.get(landing.url)
            except Exception:
                return []
            if not response.is_success:
                return []
            if "html" not in response.headers.get("content-type", "").lower():
                return []
            base_url = str(response.url)
            return [
                InstallerCandidate(
                    url=candidate.url,
                    source="official_download_page",
                    label=candidate.label,
                    context=candidate.context,
                    referer=base_url,
                )
                for candidate in extract_candidates(response.text, base_url)
            ]

        nested: list[InstallerCandidate] = []
        try:
            async with httpx.AsyncClient(
                timeout=self.settings.request_timeout_seconds,
                follow_redirects=True,
                headers={"User-Agent": "Mozilla/5.0 BatchDownloaderScraper/0.1"},
            ) as client:
                tasks = [
                    asyncio.create_task(fetch_landing(client, landing))
                    for landing in landing_pages
                ]
                landing_budget = min(15.0, self.settings.request_timeout_seconds + 2.0)
                done, pending = await asyncio.wait(tasks, timeout=landing_budget)
                for task in pending:
                    task.cancel()
                if pending:
                    await asyncio.gather(*pending, return_exceptions=True)
                for task in done:
                    nested.extend(task.result())
        except Exception:
            return []
        return dedupe_candidates(nested)

    async def _collect_winstall_github_candidates(
        self,
        app: WinstallApp,
        candidates: list[InstallerCandidate],
    ) -> list[InstallerCandidate]:
        """Ejecuta el paso interno `_collect_winstall_github_candidates`.

        Args:
            app (WinstallApp): Aplicación sobre la que se realiza la operación.
            candidates (list[InstallerCandidate]): Valor de `candidates` utilizado por la operación.

        Returns:
            list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
        """
        refreshed: list[InstallerCandidate] = []
        seen_repositories: set[tuple[str, str]] = set()
        for candidate in candidates:
            repo = parse_github_repo(candidate.url)
            if not repo:
                continue
            repo_key = (repo.owner.lower(), repo.name.lower())
            if repo_key in seen_repositories:
                continue
            seen_repositories.add(repo_key)
            try:
                async with asyncio.timeout(github_collection_timeout_seconds(self.settings)):
                    release_candidates = await self.github.collect(
                        candidate.url,
                        app.latest_version,
                    )
            except Exception:
                continue
            for release_candidate in release_candidates:
                refreshed.append(
                    InstallerCandidate(
                        url=release_candidate.url,
                        source=f"winstall_{release_candidate.source}",
                        label=release_candidate.label or candidate.label,
                        context=release_candidate.context or candidate.context,
                        asset_kind=release_candidate.asset_kind or candidate.asset_kind,
                        referer=candidate.referer,
                    )
                )
        refreshed.extend(await self._collect_winstall_parent_index_candidates(candidates))
        return dedupe_candidates(refreshed)

    async def _collect_winstall_parent_index_candidates(
        self,
        candidates: list[InstallerCandidate],
    ) -> list[InstallerCandidate]:
        """Ejecuta el paso interno `_collect_winstall_parent_index_candidates`.

        Args:
            candidates (list[InstallerCandidate]): Valor de `candidates` utilizado por la operación.

        Returns:
            list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
        """
        return await FilterWorker._collect_winstall_parent_index_candidates(
            self,  # type: ignore[arg-type]
            candidates,
        )

    async def _validate_installers(
        self,
        *,
        app: WinstallApp,
        official_url: str | None,
        direct_candidates: list[InstallerCandidate],
        fallback_candidates: list[InstallerCandidate],
    ) -> tuple[list[ValidInstaller], dict[str, dict[str, Any]]]:
        # Una página oficial lenta no debe privar a un fallback válido de Winstall.
        # Ambos grupos son rutas de confianza independientes y se validan en paralelo.
        """Ejecuta el paso interno `_validate_installers`.

        Args:
            app (WinstallApp): Aplicación sobre la que se realiza la operación.
            official_url (str | None): Dirección de `official` que debe procesarse.
            direct_candidates (list[InstallerCandidate]): Valor de `direct_candidates` utilizado por
                la operación.
            fallback_candidates (list[InstallerCandidate]): Valor de `fallback_candidates` utilizado
                por la operación.

        Returns:
            tuple[list[ValidInstaller], dict[str, dict[str, Any]]]: Colección de elementos obtenidos
                por la operación.
        """
        (direct, direct_diagnostics), (fallback, fallback_diagnostics) = await asyncio.gather(
            self._validate_candidate_group(
                app,
                direct_candidates,
                ResolutionStatus.DIRECT,
                max_candidates=96,
                max_valid=12,
            ),
            self._validate_candidate_group(
                app,
                fallback_candidates,
                ResolutionStatus.FALLBACK,
                max_candidates=48,
                max_valid=12,
            ),
        )
        return dedupe_valid_installers([*direct, *fallback]), {
            "direct": direct_diagnostics.as_metadata(),
            "fallback": fallback_diagnostics.as_metadata(),
        }

    async def _validate_candidate_group(
        self,
        app: WinstallApp,
        candidates: list[InstallerCandidate],
        status: ResolutionStatus,
        max_candidates: int,
        max_valid: int,
    ) -> tuple[list[ValidInstaller], CandidateValidationDiagnostics]:
        """Ejecuta el paso interno `_validate_candidate_group`.

        Args:
            app (WinstallApp): Aplicación sobre la que se realiza la operación.
            candidates (list[InstallerCandidate]): Valor de `candidates` utilizado por la operación.
            status (ResolutionStatus): Valor de `status` utilizado por la operación.
            max_candidates (int): Valor de `max_candidates` utilizado por la operación.
            max_valid (int): Valor de `max_valid` utilizado por la operación.

        Returns:
            tuple[list[ValidInstaller], CandidateValidationDiagnostics]: Colección de elementos
                obtenidos por la operación.
        """
        diagnostics = CandidateValidationDiagnostics()
        scored = []
        expanded_candidates: list[InstallerCandidate] = []
        for candidate in dedupe_candidates(candidates):
            try:
                expanded_candidates.extend(candidate_variants(candidate))
            except (TypeError, ValueError) as exc:
                diagnostics.error(exc)
        for candidate in dedupe_candidates(expanded_candidates):
            diagnostics.discovered += 1
            try:
                scored_candidate = score_candidate(
                    candidate,
                    app_name=app.name,
                    package_id=app.package_id,
                    publisher=app.publisher,
                    version=app.latest_version,
                )
                operating_system = infer_operating_system(candidate)
            except (TypeError, ValueError) as exc:
                diagnostics.error(exc)
                continue
            if (
                not operating_system
                and not is_windows_winstall_archive(scored_candidate)
                and not is_download_candidate(scored_candidate)
            ):
                diagnostics.skip("no_platform_or_download_intent")
                continue
            diagnostics.eligible += 1
            scored.append(scored_candidate)
        scored.sort(key=lambda candidate: candidate.score, reverse=True)

        valid: list[ValidInstaller] = []
        candidates_to_validate = []
        for candidate in scored[:max_candidates]:
            if candidate.score <= 0:
                diagnostics.skip("non_positive_score")
                continue
            candidates_to_validate.append(candidate)

        loop = asyncio.get_running_loop()
        budget_seconds = max(
            5.0,
            min(40.0, self.settings.scrape_app_timeout_seconds * 0.45),
        )
        deadline = loop.time() + budget_seconds
        batch_size = 4

        async def validate_one(candidate: InstallerCandidate):
            """Valida la operación `one`.

            Args:
                candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.
            """
            timeout_seconds = min(
                max(5.0, self.settings.request_timeout_seconds + 2.0),
                budget_seconds,
            )
            try:
                async with asyncio.timeout(timeout_seconds):
                    return candidate, await self.validator.validate(candidate), None
            except Exception as exc:
                return candidate, None, exc

        processed = 0
        for offset in range(0, len(candidates_to_validate), batch_size):
            remaining = deadline - loop.time()
            if remaining <= 0:
                break
            batch = candidates_to_validate[offset : offset + batch_size]
            diagnostics.attempted += len(batch)
            tasks = [asyncio.create_task(validate_one(candidate)) for candidate in batch]
            done, pending = await asyncio.wait(tasks, timeout=remaining)
            for task in pending:
                task.cancel()
            if pending:
                await asyncio.gather(*pending, return_exceptions=True)
                diagnostics.errors["ValidationBudgetExceeded"] = (
                    diagnostics.errors.get("ValidationBudgetExceeded", 0) + len(pending)
                )
            processed = offset + len(batch)

            for task in done:
                candidate, result, error = task.result()
                if error is not None:
                    diagnostics.error(error)
                    continue
                if result is None or not result.ok:
                    diagnostics.reject(result.reason if result else "unknown")
                    continue
                operating_system = infer_validated_operating_system(candidate, result)
                if not operating_system:
                    diagnostics.reject("operating_system_unresolved")
                    continue
                valid.append(
                    ValidInstaller(
                        candidate=candidate,
                        result=result,
                        status=status,
                        operating_system=operating_system,
                        architecture=infer_architecture(candidate),
                        version=validated_installer_version(candidate, result)
                        or app.latest_version,
                    )
                )
                diagnostics.valid += 1

            if pending or len(valid) >= max_valid:
                break

        unprocessed = max(0, len(candidates_to_validate) - processed)
        if unprocessed:
            diagnostics.skipped["validation_budget_exhausted"] = (
                diagnostics.skipped.get("validation_budget_exhausted", 0) + unprocessed
            )
        return valid[:max_valid], diagnostics

    async def _save_valid_installers(
        self,
        catalog: CatalogRepository,
        logs: ResolverLogRepository,
        software_app_id: uuid.UUID,
        app: WinstallApp,
        official_url: str | None,
        installers: list[ValidInstaller],
    ) -> None:
        """Ejecuta el paso interno `_save_valid_installers`.

        Args:
            catalog (CatalogRepository): Valor de `catalog` utilizado por la operación.
            logs (ResolverLogRepository): Valor de `logs` utilizado por la operación.
            software_app_id (uuid.UUID): Identificador de `software_app` utilizado por la operación.
            app (WinstallApp): Aplicación sobre la que se realiza la operación.
            official_url (str | None): Dirección de `official` que debe procesarse.
            installers (list[ValidInstaller]): Valor de `installers` utilizado por la operación.
        """
        ranked = rank_installers(installers)
        expired_sources: set[uuid.UUID] = set()
        for installer, release_rank, is_latest in ranked:
            source = await catalog.ensure_download_source(
                software_app_id=software_app_id,
                app=app,
                operating_system=installer.operating_system,
                architecture=installer.architecture,
                initial_url=official_url or app.homepage,
            )
            if source.id not in expired_sources:
                await catalog.expire_valid_resolved_sources(source.id)
                expired_sources.add(source.id)
            await catalog.save_resolved_source(
                ResolvedSourceCreate(
                    source_id=source.id,
                    url=installer.result.final_url or installer.candidate.url,
                    final_domain=installer.result.final_domain
                    or registered_domain(installer.result.final_url or installer.candidate.url)
                    or "",
                    filename=installer.result.filename,
                    extension=installer.result.extension,
                    content_type=installer.result.content_type,
                    size_bytes=installer.result.size_bytes,
                    version=installer.version,
                    score=installer.candidate.score,
                    status=installer.status,
                    validation_status=ValidationStatus.VALID,
                    release_rank=release_rank,
                    is_latest=is_latest,
                    version_status="latest" if is_latest else "previous",
                    metadata=resolved_metadata(installer, is_latest),
                )
            )
            await logs.add(
                phase="resolve",
                status=installer.status.value,
                download_source_id=source.id,
                safe_metadata={
                    "winstall_id": app.package_id,
                    "os": installer.operating_system,
                    "architecture": installer.architecture,
                    "version": installer.version,
                    "is_latest": is_latest,
                },
            )
        await catalog.refresh_source_statuses(expired_sources)

async def enqueue_descriptor_for_app(
    catalog: CatalogRepository,
    pipeline: PipelineRepository,
    run_id: uuid.UUID | None,
    software_app: Any,
    *,
    force: bool,
    priority: int = 0,
) -> ScraperWorkItem | None:
    """Encola la operación `descriptor_for_app`.

    Args:
        catalog (CatalogRepository): Valor de `catalog` utilizado por la operación.
        pipeline (PipelineRepository): Valor de `pipeline` utilizado por la operación.
        run_id (uuid.UUID | None): Identificador de `run` utilizado por la operación.
        software_app (Any): Valor de `software_app` utilizado por la operación.
        force (bool): Valor de `force` utilizado por la operación.
        priority (int): Valor de `priority` utilizado por la operación.

    Returns:
        ScraperWorkItem | None: Resultado producido por la operación.
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
    """Encola la operación `so_filter_for_app`.

    Args:
        pipeline (PipelineRepository): Valor de `pipeline` utilizado por la operación.
        run_id (uuid.UUID | None): Identificador de `run` utilizado por la operación.
        software_app (Any): Valor de `software_app` utilizado por la operación.
        force (bool): Valor de `force` utilizado por la operación.
        priority (int): Valor de `priority` utilizado por la operación.

    Returns:
        ScraperWorkItem: Resultado producido por la operación.
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
    """Ejecuta el procesamiento en segundo plano de `Descriptor`.
    """
    def __init__(self, settings: Settings) -> None:
        """Inicializa una instancia de `DescriptorWorker`.

        Args:
            settings (Settings): Configuración del servicio.
        """
        self.settings = settings
        """Estado de instancia asociado a `settings`.
        """
        self.worker_id = f"descriptor:{worker_id()}"
        """Estado de instancia asociado a `worker_id`.
        """
        self.llm = AppDescriptionLLMClient(settings)
        """Estado de instancia asociado a `llm`.
        """

    async def run(self, runtime: PipelineRuntime) -> None:
        """Ejecuta `run` dentro de `DescriptorWorker`.

        Args:
            runtime (PipelineRuntime): Valor de `runtime` utilizado por la operación.
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
        """Procesa la operación `one`.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        if not self.llm.has_provider():
            logger.warning("descriptor_process_one_skipped", reason="llm_provider_not_configured")
            return False
        item = await claim_item(self.settings, QUEUE_SO_FILTER_DESCRIPTOR, self.worker_id)
        if item is None:
            return False
        return await self._process_claimed_item(None, item)

    async def _consume(self, runtime: PipelineRuntime) -> None:
        """Ejecuta el paso interno `_consume`.

        Args:
            runtime (PipelineRuntime): Valor de `runtime` utilizado por la operación.
        """
        while not runtime.stop_event.is_set():
            if not await runtime.before_next_item():
                break
            if not await runtime.reserve_descriptor_attempt():
                logger.info("descriptor_budget_exhausted")
                break
            item = await claim_item(self.settings, QUEUE_SO_FILTER_DESCRIPTOR, self.worker_id)
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
        """Ejecuta el paso interno `_process_claimed_item`.

        Args:
            runtime (PipelineRuntime | None): Valor de `runtime` utilizado por la operación.
            item (ScraperWorkItem): Valor de `item` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
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
                pipeline = PipelineRepository(session)
                await pipeline.save_snapshot(
                    run_id=runtime.run_id if runtime else item.run_id,
                    worker_id=self.worker_id,
                    stage="descriptor",
                    package_id=payload.get("package_id") or item.package_id,
                    app_name=item.app_name,
                    url=None,
                    html=None,
                )
                result = await AppDescriptionEnricher(
                    self.settings,
                    catalog,
                    logs,
                    llm=self.llm,
                ).enrich_app(software_app_id, force=bool(payload.get("force")))
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
    """Ejecuta el procesamiento en segundo plano de `SOFilter`.
    """

    def __init__(self, settings: Settings) -> None:
        """Inicializa una instancia de `SOFilterWorker`.

        Args:
            settings (Settings): Configuración del servicio.
        """
        self.settings = settings
        """Estado de instancia asociado a `settings`.
        """
        self.worker_id = f"so-filter:{worker_id()}"
        """Estado de instancia asociado a `worker_id`.
        """

    async def run(self, runtime: PipelineRuntime) -> None:
        """Ejecuta `run` dentro de `SOFilterWorker`.

        Args:
            runtime (PipelineRuntime): Valor de `runtime` utilizado por la operación.
        """
        while not runtime.stop_event.is_set():
            if not await runtime.before_next_item():
                break
            processed = await self.process_one(runtime)
            if not processed:
                if runtime.scraper_done.is_set():
                    break
                await asyncio.sleep(1)

    async def process_one(self, runtime: PipelineRuntime | None = None) -> bool:
        """Procesa la operación `one`.

        Args:
            runtime (PipelineRuntime | None): Valor de `runtime` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        item = await claim_item(self.settings, QUEUE_SCRAPER_SO_FILTER, self.worker_id)
        if item is None:
            return False
        return await self._process_claimed_item(item, runtime)

    async def _process_claimed_item(
        self,
        item: ScraperWorkItem,
        runtime: PipelineRuntime | None,
    ) -> bool:
        """Ejecuta el paso interno `_process_claimed_item`.

        Args:
            item (ScraperWorkItem): Valor de `item` utilizado por la operación.
            runtime (PipelineRuntime | None): Valor de `runtime` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        software_app_id = (item.payload_json or {}).get("software_app_id")
        if not software_app_id:
            await finish_item(self.settings, item, "discard", "missing_software_app_id")
            return True
        try:
            app_id = uuid.UUID(str(software_app_id))
        except (TypeError, ValueError):
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
                systems = await catalog.refresh_operating_systems(app_id)
                await pipeline.save_snapshot(
                    run_id=item.run_id,
                    worker_id=self.worker_id,
                    stage="so_filter",
                    package_id=software_app.winstall_id,
                    app_name=software_app.name,
                    url=None,
                    html=None,
                )
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
            action = (
                "requeue" if item.attempts < self.settings.so_filter_max_attempts else "fail"
            )
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


async def claim_item(
    settings: Settings,
    queue: str,
    worker_id_value: str,
) -> ScraperWorkItem | None:
    """Reserva la operación `item`.

    Args:
        settings (Settings): Configuración del servicio.
        queue (str): Valor de `queue` utilizado por la operación.
        worker_id_value (str): Valor de `worker_id_value` utilizado por la operación.

    Returns:
        ScraperWorkItem | None: Resultado producido por la operación.
    """
    async with async_session_local()() as session:
        pipeline = PipelineRepository(session)
        item = await pipeline.claim_next(
            queue,
            worker_id=worker_id_value,
            lease_seconds=max(60, int(settings.scrape_app_timeout_seconds * 2)),
        )
        depth = await pipeline.queue_depth(queue)
        await session.commit()
        if item:
            logger.info(
                "scraper_pipeline_item_claimed",
                queue=queue,
                winstall_id=item.package_id,
                worker_id=worker_id_value,
                attempts=item.attempts,
                depth=depth,
            )
        return item


async def finish_item(
    settings: Settings,
    item: ScraperWorkItem,
    action: str,
    message: str | None,
    *,
    delay_seconds: int = 2,
) -> None:
    """Ejecuta la operación `finish_item`.

    Args:
        settings (Settings): Configuración del servicio.
        item (ScraperWorkItem): Valor de `item` utilizado por la operación.
        action (str): Valor de `action` utilizado por la operación.
        message (str | None): Mensaje que debe procesarse.
        delay_seconds (int): Valor de `delay_seconds` utilizado por la operación.
    """
    async with async_session_local()() as session:
        pipeline = PipelineRepository(session)
        db_item = await session.get(ScraperWorkItem, item.id)
        if not db_item:
            return
        if action == "complete":
            await pipeline.complete(db_item)
        elif action == "discard":
            await pipeline.discard(db_item, message or "discarded")
        elif action == "requeue":
            await pipeline.requeue(
                db_item,
                message or "retry",
                delay_seconds=delay_seconds,
            )
        else:
            await pipeline.fail(db_item, message or "failed")
        depth = await pipeline.queue_depth(db_item.queue)
        await session.commit()
        logger.info(
            "scraper_pipeline_item_finished",
            queue=db_item.queue,
            winstall_id=db_item.package_id,
            action=action,
            reason=message,
            depth=depth,
        )


async def set_current(
    settings: Settings,
    run_id: uuid.UUID,
    package_id: str | None,
    app_name: str | None,
    phase: str,
) -> None:
    """Establece la operación `current`.

    Args:
        settings (Settings): Configuración del servicio.
        run_id (uuid.UUID): Identificador de `run` utilizado por la operación.
        package_id (str | None): Identificador de `package` utilizado por la operación.
        app_name (str | None): Valor de `app_name` utilizado por la operación.
        phase (str): Valor de `phase` utilizado por la operación.
    """
    async with async_session_local()() as session:
        runs = ScrapeRunRepository(session, settings)
        await runs.set_current(run_id, package_id, app_name, phase)
        await session.commit()


def parse_payload_app(payload: dict[str, Any], fallback_package_id: str) -> WinstallApp:
    """Analiza la operación `payload_app`.

    Args:
        payload (dict[str, Any]): Carga de datos recibida por la operación.
        fallback_package_id (str): Identificador de `fallback_package` utilizado por la operación.

    Returns:
        WinstallApp: Resultado producido por la operación.
    """
    raw = payload.get("app")
    if isinstance(raw, dict):
        return parse_winstall_app(raw)
    return parse_winstall_app({"_id": fallback_package_id, "name": fallback_package_id})


def payload_package_id(payload: dict[str, Any], item: ScraperWorkItem) -> str:
    """Ejecuta la operación `payload_package_id`.

    Args:
        payload (dict[str, Any]): Carga de datos recibida por la operación.
        item (ScraperWorkItem): Valor de `item` utilizado por la operación.

    Returns:
        str: Resultado producido por la operación.
    """
    value = payload.get("package_id") or item.package_id
    return str(value)


def is_stale_control_command(command: Any, run_started_at: datetime) -> bool:
    """Indica si se cumple la operación `stale_control_command`.

    Args:
        command (Any): Comando que debe procesarse.
        run_started_at (datetime): Instante asociado a `run_started`.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    return (
        command.command in {"pause", "resume", "stop", "force_stop"}
        and command.created_at < run_started_at
    )


def first_task_failure(error: BaseException) -> BaseException:
    """Ejecuta la operación `first_task_failure`.

    Args:
        error (BaseException): Error que debe registrarse o propagarse.

    Returns:
        BaseException: Resultado producido por la operación.
    """

    if isinstance(error, BaseExceptionGroup):
        for nested in error.exceptions:
            failure = first_task_failure(nested)
            if not isinstance(failure, asyncio.CancelledError):
                return failure
    return error


def fallback_candidates(payload: dict[str, Any], app: WinstallApp) -> list[InstallerCandidate]:
    """Ejecuta la operación `fallback_candidates`.

    Args:
        payload (dict[str, Any]): Carga de datos recibida por la operación.
        app (WinstallApp): Aplicación sobre la que se realiza la operación.

    Returns:
        list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
    """
    candidates: list[InstallerCandidate] = []
    winstall_referer = payload.get("winstall_url")
    for item in payload.get("winstall_downloads") or []:
        if isinstance(item, dict) and item.get("url"):
            candidates.append(
                InstallerCandidate(
                    url=str(item["url"]),
                    source="winstall_page",
                    label=item.get("label") or app.name,
                    context=item.get("context"),
                    asset_kind="winstall_download",
                    referer=winstall_referer,
                )
            )
    for url in payload.get("winstall_download_urls") or []:
        if isinstance(url, str):
            candidates.append(
                InstallerCandidate(
                    url=url,
                    source="winstall_page",
                    label=app.name,
                    asset_kind="winstall_download",
                    referer=winstall_referer,
                )
            )
    for version in app.versions:
        for url in version.installers:
            candidates.append(
                InstallerCandidate(
                    url=url,
                    source="winstall_api",
                    label=f"{app.name} {version.installer_type or ''}".strip(),
                    context=version.version,
                    asset_kind="winstall_download",
                    referer=winstall_referer,
                )
            )
    return dedupe_candidates(candidates)


def known_official_candidates(app: WinstallApp) -> list[InstallerCandidate]:
    """Ejecuta la operación `known_official_candidates`.

    Args:
        app (WinstallApp): Aplicación sobre la que se realiza la operación.

    Returns:
        list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
    """
    return known_official_candidates_for_package(
        app.package_id,
        getattr(app, "latest_version", None),
    )


def known_official_candidates_for_package(
    package_id: str,
    latest_version: str | None = None,
) -> list[InstallerCandidate]:
    """Ejecuta la operación `known_official_candidates_for_package`.

    Args:
        package_id (str): Identificador de `package` utilizado por la operación.
        latest_version (str | None): Valor de `latest_version` utilizado por la operación.

    Returns:
        list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
    """
    if package_id == "ItchIo.Itch":
        return [
            InstallerCandidate(
                url="https://itch.io/app/download?platform=windows",
                source="official_known_endpoint",
                label="itch Windows installer",
                context="Official itch app Windows download endpoint.",
                asset_kind="installer",
            ),
            InstallerCandidate(
                url="https://itch.io/app/download?platform=osx",
                source="official_known_endpoint",
                label="itch macOS installer",
                context="Official itch app macOS download endpoint.",
                asset_kind="installer",
            ),
            InstallerCandidate(
                url="https://itch.io/app/download?platform=linux",
                source="official_known_endpoint",
                label="itch Linux installer",
                context="Official itch app Linux download endpoint.",
                asset_kind="installer",
            ),
        ]
    if package_id == "EpicGames.EpicGamesLauncher":
        return [
            InstallerCandidate(
                url=(
                    "https://launcher-public-service-prod06.ol.epicgames.com/"
                    "launcher/api/installer/download/EpicGamesLauncherInstaller.exe"
                ),
                source="official_known_endpoint",
                label="Epic Games Launcher Windows installer",
                context="Official Epic Games launcher download API.",
                asset_kind="installer",
            )
        ]
    if package_id == "115.115Chrome" and latest_version:
        version = latest_version.strip().removeprefix("v")
        return [
            InstallerCandidate(
                url=f"https://down.115.com/client/win/115br_v{version}_x64.exe",
                source="official_known_endpoint",
                label="115 Browser Windows x64 installer",
                context="Official 115 Browser Windows download endpoint.",
                asset_kind="installer",
            ),
            InstallerCandidate(
                url=f"https://down.115.com/client/mac/115br_v{version}_x64.dmg",
                source="official_known_endpoint",
                label="115 Browser macOS x64 installer",
                context="Official 115 Browser macOS download endpoint.",
                asset_kind="installer",
            ),
            InstallerCandidate(
                url=f"https://down.115.com/client/mac/115br_v{version}_arm64.dmg",
                source="official_known_endpoint",
                label="115 Browser macOS ARM64 installer",
                context="Official 115 Browser macOS download endpoint.",
                asset_kind="installer",
            ),
            InstallerCandidate(
                url=f"https://down.115.com/client/115pc/lin/115br_v{version}.deb",
                source="official_known_endpoint",
                label="115 Browser Linux DEB installer",
                context="Official 115 Browser Linux download endpoint.",
                asset_kind="installer",
            ),
        ]
    if package_id == "123.123pan" and latest_version:
        version = normalized_123pan_version(latest_version)
        compact_version = "".join(character for character in version if character.isdigit())
        if compact_version:
            return [
                InstallerCandidate(
                    url=(
                        "https://app.123957.com/pc-pro/windows/"
                        f"{compact_version}/123pan_{version}.exe"
                    ),
                    source="official_known_endpoint",
                    label="123云盘 Windows installer",
                    context="Official 123云盘 Windows download endpoint.",
                    asset_kind="installer",
                )
            ]
    return []


def use_only_known_official_candidates(
    app: WinstallApp,
    known_candidates: list[InstallerCandidate],
) -> bool:
    """Ejecuta la operación `use_only_known_official_candidates`.

    Args:
        app (WinstallApp): Aplicación sobre la que se realiza la operación.
        known_candidates (list[InstallerCandidate]): Valor de `known_candidates` utilizado por la
            operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    return bool(known_candidates) and app.package_id in {
        "EpicGames.EpicGamesLauncher",
        "ItchIo.Itch",
        "115.115Chrome",
        "123.123pan",
    }


def use_winstall_fallback_only(
    app: WinstallApp,
    fallback: list[InstallerCandidate],
) -> bool:
    """Ejecuta la operación `use_winstall_fallback_only`.

    Args:
        app (WinstallApp): Aplicación sobre la que se realiza la operación.
        fallback (list[InstallerCandidate]): Valor de `fallback` utilizado por la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    return bool(fallback) and app.package_id in {
        "360.360DocProtect",
        "360.360SE",
        "360.360Zip",
        "3TSoftwareLabs.Studio3T",
        "86Box.86BoxManager",
    }


def should_collect_official_installers(
    app: WinstallApp,
    official_url: str | None,
    *,
    use_official: bool,
    fallback: list[InstallerCandidate],
) -> bool:
    """Ejecuta la operación `should_collect_official_installers`.

    Args:
        app (WinstallApp): Aplicación sobre la que se realiza la operación.
        official_url (str | None): Dirección de `official` que debe procesarse.
        use_official (bool): Valor de `use_official` utilizado por la operación.
        fallback (list[InstallerCandidate]): Valor de `fallback` utilizado por la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    if known_official_candidates(app):
        return True
    return bool(use_official and official_url and not use_winstall_fallback_only(app, fallback))


def normalized_123pan_version(value: str) -> str:
    """Ejecuta la operación `normalized_123pan_version`.

    Args:
        value (str): Valor que debe procesarse.

    Returns:
        str: Resultado producido por la operación.
    """
    parts = value.strip().removeprefix("v").split(".")
    if len(parts) >= 3 and all(part.isdigit() for part in parts[:3]):
        return ".".join(parts[:3])
    return value.strip().removeprefix("v")


def is_download_landing_page(
    candidate: InstallerCandidate,
    official_url: str,
    official_domain: str | None,
) -> bool:
    """Indica si se cumple la operación `download_landing_page`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.
        official_url (str): Dirección de `official` que debe procesarse.
        official_domain (str | None): Valor de `official_domain` utilizado por la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    if candidate.url == official_url or candidate.extension:
        return False
    parsed = urlparse(candidate.url)
    if parsed.scheme not in {"http", "https"}:
        return False
    if not official_domain or registered_domain(candidate.url) != official_domain:
        return False
    route = f"{parsed.path}?{parsed.query}".lower()
    return candidate_has_download_intent(candidate) or any(
        marker in route for marker in ("download", "installer", "setup", "desktop")
    )


def is_actionable_installer_candidate(candidate: InstallerCandidate) -> bool:
    """Indica si se cumple la operación `actionable_installer_candidate`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    parsed = urlparse(candidate.url)
    if parsed.scheme not in {"http", "https"}:
        return False
    return bool(candidate.extension) or candidate.asset_kind in {
        "installer",
        "release_zip",
        "winstall_download",
    }


def github_collection_timeout_seconds(settings: Settings) -> float:
    """Ejecuta la operación `github_collection_timeout_seconds`.

    Args:
        settings (Settings): Configuración del servicio.

    Returns:
        float: Resultado producido por la operación.
    """
    return max(5.0, min(15.0, settings.request_timeout_seconds + 2.0))


def winstall_parent_index_url(url: str) -> str | None:
    """Ejecuta la operación `winstall_parent_index_url`.

    Args:
        url (str): URL del recurso que debe procesarse.

    Returns:
        str | None: Resultado producido por la operación.
    """
    try:
        parsed = urlparse(url)
    except ValueError:
        return None
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        return None
    if parse_github_repo(url):
        return None

    segments = parsed.path.split("/")
    file_index: int | None = None
    for index in range(len(segments) - 1, -1, -1):
        if detect_extension(segments[index]):
            file_index = index
            break
    if file_index is None:
        return None
    parent_path = "/".join(segments[:file_index]) + "/"
    if parent_path == "/":
        return None
    return urlunparse(parsed._replace(path=parent_path, params="", query="", fragment=""))


def dedupe_candidates(candidates: list[InstallerCandidate]) -> list[InstallerCandidate]:
    """Ejecuta la operación `dedupe_candidates`.

    Args:
        candidates (list[InstallerCandidate]): Valor de `candidates` utilizado por la operación.

    Returns:
        list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
    """
    deduped: dict[str, InstallerCandidate] = {}
    for candidate in candidates:
        if candidate.url and candidate.url not in deduped:
            deduped[candidate.url] = candidate
    return list(deduped.values())


def prepare_scored_candidates(
    candidates: list[InstallerCandidate],
    app_name: str | None,
    package_id: str | None,
    publisher: str | None,
    version: str | None,
) -> list[InstallerCandidate]:
    """Ejecuta la operación `prepare_scored_candidates`.

    Args:
        candidates (list[InstallerCandidate]): Valor de `candidates` utilizado por la operación.
        app_name (str | None): Valor de `app_name` utilizado por la operación.
        package_id (str | None): Identificador de `package` utilizado por la operación.
        publisher (str | None): Valor de `publisher` utilizado por la operación.
        version (str | None): Valor de `version` utilizado por la operación.

    Returns:
        list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
    """
    expanded = [
        variant
        for candidate in dedupe_candidates(candidates)
        for variant in candidate_variants(candidate)
    ]
    scored = [
        score_candidate(
            candidate,
            app_name=app_name,
            package_id=package_id,
            publisher=publisher,
            version=version,
        )
        for candidate in dedupe_candidates(expanded)
        if is_download_candidate(candidate)
    ]
    return sorted(scored, key=lambda candidate: candidate.score, reverse=True)


def dedupe_valid_installers(installers: list[ValidInstaller]) -> list[ValidInstaller]:
    """Ejecuta la operación `dedupe_valid_installers`.

    Args:
        installers (list[ValidInstaller]): Valor de `installers` utilizado por la operación.

    Returns:
        list[ValidInstaller]: Colección de elementos obtenidos por la operación.
    """
    deduped: dict[tuple[str, str, str], ValidInstaller] = {}
    for installer in installers:
        url = installer.result.final_url or installer.candidate.url
        parsed = urlparse(url)
        stable_url = parsed._replace(query="", fragment="").geturl()
        key = (stable_url, installer.operating_system, installer.architecture)
        current = deduped.get(key)
        if current is None or installer.candidate.score > current.candidate.score:
            deduped[key] = installer
    return list(deduped.values())


def validated_installer_version(
    candidate: InstallerCandidate,
    result: ValidationResult,
) -> str | None:
    """Ejecuta la operación `validated_installer_version`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.
        result (ValidationResult): Resultado que debe procesarse.

    Returns:
        str | None: Resultado producido por la operación.
    """
    final_candidate = InstallerCandidate(
        url=result.final_url or candidate.url,
        source=candidate.source,
        label=result.filename or candidate.label,
        context=candidate.context,
    )
    return extract_version(final_candidate) or extract_version(candidate)


def rank_installers(installers: list[ValidInstaller]) -> list[tuple[ValidInstaller, int, bool]]:
    """Ejecuta la operación `rank_installers`.

    Args:
        installers (list[ValidInstaller]): Valor de `installers` utilizado por la operación.

    Returns:
        list[tuple[ValidInstaller, int, bool]]: Colección de elementos obtenidos por la operación.
    """
    grouped: dict[tuple[str, str], list[ValidInstaller]] = {}
    for installer in installers:
        grouped.setdefault(
            (installer.operating_system, installer.architecture),
            [],
        ).append(installer)

    ranked: list[tuple[ValidInstaller, int, bool]] = []
    for group in grouped.values():
        group.sort(key=installer_sort_key, reverse=True)
        for index, installer in enumerate(group):
            ranked.append((installer, index, index == 0))
    return ranked


def infer_validated_operating_system(
    candidate: InstallerCandidate,
    result: ValidationResult,
) -> str | None:
    """Ejecuta la operación `infer_validated_operating_system`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.
        result (ValidationResult): Resultado que debe procesarse.

    Returns:
        str | None: Resultado producido por la operación.
    """
    if result.extension != ".tar.gz":
        operating_system = operating_system_for_extension(result.extension)
        if operating_system:
            return operating_system
    if result.filename:
        filename_probe = InstallerCandidate(
            url=f"https://local.invalid/{result.filename}",
            source=candidate.source,
            label=candidate.label,
            context=candidate.context,
        )
        operating_system = infer_operating_system(filename_probe)
        if operating_system:
            return operating_system
    if result.final_url:
        final_probe = InstallerCandidate(
            url=result.final_url,
            source=candidate.source,
            label=candidate.label,
            context=candidate.context,
        )
        operating_system = infer_operating_system(final_probe)
        if operating_system:
            return operating_system
    operating_system = infer_operating_system(candidate)
    if operating_system:
        return operating_system
    if is_windows_winstall_archive(candidate, result.extension):
        return "windows"
    return None


def is_windows_winstall_archive(
    candidate: InstallerCandidate,
    extension: str | None = None,
) -> bool:
    """Indica si se cumple la operación `windows_winstall_archive`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.
        extension (str | None): Valor de `extension` utilizado por la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    detected_extension = extension or candidate.extension
    return detected_extension == ".zip" and (
        candidate.source in {"winstall_api", "winstall_page"}
        or candidate.asset_kind == "winstall_download"
        # Una coincidencia exacta de producto hallada al resolver una entrada Windows
        # de Winstall es una señal más fuerte que un ZIP sin plataforma identificada.
        or bool(candidate.match_tokens)
    )


def installer_sort_key(installer: ValidInstaller) -> tuple[int, Any, int, int]:
    """Ejecuta la operación `installer_sort_key`.

    Args:
        installer (ValidInstaller): Valor de `installer` utilizado por la operación.

    Returns:
        tuple[int, Any, int, int]: Resultado producido por la operación.
    """
    version = parse_version(installer.version)
    return (
        1 if version is not None else 0,
        version or Version("0"),
        1 if installer.status == ResolutionStatus.DIRECT else 0,
        installer.candidate.score,
    )


def parse_version(value: str | None) -> Version | None:
    """Analiza la operación `version`.

    Args:
        value (str | None): Valor que debe procesarse.

    Returns:
        Version | None: Resultado producido por la operación.
    """
    if not value:
        return None
    try:
        return Version(value)
    except InvalidVersion:
        return None


def resolved_metadata(installer: ValidInstaller, is_latest: bool) -> dict:
    """Ejecuta la operación `resolved_metadata`.

    Args:
        installer (ValidInstaller): Valor de `installer` utilizado por la operación.
        is_latest (bool): Valor de `is_latest` utilizado por la operación.

    Returns:
        dict: Mapa con los datos producidos por la operación.
    """
    metadata = {
        "candidate_source": installer.candidate.source,
        "candidate_label": installer.candidate.label,
        "match_tokens": list(installer.candidate.match_tokens),
        "is_primary": is_latest,
        "is_latest": is_latest,
        "asset_kind": installer.candidate.asset_kind or "installer",
        "operating_system": installer.operating_system,
        "architecture": installer.architecture,
        "version_status": "latest" if is_latest else "previous",
        "validation_confidence": installer.result.confidence.value,
    }
    if installer.result.transport_security:
        metadata["transport_security"] = installer.result.transport_security
    return metadata


def scrape_app_failure_metadata(exc: Exception, winstall_id: str) -> dict:
    """Ejecuta la operación `scrape_app_failure_metadata`.

    Args:
        exc (Exception): Valor de `exc` utilizado por la operación.
        winstall_id (str): Identificador de `winstall` utilizado por la operación.

    Returns:
        dict: Mapa con los datos producidos por la operación.
    """
    metadata: dict[str, object] = {
        "winstall_id": winstall_id,
        "error": exc.__class__.__name__,
        "detail": exception_detail(exc),
    }
    if isinstance(exc, StatementError):
        metadata["statement"] = truncate_text(exc.statement, 1200)
        metadata["params"] = truncate_text(repr(exc.params), 1200)
    return json_safe(metadata)


def exception_detail(exc: Exception) -> str:
    """Ejecuta la operación `exception_detail`.

    Args:
        exc (Exception): Valor de `exc` utilizado por la operación.

    Returns:
        str: Resultado producido por la operación.
    """
    if isinstance(exc, StatementError) and exc.orig is not None:
        return truncate_text(f"{exc.orig.__class__.__name__}: {exc.orig}", 1200) or ""
    return truncate_text(str(exc), 1200) or ""


def is_transient_mysql_lock_error(exc: OperationalError) -> bool:
    """Indica si se cumple la operación `transient_mysql_lock_error`.

    Args:
        exc (OperationalError): Valor de `exc` utilizado por la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    args: tuple[object, ...] = getattr(exc.orig, "args", ())
    return bool(args) and args[0] in {1205, 1213}


def truncate_text(value: object, max_length: int) -> str | None:
    """Ejecuta la operación `truncate_text`.

    Args:
        value (object): Valor que debe procesarse.
        max_length (int): Valor de `max_length` utilizado por la operación.

    Returns:
        str | None: Resultado producido por la operación.
    """
    if value is None:
        return None
    text = str(value)
    if len(text) <= max_length:
        return text
    return text[: max_length - 3] + "..."
