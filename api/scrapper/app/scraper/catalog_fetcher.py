from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.logging import get_logger
from app.core.url_protector import UrlProtector
from app.db.enums import ScrapeRunStatus
from app.repositories.catalog import CatalogRepository
from app.repositories.logs import ResolverLogRepository
from app.repositories.runs import ScrapeRunRepository
from app.scraper.resolver import InstallerResolver
from app.scraper.validator import DownloadValidator
from app.scraper.winstall import WinstallClient

logger = get_logger(__name__)


@dataclass
class ScrapeCounters:
    apps_discovered: int = 0
    apps_resolved: int = 0
    apps_failed: int = 0


class CatalogFetcher:
    def __init__(self, settings: Settings, session: AsyncSession) -> None:
        self.settings = settings
        self.session = session
        self.url_protector = UrlProtector(settings.url_protection_secret)
        self.catalog = CatalogRepository(session, self.url_protector)
        self.logs = ResolverLogRepository(session)
        self.validator = DownloadValidator(settings)
        self.resolver = InstallerResolver(settings, self.catalog, self.logs, self.validator)
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

        counters = ScrapeCounters()
        try:
            async with WinstallClient(self.settings) as winstall:
                async for lightweight_app in winstall.iter_apps():
                    if (
                        self.settings.scrape_max_apps > 0
                        and counters.apps_discovered >= self.settings.scrape_max_apps
                    ):
                        break
                    counters.apps_discovered += 1
                    try:
                        app = await winstall.get_app(lightweight_app.package_id)
                        software_app = await self.catalog.upsert_winstall_app(app)
                        await self.session.flush()
                        source = await self.catalog.default_source_for_app(software_app.id)
                        if source:
                            await self.resolver.resolve(source, app)
                            counters.apps_resolved += 1
                        if counters.apps_discovered % 10 == 0:
                            await self.runs.heartbeat(run_id, **counters.__dict__)
                            await self.session.commit()
                    except Exception as exc:
                        counters.apps_failed += 1
                        await self.session.rollback()
                        await self.logs.add(
                            phase="scrape_app",
                            status="failed",
                            message=exc.__class__.__name__,
                            safe_metadata={"winstall_id": lightweight_app.package_id},
                        )
                        await self.session.commit()

            final_status = (
                ScrapeRunStatus.PARTIAL if counters.apps_failed else ScrapeRunStatus.COMPLETED
            )
            await self.runs.finish(run_id, final_status, **counters.__dict__)
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
