from __future__ import annotations

import asyncio
import uuid
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

import httpx
from packaging.version import InvalidVersion, Version
from sqlalchemy.exc import StatementError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.json_safe import json_safe
from app.core.logging import get_logger
from app.core.url_protector import UrlProtector
from app.db.enums import LongDescriptionStatus, ResolutionStatus, ScrapeRunStatus, ValidationStatus
from app.db.models import ScraperWorkItem
from app.repositories.catalog import CatalogRepository, ResolvedSourceCreate
from app.repositories.logs import ResolverLogRepository
from app.repositories.pipeline import (
    QUEUE_FILTER_SCRAPER,
    QUEUE_SCRAPER_DESCRIPTOR,
    QUEUE_SEARCHER_FILTER,
    PipelineRepository,
)
from app.repositories.runs import ScrapeRunRepository, worker_id
from app.scraper.candidates import (
    InstallerCandidate,
    extract_candidates,
    extract_version,
    infer_architecture,
    infer_operating_system,
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
from app.scraper.validator import DownloadValidator, ValidationResult, domain_has_public_dns
from app.scraper.winstall import WinstallApp, WinstallClient, parse_winstall_app

logger = get_logger(__name__)


def async_session_local():
    from app.db.session import AsyncSessionLocal

    return AsyncSessionLocal


@dataclass
class ScrapeCounters:
    apps_discovered: int = 0
    apps_resolved: int = 0
    apps_failed: int = 0
    apps_skipped: int = 0


@dataclass
class PipelineRuntime:
    settings: Settings
    run_id: uuid.UUID
    run_started_at: datetime
    counters: ScrapeCounters = field(default_factory=ScrapeCounters)
    stop_event: asyncio.Event = field(default_factory=asyncio.Event)
    pause_event: asyncio.Event = field(default_factory=asyncio.Event)
    searcher_done: asyncio.Event = field(default_factory=asyncio.Event)
    filter_done: asyncio.Event = field(default_factory=asyncio.Event)
    scraper_done: asyncio.Event = field(default_factory=asyncio.Event)
    descriptor_done: asyncio.Event = field(default_factory=asyncio.Event)
    all_workers_done: asyncio.Event = field(default_factory=asyncio.Event)
    stopped_by_command: bool = False
    _counter_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    _descriptor_budget_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    _descriptor_attempts: int = 0

    async def before_next_item(self) -> bool:
        while self.pause_event.is_set() and not self.stop_event.is_set():
            await asyncio.sleep(1)
        return not self.stop_event.is_set()

    async def increment(self, field_name: str, amount: int = 1) -> None:
        async with self._counter_lock:
            setattr(self.counters, field_name, getattr(self.counters, field_name) + amount)

    async def reserve_descriptor_attempt(self) -> bool:
        async with self._descriptor_budget_lock:
            maximum = self.settings.llm_max_apps_per_run
            if maximum > 0 and self._descriptor_attempts >= maximum:
                return False
            self._descriptor_attempts += 1
            return True

    async def release_descriptor_attempt(self) -> None:
        async with self._descriptor_budget_lock:
            self._descriptor_attempts = max(0, self._descriptor_attempts - 1)


@dataclass(frozen=True)
class ValidInstaller:
    candidate: InstallerCandidate
    result: ValidationResult
    status: ResolutionStatus
    operating_system: str
    architecture: str
    version: str | None


class CatalogFetcher:
    def __init__(self, settings: Settings, session: AsyncSession) -> None:
        self.settings = settings
        self.session = session
        self.url_protector = UrlProtector(settings.url_protection_secret)
        self.catalog = CatalogRepository(session, self.url_protector)
        self.runs = ScrapeRunRepository(session, settings)

    async def scrape_once(self, recover_running: bool = False) -> ScrapeCounters:
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
            pending_work = await pipeline.has_pending_work()
            await session.commit()
        if recovered_items:
            logger.warning("scraper_pipeline_leases_recovered", count=recovered_items)

        seeded_descriptions = await self._seed_descriptor_queue(run_id)
        if seeded_descriptions:
            logger.info("descriptor_backlog_seeded", count=seeded_descriptions)

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

        tasks = [
            asyncio.create_task(self._command_monitor(runtime), name="scraper-command-monitor"),
            asyncio.create_task(self._heartbeat(runtime), name="scraper-heartbeat"),
            asyncio.create_task(
                SearcherWorker(self.settings).run(runtime),
                name="scraper-searcher",
            ),
            asyncio.create_task(FilterWorker(self.settings).run(runtime), name="scraper-filter"),
            asyncio.create_task(
                PlatformScraperWorker(self.settings).run(runtime),
                name="scraper-worker",
            ),
            asyncio.create_task(
                DescriptorWorker(self.settings).run(runtime),
                name="scraper-descriptor",
            ),
        ]

        worker_error: BaseException | None = None
        try:
            worker_tasks = [task for task in tasks if task.get_name() not in {
                "scraper-command-monitor",
                "scraper-heartbeat",
            }]
            results = await asyncio.gather(*worker_tasks, return_exceptions=True)
            for result in results:
                if isinstance(result, BaseException):
                    worker_error = result
                    runtime.stop_event.set()
                    break
        finally:
            runtime.all_workers_done.set()
            await asyncio.gather(*tasks, return_exceptions=True)

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
        while not runtime.all_workers_done.is_set():
            async with async_session_local()() as session:
                runs = ScrapeRunRepository(session, self.settings)
                pipeline = PipelineRepository(session)
                await runs.heartbeat(runtime.run_id, **runtime.counters.__dict__)
                await pipeline.save_metric_snapshot(runtime.run_id)
                await session.commit()
            await asyncio.sleep(5)

    async def _seed_descriptor_queue(self, run_id: uuid.UUID) -> int:
        async with async_session_local()() as session:
            catalog = CatalogRepository(session, self.url_protector)
            pipeline = PipelineRepository(session)
            maximum = self.settings.llm_max_apps_per_run
            queued = 0
            apps = await catalog.apps_for_description_enrichment(include_completed=True)
            for app in apps:
                input_hash = description_input_hash(app)
                current = (
                    app.long_description_status == LongDescriptionStatus.COMPLETED.value
                    and bool(app.long_description)
                    and app.long_description_input_hash == input_hash
                )
                if current:
                    continue
                await catalog.mark_long_description_pending(app.id)
                await pipeline.enqueue(
                    QUEUE_SCRAPER_DESCRIPTOR,
                    app.winstall_id,
                    app.name,
                    {
                        "software_app_id": str(app.id),
                        "package_id": app.winstall_id,
                        "input_hash": input_hash,
                        "force": False,
                    },
                    run_id,
                )
                queued += 1
                if maximum > 0 and queued >= maximum:
                    break
            await session.commit()
            return queued


class SearcherWorker:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.worker_id = f"searcher:{worker_id()}"

    async def run(self, runtime: PipelineRuntime) -> None:
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
                    payload = {
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
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.worker_id = f"filter:{worker_id()}"
        self.validator = DownloadValidator(settings)

    async def run(self, runtime: PipelineRuntime) -> None:
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
                    await pipeline.complete(item)
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
        candidates = fallback_candidates(payload, app)
        for candidate in candidates[:12]:
            try:
                result = await self.validator.validate(candidate)
            except Exception:
                continue
            if result.ok:
                return True
        return False


class PlatformScraperWorker:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.worker_id = f"scraper:{worker_id()}"
        self.url_protector = UrlProtector(settings.url_protection_secret)
        self.validator = DownloadValidator(settings)
        self.playwright = PlaywrightCandidateCollector(settings)
        self.github = GitHubReleaseResolver(settings)
        self.icon_resolver = IconResolver(settings)

    async def run(self, runtime: PipelineRuntime) -> None:
        try:
            while not runtime.stop_event.is_set():
                if not await runtime.before_next_item():
                    break
                item = await claim_item(self.settings, QUEUE_FILTER_SCRAPER, self.worker_id)
                if item is None:
                    if runtime.searcher_done.is_set() and runtime.filter_done.is_set():
                        break
                    await asyncio.sleep(1)
                    continue
                try:
                    resolved = await self._scrape_item(runtime, item)
                    await finish_item(self.settings, item, "complete", None)
                    if resolved:
                        await runtime.increment("apps_resolved")
                    else:
                        await runtime.increment("apps_failed")
                except Exception as exc:
                    await finish_item(self.settings, item, "fail", exc.__class__.__name__)
                    await runtime.increment("apps_failed")
                    logger.warning(
                        "scraper_app_failed",
                        winstall_id=item.package_id,
                        error=exc.__class__.__name__,
                        detail=exception_detail(exc),
                    )
        finally:
            runtime.scraper_done.set()

    async def _scrape_item(self, runtime: PipelineRuntime, item: ScraperWorkItem) -> bool:
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
            software_app = await catalog.upsert_winstall_app(app)
            await self._resolve_missing_icon(
                catalog,
                logs,
                software_app.id,
                software_app.icon_url,
                app,
            )
            await session.commit()

        direct_candidates: list[InstallerCandidate] = []
        filter_info = payload.get("filter") or {}
        if filter_info.get("use_official") and official_url:
            await set_current(
                self.settings,
                runtime.run_id,
                app.package_id,
                app.name,
                "scraper_collecting_official_installers",
            )
            direct_candidates = await self._collect_official_candidates(
                runtime,
                app,
                official_url,
            )

        fallback = fallback_candidates(payload, app)
        valid_installers = await self._validate_installers(
            app=app,
            official_url=official_url,
            direct_candidates=direct_candidates,
            fallback_candidates=fallback,
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
                        safe_metadata={"winstall_id": app.package_id},
                    )
                await enqueue_descriptor_for_app(
                    catalog,
                    PipelineRepository(session),
                    runtime.run_id,
                    software_app,
                    force=False,
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
            await enqueue_descriptor_for_app(
                catalog,
                PipelineRepository(session),
                runtime.run_id,
                software_app,
                force=False,
            )
            await session.commit()
            return True

    async def _collect_official_candidates(
        self,
        runtime: PipelineRuntime,
        app: WinstallApp,
        official_url: str,
    ) -> list[InstallerCandidate]:
        known_candidates = known_official_candidates(app)
        if parse_github_repo(official_url):
            try:
                return dedupe_candidates([*known_candidates, *(await self.github.collect(official_url))])
            except Exception:
                return known_candidates

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

        candidates = [*known_candidates, *(extract_candidates(html, official_url) if html else [])]
        try:
            candidates.extend(await self.playwright.collect(official_url))
        except Exception:
            pass
        return dedupe_candidates(candidates)

    async def _validate_installers(
        self,
        *,
        app: WinstallApp,
        official_url: str | None,
        direct_candidates: list[InstallerCandidate],
        fallback_candidates: list[InstallerCandidate],
    ) -> list[ValidInstaller]:
        valid: list[ValidInstaller] = []
        valid.extend(
            await self._validate_candidate_group(
                app,
                direct_candidates,
                ResolutionStatus.DIRECT,
                max_candidates=96,
            )
        )

        valid.extend(
            await self._validate_candidate_group(
                app,
                fallback_candidates,
                ResolutionStatus.FALLBACK,
                max_candidates=48,
            )
        )
        return dedupe_valid_installers(valid)

    async def _validate_candidate_group(
        self,
        app: WinstallApp,
        candidates: list[InstallerCandidate],
        status: ResolutionStatus,
        max_candidates: int,
    ) -> list[ValidInstaller]:
        scored = []
        for candidate in dedupe_candidates(candidates):
            operating_system = infer_operating_system(candidate)
            if not operating_system:
                continue
            scored.append(
                score_candidate(
                    candidate,
                    app_name=app.name,
                    package_id=app.package_id,
                    publisher=app.publisher,
                    version=app.latest_version,
                )
            )
        scored.sort(key=lambda candidate: candidate.score, reverse=True)

        valid: list[ValidInstaller] = []
        for candidate in scored[:max_candidates]:
            if candidate.score <= 0:
                continue
            try:
                result = await self.validator.validate(candidate)
            except Exception:
                continue
            if not result.ok:
                continue
            operating_system = infer_validated_operating_system(candidate, result)
            if not operating_system:
                continue
            valid.append(
                ValidInstaller(
                    candidate=candidate,
                    result=result,
                    status=status,
                    operating_system=operating_system,
                    architecture=infer_architecture(candidate),
                    version=extract_version(candidate) or app.latest_version,
                )
            )
        return valid

    async def _save_valid_installers(
        self,
        catalog: CatalogRepository,
        logs: ResolverLogRepository,
        software_app_id: uuid.UUID,
        app: WinstallApp,
        official_url: str | None,
        installers: list[ValidInstaller],
    ) -> None:
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

    async def _resolve_missing_icon(
        self,
        catalog: CatalogRepository,
        logs: ResolverLogRepository,
        software_app_id,
        current_icon_url: str | None,
        app: WinstallApp,
    ) -> None:
        if current_icon_url and current_icon_url.strip() and current_icon_url.strip() != "-":
            return
        try:
            result = await self.icon_resolver.resolve(app)
        except Exception as exc:
            await logs.add(
                phase="icon",
                status="failed",
                message=exc.__class__.__name__,
                safe_metadata={"winstall_id": app.package_id},
            )
            return
        if not result:
            return
        await catalog.update_icon_url(software_app_id, result.url)
        await logs.add(
            phase="icon",
            status="resolved",
            safe_metadata={
                "winstall_id": app.package_id,
                "source": result.source,
                "domain": registered_domain(result.url),
            },
        )


async def enqueue_descriptor_for_app(
    catalog: CatalogRepository,
    pipeline: PipelineRepository,
    run_id: uuid.UUID | None,
    software_app: Any,
    *,
    force: bool,
    priority: int = 0,
) -> ScraperWorkItem | None:
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
        QUEUE_SCRAPER_DESCRIPTOR,
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


class DescriptorWorker:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.worker_id = f"descriptor:{worker_id()}"
        self.llm = AppDescriptionLLMClient(settings)

    async def run(self, runtime: PipelineRuntime) -> None:
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
        if not self.llm.has_provider():
            logger.warning("descriptor_process_one_skipped", reason="llm_provider_not_configured")
            return False
        item = await claim_item(self.settings, QUEUE_SCRAPER_DESCRIPTOR, self.worker_id)
        if item is None:
            return False
        return await self._process_claimed_item(None, item)

    async def _consume(self, runtime: PipelineRuntime) -> None:
        while not runtime.stop_event.is_set():
            if not await runtime.before_next_item():
                break
            if not await runtime.reserve_descriptor_attempt():
                logger.info("descriptor_budget_exhausted")
                break
            item = await claim_item(self.settings, QUEUE_SCRAPER_DESCRIPTOR, self.worker_id)
            if item is None:
                await runtime.release_descriptor_attempt()
                if runtime.scraper_done.is_set():
                    break
                await asyncio.sleep(1)
                continue
            await self._process_claimed_item(runtime, item)

    async def _process_claimed_item(
        self,
        runtime: PipelineRuntime | None,
        item: ScraperWorkItem,
    ) -> bool:
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


async def claim_item(
    settings: Settings,
    queue: str,
    worker_id_value: str,
) -> ScraperWorkItem | None:
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
) -> None:
    async with async_session_local()() as session:
        pipeline = PipelineRepository(session)
        db_item = await session.get(ScraperWorkItem, item.id)
        if not db_item:
            return
        if action == "complete":
            await pipeline.complete(db_item)
        elif action == "discard":
            await pipeline.discard(db_item, message or "discarded")
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
    async with async_session_local()() as session:
        runs = ScrapeRunRepository(session, settings)
        await runs.set_current(run_id, package_id, app_name, phase)
        await session.commit()


def parse_payload_app(payload: dict[str, Any], fallback_package_id: str) -> WinstallApp:
    raw = payload.get("app")
    if isinstance(raw, dict):
        return parse_winstall_app(raw)
    return parse_winstall_app({"_id": fallback_package_id, "name": fallback_package_id})


def payload_package_id(payload: dict[str, Any], item: ScraperWorkItem) -> str:
    value = payload.get("package_id") or item.package_id
    return str(value)


def is_stale_control_command(command: Any, run_started_at: datetime) -> bool:
    return command.command in {"pause", "resume", "stop", "force_stop"} and command.created_at < run_started_at


def fallback_candidates(payload: dict[str, Any], app: WinstallApp) -> list[InstallerCandidate]:
    candidates: list[InstallerCandidate] = []
    for version in app.versions:
        for url in version.installers:
            candidates.append(
                InstallerCandidate(
                    url=url,
                    source="winstall_api",
                    label=f"{app.name} {version.installer_type or ''}".strip(),
                    context=version.version,
                    asset_kind="winstall_download",
                )
            )
    for item in payload.get("winstall_downloads") or []:
        if isinstance(item, dict) and item.get("url"):
            candidates.append(
                InstallerCandidate(
                    url=str(item["url"]),
                    source="winstall_page",
                    label=item.get("label") or app.name,
                    context=item.get("context"),
                    asset_kind="winstall_download",
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
                )
            )
    return dedupe_candidates(candidates)


def known_official_candidates(app: WinstallApp) -> list[InstallerCandidate]:
    if app.package_id == "EpicGames.EpicGamesLauncher":
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
    return []


def dedupe_candidates(candidates: list[InstallerCandidate]) -> list[InstallerCandidate]:
    deduped: dict[str, InstallerCandidate] = {}
    for candidate in candidates:
        if candidate.url and candidate.url not in deduped:
            deduped[candidate.url] = candidate
    return list(deduped.values())


def dedupe_valid_installers(installers: list[ValidInstaller]) -> list[ValidInstaller]:
    deduped: dict[tuple[str, str, str, str | None], ValidInstaller] = {}
    for installer in installers:
        url = installer.result.final_url or installer.candidate.url
        key = (url, installer.operating_system, installer.architecture, installer.version)
        current = deduped.get(key)
        if current is None or installer.candidate.score > current.candidate.score:
            deduped[key] = installer
    return list(deduped.values())


def rank_installers(installers: list[ValidInstaller]) -> list[tuple[ValidInstaller, int, bool]]:
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
    return infer_operating_system(candidate)


def installer_sort_key(installer: ValidInstaller) -> tuple[int, Any, int, int]:
    version = parse_version(installer.version)
    return (
        1 if version is not None else 0,
        version or Version("0"),
        1 if installer.status == ResolutionStatus.DIRECT else 0,
        installer.candidate.score,
    )


def parse_version(value: str | None) -> Version | None:
    if not value:
        return None
    try:
        return Version(value)
    except InvalidVersion:
        return None


def resolved_metadata(installer: ValidInstaller, is_latest: bool) -> dict:
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
    }
    if installer.result.transport_security:
        metadata["transport_security"] = installer.result.transport_security
    return metadata


def scrape_app_failure_metadata(exc: Exception, winstall_id: str) -> dict:
    metadata = {
        "winstall_id": winstall_id,
        "error": exc.__class__.__name__,
        "detail": exception_detail(exc),
    }
    if isinstance(exc, StatementError):
        metadata["statement"] = truncate_text(exc.statement, 1200)
        metadata["params"] = truncate_text(repr(exc.params), 1200)
    return json_safe(metadata)


def exception_detail(exc: Exception) -> str:
    if isinstance(exc, StatementError) and exc.orig is not None:
        return truncate_text(f"{exc.orig.__class__.__name__}: {exc.orig}", 1200) or ""
    return truncate_text(str(exc), 1200) or ""


def truncate_text(value: object, max_length: int) -> str | None:
    if value is None:
        return None
    text = str(value)
    if len(text) <= max_length:
        return text
    return text[: max_length - 3] + "..."
