from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.core.config import Settings
from app.db.enums import ResolutionStatus
from app.scraper.candidates import InstallerCandidate
from app.scraper.resolver import InstallerResolver
from app.scraper.validator import ValidationResult


class FakeCatalog:
    def __init__(self):
        self.saved = []

    async def expire_valid_resolved_sources(self, _source_id):
        return None

    async def mark_source_status(self, *_args, **_kwargs):
        return None

    async def save_resolved_source(self, item):
        self.saved.append(item)


class FakeLogs:
    async def add(self, *_args, **_kwargs):
        return None


class FakeValidator:
    async def validate(self, candidate, _allowed_domains):
        if "latest" in candidate.url:
            return ValidationResult(
                ok=True,
                url=candidate.url,
                final_url=candidate.url,
                final_domain="github.com",
                filename="App-latest.exe",
                extension=".exe",
                content_type="application/octet-stream",
                size_bytes=1024,
            )
        return ValidationResult(ok=False, url=candidate.url, reason="http_404")


@pytest.mark.asyncio
async def test_github_official_url_does_not_fall_back_to_generic_page_parser(monkeypatch) -> None:
    resolver = InstallerResolver(Settings(), FakeCatalog(), FakeLogs(), FakeValidator())
    source = SimpleNamespace(id=uuid4(), initial_url=None, allowed_domains=[])
    app = SimpleNamespace(
        homepage="https://github.com/ali50m/AddCurrentPath",
        package_id="ali50m.AddCurrentPath",
        name="Add Current Path",
        publisher="ali50m",
        latest_version="1.0.0",
    )

    async def github_releases(*_args, **_kwargs):
        return ResolutionStatus.REQUIRES_MANUAL_REVIEW

    async def generic_page(*_args, **_kwargs):
        raise AssertionError("GitHub repo pages must not use the generic resolver")

    async def winstall_fallback(*_args, **_kwargs):
        return ResolutionStatus.REQUIRES_MANUAL_REVIEW

    monkeypatch.setattr(resolver, "_resolve_github_releases", github_releases)
    monkeypatch.setattr(resolver, "_resolve_official_page", generic_page)
    monkeypatch.setattr(resolver, "_resolve_winstall_fallback", winstall_fallback)

    status = await resolver.resolve(source, app)

    assert status == ResolutionStatus.REQUIRES_MANUAL_REVIEW


@pytest.mark.asyncio
async def test_winstall_github_asset_404_retries_latest_release(monkeypatch) -> None:
    catalog = FakeCatalog()
    resolver = InstallerResolver(Settings(), catalog, FakeLogs(), FakeValidator())
    source_id = uuid4()
    app = SimpleNamespace(
        versions=[
            SimpleNamespace(
                version="1.0.0",
                installer_type="exe",
                installers=[
                    "https://github.com/vendor/app/releases/download/v1.0.0/App-old.exe"
                ],
            )
        ],
        package_id="Vendor.App",
        name="Vendor App",
        publisher="Vendor",
        latest_version="1.0.0",
    )

    class FakeWinstallClient:
        def __init__(self, *_args, **_kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args):
            return None

        async def get_downloads(self, _package_id):
            return []

    async def collect_latest(_url):
        return [
            InstallerCandidate(
                url="https://github.com/vendor/app/releases/download/latest/App-latest.exe",
                source="github_release_api",
                label="App-latest.exe",
                asset_kind="installer",
            )
        ]

    monkeypatch.setattr("app.scraper.resolver.WinstallClient", FakeWinstallClient)
    monkeypatch.setattr(resolver.github, "collect", collect_latest)

    status = await resolver._resolve_winstall_fallback(source_id, app, {"github.com"})

    assert status == ResolutionStatus.FALLBACK
    assert catalog.saved[0].url.endswith("App-latest.exe")
    assert catalog.saved[0].metadata["candidate_source"] == "winstall_github_release_api"


@pytest.mark.asyncio
async def test_official_page_playwright_error_does_not_fail_app(monkeypatch) -> None:
    resolver = InstallerResolver(Settings(), FakeCatalog(), FakeLogs(), FakeValidator())
    app = SimpleNamespace(
        name="Vendor App",
        package_id="Vendor.App",
        publisher="Vendor",
        latest_version="1.0.0",
    )

    async def fetch_html(_url):
        return ""

    async def collect(_url):
        raise RuntimeError("browser failed")

    monkeypatch.setattr(resolver, "_fetch_html", fetch_html)
    monkeypatch.setattr(resolver.playwright, "collect", collect)

    status = await resolver._resolve_official_page(
        uuid4(),
        "https://example.com/download",
        {"example.com"},
        app,
    )

    assert status == ResolutionStatus.REQUIRES_MANUAL_REVIEW
