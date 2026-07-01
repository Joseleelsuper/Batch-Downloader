from __future__ import annotations

import asyncio
from dataclasses import dataclass

from sqlalchemy.exc import StatementError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.json_safe import json_safe
from app.core.logging import get_logger
from app.core.url_protector import UrlProtector
from app.db.enums import ScrapeRunStatus
from app.repositories.catalog import CatalogRepository
from app.repositories.logs import ResolverLogRepository
from app.repositories.runs import ScrapeRunRepository
from app.scraper.candidates import registered_domain
from app.scraper.description_enricher import AppDescriptionEnricher
from app.scraper.icon_resolver import IconResolver
from app.scraper.resolver import InstallerResolver
from app.scraper.validator import DownloadValidator
from app.scraper.winstall import WinstallClient

logger = get_logger(__name__)


@dataclass
class ScrapeCounters:
    apps_discovered: int = 0
    apps_resolved: int = 0
    apps_failed: int = 0
    apps_skipped: int = 0


class CatalogFetcher:
    def __init__(self, settings: Settings, session: AsyncSession) -> None:
        self.settings = settings
        self.session = session
        self.url_protector = UrlProtector(settings.url_protection_secret)
        self.catalog = CatalogRepository(session, self.url_protector)
        self.logs = ResolverLogRepository(session)
        self.validator = DownloadValidator(settings)
        self.resolver = InstallerResolver(settings, self.catalog, self.logs, self.validator)
        self.icon_resolver = IconResolver(settings)
        self.description_enricher = AppDescriptionEnricher(settings, self.catalog, self.logs)
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
        logger.info(
            "scrape_started",
            run_id=str(run_id),
            recover_running=recover_running,
            max_apps=self.settings.scrape_max_apps,
        )

        counters = ScrapeCounters()
        stopped_by_command = False
        try:
            async with WinstallClient(self.settings) as winstall:
                async for lightweight_app in winstall.iter_apps():
                    if await self._apply_pending_commands(run_id, counters):
                        stopped_by_command = True
                        break
                    if (
                        self.settings.scrape_max_apps > 0
                        and counters.apps_discovered >= self.settings.scrape_max_apps
                    ):
                        break
                    await self.runs.set_current(
                        run_id,
                        lightweight_app.package_id,
                        getattr(lightweight_app, "name", None),
                        "checking_catalog",
                    )
                    should_scrape = await self.catalog.should_scrape_winstall_package(
                        lightweight_app.package_id
                    )
                    if not should_scrape:
                        counters.apps_skipped += 1
                        continue
                    counters.apps_discovered += 1
                    try:
                        resolved = await asyncio.wait_for(
                            self._scrape_single_app(winstall, lightweight_app.package_id, run_id),
                            timeout=self.settings.scrape_app_timeout_seconds,
                        )
                        if resolved:
                            counters.apps_resolved += 1
                    except TimeoutError:
                        counters.apps_failed += 1
                        await self.session.rollback()
                        await self.logs.add(
                            phase="scrape_app",
                            status="failed",
                            message="TimeoutError",
                            safe_metadata={"winstall_id": lightweight_app.package_id},
                        )
                        await self.session.commit()
                        logger.warning(
                            "scrape_app_timeout",
                            winstall_id=lightweight_app.package_id,
                            timeout_seconds=self.settings.scrape_app_timeout_seconds,
                        )
                    except Exception as exc:
                        counters.apps_failed += 1
                        await self.session.rollback()
                        await self.logs.add(
                            phase="scrape_app",
                            status="failed",
                            message=exc.__class__.__name__,
                            safe_metadata=scrape_app_failure_metadata(
                                exc,
                                lightweight_app.package_id,
                            ),
                        )
                        await self.session.commit()
                        logger.warning(
                            "scrape_app_failed",
                            winstall_id=lightweight_app.package_id,
                            error=exc.__class__.__name__,
                            detail=exception_detail(exc),
                        )
                    if counters.apps_discovered % 10 == 0:
                        await self.runs.heartbeat(run_id, **counters.__dict__)
                        await self.session.commit()
                        logger.info(
                            "scrape_progress",
                            run_id=str(run_id),
                            **counters.__dict__,
                        )
                    if (
                        self.settings.llm_enrich_interval_apps > 0
                        and counters.apps_discovered % self.settings.llm_enrich_interval_apps == 0
                    ):
                        await self._enrich_descriptions(run_id)

            if not stopped_by_command:
                await self.runs.set_current(run_id, None, None, "enriching_descriptions")
                await self._enrich_descriptions(run_id)

            final_status = (
                ScrapeRunStatus.PARTIAL
                if stopped_by_command or counters.apps_failed
                else ScrapeRunStatus.COMPLETED
            )
            await self.runs.finish(
                run_id,
                final_status,
                error_summary="Stopped by admin command" if stopped_by_command else None,
                **counters.__dict__,
            )
            await self.session.commit()
            return counters
        except Exception as exc:
            await self.runs.finish(
                run_id,
                ScrapeRunStatus.FAILED,
                error_summary=exc.__class__.__name__,
                **counters.__dict__,
            )
            await self.session.commit()
            raise

    async def _scrape_single_app(
        self,
        winstall: WinstallClient,
        package_id: str,
        run_id,
    ) -> bool:
        await self.runs.set_current(run_id, package_id, None, "fetching_winstall_app")
        app = await winstall.get_app(package_id)
        await self.runs.set_current(run_id, app.package_id, app.name, "upserting_app")
        software_app = await self.catalog.upsert_winstall_app(app)
        await self.runs.set_current(run_id, app.package_id, app.name, "resolving_icon")
        await self._resolve_missing_icon(software_app.id, software_app.icon_url, app)
        await self.session.flush()
        source = await self.catalog.default_source_for_app(software_app.id)
        if not source:
            return False
        await self.runs.set_current(run_id, app.package_id, app.name, "resolving_installer")
        await self.resolver.resolve(source, app)
        return True

    async def _apply_pending_commands(self, run_id, counters: ScrapeCounters) -> bool:
        while True:
            command = await self.runs.next_pending_command()
            if not command:
                return False

            if command.command == "pause":
                await self.runs.consume_command(command)
                await self.runs.mark_paused(run_id)
                await self.session.commit()
                logger.info("scrape_paused", run_id=str(run_id))
                return await self._wait_until_resume_or_stop(run_id, counters)

            if command.command == "resume":
                await self.runs.consume_command(command, message="No paused run was waiting.")
                await self.session.commit()
                continue

            if command.command == "stop":
                await self.runs.mark_stop_requested(run_id)
                await self.runs.consume_command(command)
                await self.session.commit()
                logger.info("scrape_stop_requested", run_id=str(run_id))
                return True

            if command.command == "run_once":
                await self.runs.consume_command(
                    command,
                    status="rejected",
                    message="A scraper run is already active.",
                )
                await self.session.commit()
                continue

            await self.runs.consume_command(
                command,
                status="failed",
                message=f"Unsupported command: {command.command}",
            )
            await self.session.commit()

    async def _wait_until_resume_or_stop(self, run_id, counters: ScrapeCounters) -> bool:
        while True:
            await self.runs.mark_paused(run_id)
            await self.runs.heartbeat(run_id, **counters.__dict__)
            await self.session.commit()
            await asyncio.sleep(5)
            command = await self.runs.next_pending_command()
            if not command:
                continue
            if command.command == "resume":
                await self.runs.consume_command(command)
                await self.runs.set_current(run_id, None, None, "running")
                await self.session.commit()
                logger.info("scrape_resumed", run_id=str(run_id))
                return False
            if command.command == "stop":
                await self.runs.mark_stop_requested(run_id)
                await self.runs.consume_command(command)
                await self.session.commit()
                logger.info("scrape_stop_requested_while_paused", run_id=str(run_id))
                return True
            await self.runs.consume_command(
                command,
                status="rejected",
                message="Only resume or stop are accepted while paused.",
            )
            await self.session.commit()

    async def _enrich_descriptions(self, run_id) -> int:
        logger.info(
            "descriptions_enrichment_triggered",
            run_id=str(run_id),
            max_apps=self.settings.llm_max_apps_per_run,
        )
        try:
            enriched = await self.description_enricher.enrich_pending()
            await self.session.commit()
            logger.info(
                "descriptions_enrichment_done",
                run_id=str(run_id),
                count=enriched,
            )
            return enriched
        except Exception as exc:
            await self.session.rollback()
            await self.logs.add(
                phase="description",
                status="failed",
                message=exc.__class__.__name__,
            )
            await self.session.commit()
            logger.warning(
                "descriptions_enrichment_failed",
                run_id=str(run_id),
                error=exc.__class__.__name__,
            )
            return 0

    async def _resolve_missing_icon(
        self,
        software_app_id,
        current_icon_url: str | None,
        app,
    ) -> None:
        if current_icon_url and current_icon_url.strip() and current_icon_url.strip() != "-":
            return
        try:
            result = await self.icon_resolver.resolve(app)
        except Exception as exc:
            await self.logs.add(
                phase="icon",
                status="failed",
                message=exc.__class__.__name__,
                safe_metadata={"winstall_id": app.package_id},
            )
            return
        if not result:
            return
        await self.catalog.update_icon_url(software_app_id, result.url)
        await self.logs.add(
            phase="icon",
            status="resolved",
            safe_metadata={
                "winstall_id": app.package_id,
                "source": result.source,
                "domain": registered_domain(result.url),
            },
        )


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
