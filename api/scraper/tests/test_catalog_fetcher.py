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
    FilterWorker,
    PlatformScraperWorker,
    SearcherWorker,
    ValidInstaller,
    fallback_candidates,
    infer_validated_operating_system,
    is_windows_winstall_archive,
    is_stale_control_command,
    known_official_candidates,
    rank_installers,
    should_collect_official_installers,
    use_only_known_official_candidates,
    use_winstall_fallback_only,
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


def test_rank_installers_prefers_direct_over_fallback_for_same_version() -> None:
    direct = valid(
        "https://github.com/vendor/app/releases/download/2.0.0/App.exe",
        "windows",
        "x86_64",
        "2.0.0",
        100,
        status=ResolutionStatus.DIRECT,
    )
    fallback = valid(
        "https://winstall.example/App.exe",
        "windows",
        "x86_64",
        "2.0.0",
        200,
        status=ResolutionStatus.FALLBACK,
    )

    ranked = rank_installers([fallback, direct])

    assert ranked[0] == (direct, 0, True)
    assert ranked[1] == (fallback, 1, False)


def test_validated_operating_system_uses_validation_extension_first() -> None:
    candidate = InstallerCandidate(
        url="https://store.steampowered.com/about/",
        source="href",
        label="Download Steam",
    )

    assert infer_validated_operating_system(
        candidate,
        ValidationResult(
            ok=True,
            url=candidate.url,
            final_url="https://cdn.akamai.steamstatic.com/client/installer/steam.dmg",
            extension=".dmg",
            filename="steam.dmg",
        ),
    ) == "macos"
    assert infer_validated_operating_system(
        candidate,
        ValidationResult(
            ok=True,
            url=candidate.url,
            final_url="https://repo.steampowered.com/steam/archive/steam_latest.deb",
            extension=".deb",
            filename="steam_latest.deb",
        ),
    ) == "linux"


def test_validated_tar_gz_uses_filename_tokens_before_defaulting_to_linux() -> None:
    candidate = InstallerCandidate(
        url="https://github.com/vendor/app/releases/download/1.0.0/app-macos.tar.gz",
        source="github_release_expanded_assets",
        label="app-macos.tar.gz",
    )

    assert infer_validated_operating_system(
        candidate,
        ValidationResult(
            ok=True,
            url=candidate.url,
            final_url=candidate.url,
            extension=".tar.gz",
            filename="app-macos.tar.gz",
        ),
    ) == "macos"


def test_winstall_zip_defaults_to_windows_when_platform_is_not_in_filename() -> None:
    candidate = InstallerCandidate(
        url="https://github.com/86Box/86BoxManager/releases/download/1.7.4/86BoxManager_1.7.4.zip",
        source="winstall_page",
        label="Download (.zip)",
        asset_kind="winstall_download",
    )

    assert is_windows_winstall_archive(candidate)
    assert infer_validated_operating_system(
        candidate,
        ValidationResult(
            ok=True,
            url=candidate.url,
            final_url=candidate.url,
            extension=".zip",
            filename="86BoxManager_1.7.4.zip",
        ),
    ) == "windows"


def test_epic_games_launcher_has_known_official_candidate() -> None:
    app = SimpleNamespace(package_id="EpicGames.EpicGamesLauncher")

    candidates = known_official_candidates(app)

    assert candidates[0].source == "official_known_endpoint"
    assert candidates[0].url.endswith("EpicGamesLauncherInstaller.exe")


def test_115_browser_has_known_cross_platform_official_candidates() -> None:
    app = SimpleNamespace(package_id="115.115Chrome", latest_version="36.0.0")

    candidates = known_official_candidates(app)

    urls = {candidate.url for candidate in candidates}
    assert "https://down.115.com/client/win/115br_v36.0.0_x64.exe" in urls
    assert "https://down.115.com/client/mac/115br_v36.0.0_arm64.dmg" in urls
    assert "https://down.115.com/client/115pc/lin/115br_v36.0.0.deb" in urls
    assert use_only_known_official_candidates(app, candidates)


