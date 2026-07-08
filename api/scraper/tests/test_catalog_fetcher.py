from types import SimpleNamespace
from uuid import uuid4

import pytest

import app.scraper.catalog_fetcher as catalog_fetcher
from app.core.config import Settings
from app.core.time import utc_now
from app.db.enums import ResolutionStatus
from app.scraper.candidates import InstallerCandidate
from app.scraper.catalog_fetcher import (
    PipelineRuntime,
    SearcherWorker,
    ValidInstaller,
    fallback_candidates,
    is_stale_control_command,
    rank_installers,
)
from app.scraper.validator import ValidationResult


def test_fallback_candidates_include_winstall_api_and_page_links() -> None:
    app = SimpleNamespace(
        versions=[
            SimpleNamespace(
                version="1.2.3",
                installer_type="msi",
                installers=["https://cdn.example.com/App-1.2.3.msi"],
            )
        ],
        name="Example App",
    )
    payload = {
        "winstall_downloads": [
            {
                "url": "https://cdn.example.com/App-1.2.3.msi",
                "label": "Download (.msi)",
                "context": "<a>Download</a>",
            },
            {
                "url": "https://cdn.example.com/App-1.2.3.dmg",
                "label": "Download (.dmg)",
            },
        ]
    }

    candidates = fallback_candidates(payload, app)

    assert [candidate.url for candidate in candidates] == [
        "https://cdn.example.com/App-1.2.3.msi",
        "https://cdn.example.com/App-1.2.3.dmg",
    ]
    assert candidates[0].asset_kind == "winstall_download"


def test_rank_installers_marks_latest_per_platform_architecture() -> None:
    installers = [
        valid("https://example.com/App-1.0.0.msi", "windows", "x86_64", "1.0.0", 90),
        valid("https://example.com/App-2.0.0.msi", "windows", "x86_64", "2.0.0", 80),
        valid("https://example.com/App-1.5.0-aarch64.dmg", "macos", "aarch64", "1.5.0", 70),
    ]

    ranked = rank_installers(installers)

    by_url = {installer.candidate.url: (rank, latest) for installer, rank, latest in ranked}
    assert by_url["https://example.com/App-2.0.0.msi"] == (0, True)
    assert by_url["https://example.com/App-1.0.0.msi"] == (1, False)
    assert by_url["https://example.com/App-1.5.0-aarch64.dmg"] == (0, True)


def test_stale_control_command_rejects_only_old_control_commands() -> None:
    run_started_at = utc_now()
    old_pause = SimpleNamespace(command="pause", created_at=run_started_at.replace(year=2025))
    old_run_once = SimpleNamespace(command="run_once", created_at=run_started_at.replace(year=2025))
    fresh_stop = SimpleNamespace(command="stop", created_at=run_started_at)

    assert is_stale_control_command(old_pause, run_started_at)
    assert not is_stale_control_command(old_run_once, run_started_at)
    assert not is_stale_control_command(fresh_stop, run_started_at)


@pytest.mark.asyncio
async def test_searcher_backpressure_waits_until_queue_depth_drops(monkeypatch) -> None:
    depths = [3, 0]
    phases = []

    class FakePipeline:
        def __init__(self, _session) -> None:
            pass

        async def queue_depth(self, _queue: str) -> int:
            return depths.pop(0)

    class FakeSession:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args):
            return None

    async def fake_set_current(_settings, _run_id, _package_id, _app_name, phase):
        phases.append(phase)

    async def fake_sleep(_seconds):
        return None

    monkeypatch.setattr(catalog_fetcher, "PipelineRepository", FakePipeline)
    monkeypatch.setattr(catalog_fetcher, "async_session_local", lambda: FakeSession)
    monkeypatch.setattr(catalog_fetcher, "set_current", fake_set_current)
    monkeypatch.setattr(catalog_fetcher.asyncio, "sleep", fake_sleep)

    settings = Settings(
        scrape_searcher_backpressure_limit=2,
        scrape_searcher_backpressure_sleep_seconds=0,
    )
    runtime = PipelineRuntime(settings=settings, run_id=uuid4(), run_started_at=utc_now())

    assert await SearcherWorker(settings)._wait_for_backpressure(runtime)
    assert phases == ["searcher_waiting_for_filter_backpressure"]
    assert depths == []


def valid(url: str, os: str, arch: str, version: str, score: int) -> ValidInstaller:
    candidate = InstallerCandidate(url=url, source="href", score=score, asset_kind="installer")
    return ValidInstaller(
        candidate=candidate,
        result=ValidationResult(
            ok=True,
            url=url,
            final_url=url,
            final_domain="example.com",
            filename=url.rsplit("/", 1)[-1],
            extension=candidate.extension,
        ),
        status=ResolutionStatus.DIRECT,
        operating_system=os,
        architecture=arch,
        version=version,
    )
