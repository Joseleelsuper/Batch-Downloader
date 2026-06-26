from __future__ import annotations

import argparse
import asyncio

from apscheduler.schedulers.asyncio import AsyncIOScheduler

from app.core.config import get_settings
from app.core.logging import configure_logging, get_logger
from app.db.session import AsyncSessionLocal
from app.scraper.catalog_fetcher import CatalogFetcher

logger = get_logger(__name__)


async def scrape_once(recover_running: bool = False) -> None:
    settings = get_settings()
    async with AsyncSessionLocal() as session:
        counters = await CatalogFetcher(settings, session).scrape_once(
            recover_running=recover_running
        )
    logger.info("scrape_finished", **counters.__dict__)


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
        logger.info("startup_scrape_scheduled")
        asyncio.create_task(scrape_once(recover_running=True))
    await asyncio.Event().wait()


def main() -> None:
    configure_logging()
    parser = argparse.ArgumentParser(description="Batch Downloader scraper worker")
    parser.add_argument("command", choices=("scrape-once", "scheduler"))
    args = parser.parse_args()
    if args.command == "scrape-once":
        asyncio.run(scrape_once())
    else:
        asyncio.run(run_scheduler())


if __name__ == "__main__":
    main()
