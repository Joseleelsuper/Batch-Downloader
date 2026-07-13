from __future__ import annotations

import asyncio
import uuid
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any
from urllib.parse import urlparse, urlunparse

import httpx
from packaging.version import InvalidVersion, Version
from sqlalchemy.exc import OperationalError, StatementError
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


@dataclass
class CandidateValidationDiagnostics:
    discovered: int = 0
    eligible: int = 0
    attempted: int = 0
    valid: int = 0
    skipped: dict[str, int] = field(default_factory=dict)
    rejected: dict[str, int] = field(default_factory=dict)
    errors: dict[str, int] = field(default_factory=dict)

    def skip(self, reason: str) -> None:
        self.skipped[reason] = self.skipped.get(reason, 0) + 1

    def reject(self, reason: str | None) -> None:
        key = reason or "unknown"
        self.rejected[key] = self.rejected.get(key, 0) + 1

    def error(self, exc: Exception) -> None:
        key = exc.__class__.__name__
        self.errors[key] = self.errors.get(key, 0) + 1

    def as_metadata(self) -> dict[str, Any]:
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
                    DescriptorWorker(self.settings).run(runtime),
                    name="scraper-descriptor",
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

    async def _run_scraper_workers(self, runtime: PipelineRuntime) -> None:
        workers = [
            PlatformScraperWorker(self.settings)
            for _ in range(max(1, self.settings.scrape_concurrency))
        ]
        try:
            await asyncio.gather(*(worker.run(runtime) for worker in workers))
        finally:
            runtime.scraper_done.set()


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
        self.github = GitHubReleaseResolver(settings)

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
        if await self._candidate_group_has_valid_download(app, candidates):
            return True
        refreshed = await self._collect_winstall_github_candidates(app, candidates)
        return await self._candidate_group_has_valid_download(app, refreshed)

    async def _candidate_group_has_valid_download(
        self,
        app: WinstallApp,
        candidates: list[InstallerCandidate],
    ) -> bool:
        expanded_candidates = [
            variant
            for candidate in dedupe_candidates(candidates)
            for variant in candidate_variants(candidate)
        ]
        scored = [
            score_candidate(
                candidate,
                app_name=app.name,
                package_id=app.package_id,
                publisher=app.publisher,
                version=app.latest_version,
            )
            for candidate in dedupe_candidates(expanded_candidates)
            if is_download_candidate(candidate)
        ]
        scored.sort(key=lambda candidate: candidate.score, reverse=True)
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
        """Resolve stale Winstall GitHub assets before deciding an app is unusable."""
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
        """Refresh stale versioned files from a bounded same-host directory index."""
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
    def __init__(
        self,
        settings: Settings,
        candidate_resolvers: CandidateResolverStrategyRegistry | None = None,
    ) -> None:
        self.settings = settings
        self.worker_id = f"scraper:{worker_id()}"
        self.url_protector = UrlProtector(settings.url_protection_secret)
        self.validator = DownloadValidator(settings)
        self.playwright = PlaywrightCandidateCollector(settings)
        self.github = GitHubReleaseResolver(settings)
        self.icon_resolver = IconResolver(settings)
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

    async def run(self, runtime: PipelineRuntime) -> None:
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

        candidates = extract_candidates(html, official_url) if html else []
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
        """Inspect a few first-party download pages before paying for Playwright.

        Sites commonly expose a product page first (for example `/download.html` or
        `?do=download`) and put the actual binary link on that page. The former
        implementation never traversed that lightweight hop.
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
        """Refresh expired Winstall assets through releases or their parent index."""
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
        return await FilterWorker._collect_winstall_parent_index_candidates(self, candidates)

    async def _validate_installers(
        self,
        *,
        app: WinstallApp,
        official_url: str | None,
        direct_candidates: list[InstallerCandidate],
        fallback_candidates: list[InstallerCandidate],
    ) -> tuple[list[ValidInstaller], dict[str, dict[str, Any]]]:
        # A slow official page must not starve a valid Winstall fallback. Both
        # groups are independent trust paths, so validate them concurrently.
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
            async with asyncio.timeout(min(8.0, self.settings.request_timeout_seconds)):
                result = await self.icon_resolver.resolve(app)
        except TimeoutError:
            await logs.add(
                phase="icon",
                status="timeout",
                message="icon_resolution_timeout",
                safe_metadata={"winstall_id": app.package_id},
            )
            return
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
        elif action == "requeue":
            await pipeline.requeue(db_item, message or "retry")
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
    return (
        command.command in {"pause", "resume", "stop", "force_stop"}
        and command.created_at < run_started_at
    )


def first_task_failure(error: BaseException) -> BaseException:
    """Unwrap TaskGroup failures so run state records the actionable root cause."""

    if isinstance(error, BaseExceptionGroup):
        for nested in error.exceptions:
            failure = first_task_failure(nested)
            if not isinstance(failure, asyncio.CancelledError):
                return failure
    return error


def fallback_candidates(payload: dict[str, Any], app: WinstallApp) -> list[InstallerCandidate]:
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
    if app.package_id == "115.115Chrome" and app.latest_version:
        version = app.latest_version.strip().removeprefix("v")
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
    if app.package_id == "123.123pan" and app.latest_version:
        version = normalized_123pan_version(app.latest_version)
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
    return bool(known_candidates) and app.package_id in {
        "EpicGames.EpicGamesLauncher",
        "115.115Chrome",
        "123.123pan",
    }


def use_winstall_fallback_only(
    app: WinstallApp,
    fallback: list[InstallerCandidate],
) -> bool:
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
    """Known official endpoints remain usable if their marketing page blocks bots."""
    if known_official_candidates(app):
        return True
    return bool(use_official and official_url and not use_winstall_fallback_only(app, fallback))


def normalized_123pan_version(value: str) -> str:
    """The Winstall four-part display version maps to a three-part 123pan filename."""
    parts = value.strip().removeprefix("v").split(".")
    if len(parts) >= 3 and all(part.isdigit() for part in parts[:3]):
        return ".".join(parts[:3])
    return value.strip().removeprefix("v")


def is_download_landing_page(
    candidate: InstallerCandidate,
    official_url: str,
    official_domain: str | None,
) -> bool:
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
    """Return true only for a navigable candidate that already resembles a file.

    A `javascript:;` download button is an interaction hint, not an installer. It
    must not suppress the Playwright fallback that can reveal the next download
    dialog or route.
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
    """Bound release discovery so an existing Winstall asset still gets validated."""
    return max(5.0, min(15.0, settings.request_timeout_seconds + 2.0))