def test_123pan_has_versioned_official_windows_installer() -> None:
    app = SimpleNamespace(package_id="123.123pan", latest_version="3.2.0.0")

    candidates = known_official_candidates(app)

    assert [candidate.url for candidate in candidates] == [
        "https://app.123957.com/pc-pro/windows/320/123pan_3.2.0.exe"
    ]
    assert use_only_known_official_candidates(app, candidates)


def test_known_official_endpoint_bypasses_an_unavailable_marketing_page() -> None:
    app = SimpleNamespace(package_id="123.123pan", latest_version="3.2.0")

    assert should_collect_official_installers(
        app,
        "https://www.123pan.com/",
        use_official=False,
        fallback=[],
    )


def test_known_heavy_pages_can_use_winstall_fallback_only() -> None:
    app = SimpleNamespace(package_id="360.360SE")
    fallback = [
        InstallerCandidate(
            url="https://down.360safe.com/se/360se16.1.2000.64.exe",
            source="winstall_page",
            asset_kind="winstall_download",
        )
    ]

    assert use_winstall_fallback_only(app, fallback)
    assert not use_winstall_fallback_only(app, [])


@pytest.mark.asyncio
async def test_winstall_github_asset_refreshes_from_release_api() -> None:
    app = SimpleNamespace(package_id="Coloryr.ColorMC", latest_version="40")
    stale = InstallerCandidate(
        url="https://github.com/Coloryr/ColorMC/releases/download/old/ColorMC.exe",
        source="winstall_page",
        asset_kind="winstall_download",
    )
    refreshed_asset = InstallerCandidate(
        url="https://github.com/Coloryr/ColorMC/releases/download/v40/ColorMC-Setup.exe",
        source="github_release_api",
        label="ColorMC-Setup.exe",
        asset_kind="installer",
    )

    async def collect(url: str, version: str | None) -> list[InstallerCandidate]:
        assert url == stale.url
        assert version == "40"
        return [refreshed_asset]

    worker = PlatformScraperWorker(Settings())
    worker.github = SimpleNamespace(collect=collect)

    candidates = await worker._collect_winstall_github_candidates(app, [stale])

    assert [candidate.url for candidate in candidates] == [refreshed_asset.url]
    assert candidates[0].source == "winstall_github_release_api"


@pytest.mark.asyncio
async def test_filter_uses_refreshed_winstall_github_release_before_discarding() -> None:
    app = SimpleNamespace(
        package_id="Coloryr.ColorMC",
        name="ColorMC",
        publisher="Coloryr",
        latest_version="40",
        versions=[],
    )
    stale = "https://github.com/Coloryr/ColorMC/releases/download/old/ColorMC.exe"
    refreshed = "https://github.com/Coloryr/ColorMC/releases/download/v40/ColorMC-Setup.exe"
    worker = FilterWorker(Settings())

    async def collect(_url: str, _version: str | None) -> list[InstallerCandidate]:
        return [
            InstallerCandidate(
                url=refreshed,
                source="github_release_api",
                asset_kind="installer",
            )
        ]

    class FakeValidator:
        async def validate(self, candidate: InstallerCandidate) -> ValidationResult:
            return ValidationResult(ok=candidate.url == refreshed, url=candidate.url)

    worker.github = SimpleNamespace(collect=collect)
    worker.validator = FakeValidator()

    assert await worker._fallback_download_valid(
        {
            "winstall_downloads": [
                {"url": stale, "label": "Download (.exe)"},
            ]
        },
        app,
    )


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


def valid(
    url: str,
    os: str,
    arch: str,
    version: str,
    score: int,
    *,
    status: ResolutionStatus = ResolutionStatus.DIRECT,
) -> ValidInstaller:
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
        status=status,
        operating_system=os,
        architecture=arch,
        version=version,
    )
