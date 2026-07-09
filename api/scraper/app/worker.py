from __future__ import annotations

import argparse
import asyncio

from apscheduler.schedulers.asyncio import AsyncIOScheduler

from app.core.config import get_settings
from app.core.logging import configure_logging, get_logger
from app.core.url_protector import UrlProtector
from app.db.enums import ResolutionStatus, ValidationStatus
from app.db.session import AsyncSessionLocal
from app.repositories.catalog import CatalogRepository, ResolvedSourceCreate
from app.repositories.logs import ResolverLogRepository
from app.scraper.candidates import extract_version, infer_architecture, registered_domain
from app.scraper.catalog_fetcher import (
    CatalogFetcher,
    ValidInstaller,
    infer_validated_operating_system,
    known_official_candidates,
    resolved_metadata,
)
from app.scraper.validator import DownloadValidator
from app.scraper.winstall import WinstallClient

logger = get_logger(__name__)


async def scrape_once(recover_running: bool = False) -> None:
    settings = get_settings()
    async with AsyncSessionLocal() as session:
        try:
            counters = await CatalogFetcher(settings, session).scrape_once(
                recover_running=recover_running
            )
        except Exception as exc:
            await session.rollback()
            logger.warning("scrape_once_failed", error=exc.__class__.__name__)
            raise
    logger.info("scrape_finished", **counters.__dict__)


async def repair_platforms() -> None:
    settings = get_settings()
    async with AsyncSessionLocal() as session:
        catalog = CatalogRepository(session, UrlProtector(settings.url_protection_secret))
        repaired = await catalog.repair_resolved_source_platforms()
        await session.commit()
    logger.info("platform_repair_finished", repaired=repaired)


async def repair_known_apps() -> None:
    settings = get_settings()
    repaired = 0
    async with WinstallClient(settings) as winstall:
        for package_id in ("EpicGames.EpicGamesLauncher",):
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


async def run_scheduler() -> None:
    settings = get_settings()
    scheduler = AsyncIOScheduler(timezone=settings.scheduler_zoneinfo)
    scheduler.add_job(
        scrape_once,
        trigger="cron",
        hour=settings.scheduler_hour,
        minute=settings.scheduler_minute,
        id="daily-winstall-scrape",
        replace_existing=True,
        max_instances=1,
    )
    scheduler.start()
    logger.info(
        "scheduler_started",
        hour=settings.scheduler_hour,
        minute=settings.scheduler_minute,
        timezone=settings.scheduler_timezone,
        run_on_startup=settings.run_on_startup,
    )
    if settings.run_on_startup:
        scheduler.add_job(
            scrape_once,
            trigger="date",
            kwargs={"recover_running": True},
            id="startup-winstall-scrape",
            replace_existing=True,
            max_instances=1,
        )
        logger.info("startup_scrape_scheduled")
    await asyncio.Event().wait()


def main() -> None:
    configure_logging()
    parser = argparse.ArgumentParser(description="Batch Downloader scraper worker")
    parser.add_argument(
        "command",
        choices=("scrape-once", "scheduler", "repair-platforms", "repair-known-apps"),
    )
    args = parser.parse_args()
    if args.command == "scrape-once":
        asyncio.run(scrape_once())
    elif args.command == "repair-platforms":
        asyncio.run(repair_platforms())
    elif args.command == "repair-known-apps":
        asyncio.run(repair_known_apps())
    else:
        asyncio.run(run_scheduler())


if __name__ == "__main__":
    main()