def winstall_parent_index_url(url: str) -> str | None:
    """Return the directory containing an explicit versioned Winstall file."""
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
    deduped: dict[str, InstallerCandidate] = {}
    for candidate in candidates:
        if candidate.url and candidate.url not in deduped:
            deduped[candidate.url] = candidate
    return list(deduped.values())


def dedupe_valid_installers(installers: list[ValidInstaller]) -> list[ValidInstaller]:
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
    final_candidate = InstallerCandidate(
        url=result.final_url or candidate.url,
        source=candidate.source,
        label=result.filename or candidate.label,
        context=candidate.context,
    )
    return extract_version(final_candidate) or extract_version(candidate)


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
    detected_extension = extension or candidate.extension
    return detected_extension == ".zip" and (
        candidate.source in {"winstall_api", "winstall_page"}
        or candidate.asset_kind == "winstall_download"
        # An exact product match found while resolving a Windows Winstall entry
        # is a stronger signal than an otherwise platform-less ZIP filename.
        or bool(candidate.match_tokens)
    )


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
        "validation_confidence": installer.result.confidence.value,
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


def is_transient_mysql_lock_error(exc: OperationalError) -> bool:
    args = getattr(exc.orig, "args", ())
    return bool(args) and args[0] in {1205, 1213}


def truncate_text(value: object, max_length: int) -> str | None:
    if value is None:
        return None
    text = str(value)
    if len(text) <= max_length:
        return text
    return text[: max_length - 3] + "..."
