from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.core.config import Settings
from app.scraper.catalog_fetcher import CatalogFetcher


class FakeSession:
    def __init__(self) -> None:
        self.commits = 0
        self.rollbacks = 0

    async def commit(self) -> None:
        self.commits += 1

    async def rollback(self) -> None:
        self.rollbacks += 1


class FakeRuns:
    def __init__(self) -> None:
        self.run = SimpleNamespace(id=uuid4())
        self.finished = None

    async def recover_running(self, _error_summary: str) -> int:
        return 0

    async def acquire(self):
        return self.run

    async def heartbeat(self, *_args, **_kwargs) -> None:
        return None

    async def set_current(self, *_args, **_kwargs) -> None:
        return None

    async def next_pending_command(self):
        return None

    async def finish(self, run_id, status, **counters) -> None:
        self.finished = (run_id, status, counters)


class FakeCatalog:
    def __init__(self, should_scrape_by_package: dict[str, bool]) -> None:
        self.should_scrape_by_package = should_scrape_by_package
        self.checked = []

    async def should_scrape_winstall_package(self, package_id: str) -> bool:
        self.checked.append(package_id)
        return self.should_scrape_by_package[package_id]


class FakeWinstallClient:
    package_ids = [
        "Already.Available",
        "New.App",
        "Needs.Review",
        "Missing.Installer",
    ]

    def __init__(self, *_args, **_kwargs) -> None:
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args) -> None:
        return None

    async def iter_apps(self):
        for package_id in self.package_ids:
            yield SimpleNamespace(package_id=package_id)


@pytest.mark.asyncio
async def test_scrape_once_skips_existing_packages(monkeypatch) -> None:
    session = FakeSession()
    fetcher = CatalogFetcher.__new__(CatalogFetcher)
    fetcher.settings = Settings(scrape_max_apps=0, llm_enrich_interval_apps=0)
    fetcher.session = session
    fetcher.runs = FakeRuns()
    fetcher.catalog = FakeCatalog(
        {
            "Already.Available": False,
            "New.App": True,
            "Needs.Review": False,
            "Missing.Installer": False,
        }
    )
    scraped = []
    enriched = []

    async def scrape_single_app(_winstall, package_id, _run_id):
        scraped.append(package_id)
        return SimpleNamespace(app_id=uuid4(), resolved=True)

    async def enrich_descriptions(run_id, software_app_ids=None):
        enriched.append((run_id, software_app_ids))
        return 0

    fetcher._scrape_single_app = scrape_single_app
    fetcher._enrich_descriptions = enrich_descriptions

    monkeypatch.setattr("app.scraper.catalog_fetcher.WinstallClient", FakeWinstallClient)

    counters = await fetcher.scrape_once()

    assert fetcher.catalog.checked == FakeWinstallClient.package_ids
    assert scraped == ["New.App"]
    assert len(enriched) == 1
    assert len(enriched[0][1]) == 1
    assert counters.apps_discovered == 1
    assert counters.apps_resolved == 1
    assert counters.apps_failed == 0
    assert counters.apps_skipped == 3


@pytest.mark.asyncio
async def test_scrape_once_does_not_enrich_when_all_packages_exist(monkeypatch) -> None:
    session = FakeSession()
    fetcher = CatalogFetcher.__new__(CatalogFetcher)
    fetcher.settings = Settings(scrape_max_apps=0, llm_enrich_interval_apps=0)
    fetcher.session = session
    fetcher.runs = FakeRuns()
    fetcher.catalog = FakeCatalog(
        {package_id: False for package_id in FakeWinstallClient.package_ids}
    )

    async def scrape_single_app(_winstall, package_id, _run_id):
        raise AssertionError(f"existing package should not be scraped: {package_id}")

    async def enrich_descriptions(_run_id):
        raise AssertionError("existing-only scrape should not enrich descriptions")

    fetcher._scrape_single_app = scrape_single_app
    fetcher._enrich_descriptions = enrich_descriptions

    monkeypatch.setattr("app.scraper.catalog_fetcher.WinstallClient", FakeWinstallClient)

    counters = await fetcher.scrape_once()

    assert fetcher.catalog.checked == FakeWinstallClient.package_ids
    assert counters.apps_discovered == 0
    assert counters.apps_resolved == 0
    assert counters.apps_failed == 0
    assert counters.apps_skipped == len(FakeWinstallClient.package_ids)
