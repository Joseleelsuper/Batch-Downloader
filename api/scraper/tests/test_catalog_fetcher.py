"""Contiene las pruebas de `test_catalog_fetcher`.
"""
import asyncio
from types import SimpleNamespace
from uuid import uuid4

import pytest
from sqlalchemy.exc import OperationalError

import app.scraper.catalog_fetcher as catalog_fetcher
from app.core.config import Settings
from app.core.time import utc_now
from app.db.enums import ResolutionStatus
from app.scraper.candidates import InstallerCandidate, infer_operating_system
from app.scraper.catalog_fetcher import (
    CatalogFetcher,
    FilterWorker,
    PipelineRuntime,
    PlatformScraperWorker,
    SearcherWorker,
    ValidInstaller,
    dedupe_valid_installers,
    fallback_candidates,
    first_task_failure,
    infer_validated_operating_system,
    is_actionable_installer_candidate,
    is_stale_control_command,
    is_transient_mysql_lock_error,
    is_windows_winstall_archive,
    known_official_candidates,
    rank_installers,
    should_collect_official_installers,
    use_only_known_official_candidates,
    use_winstall_fallback_only,
    validated_installer_version,
    winstall_parent_index_url,
)
from app.scraper.validator import ValidationResult


def test_fallback_candidates_include_winstall_api_and_page_links() -> None:
    """Comprueba el escenario `fallback_candidates_include_winstall_api_and_page_links`.
    """
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
    """Comprueba el escenario `rank_installers_marks_latest_per_platform_architecture`.
    """
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
    """Comprueba el escenario `rank_installers_prefers_direct_over_fallback_for_same_version`.
    """
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


def test_dedupes_redirect_variants_that_only_change_query_parameters() -> None:
    """Comprueba el escenario `dedupes_redirect_variants_that_only_change_query_parameters`.
    """
    first = valid(
        "https://cdn.example.com/launcher/App-1.2.3.exe?token=one",
        "windows",
        "x86_64",
        "1.2.3",
        80,
    )
    second = valid(
        "https://cdn.example.com/launcher/App-1.2.3.exe?token=two",
        "windows",
        "x86_64",
        "1.2.3",
        100,
    )

    deduped = dedupe_valid_installers([first, second])

    assert deduped == [second]


def test_validated_version_prefers_the_final_binary_name() -> None:
    """Comprueba el escenario `validated_version_prefers_the_final_binary_name`.
    """
    candidate = InstallerCandidate(
        url="https://warthunder.com/download/launcherPC/",
        source="attribute:onclick",
    )
    result = ValidationResult(
        ok=True,
        url=candidate.url,
        final_url="https://cdn.example.com/wt_launcher_1.0.3.535.exe?distr=abc123",
        filename="wt_launcher_1.0.3.535.exe",
        extension=".exe",
    )

    assert validated_installer_version(candidate, result) == "1.0.3.535"


def test_validated_operating_system_uses_validation_extension_first() -> None:
    """Comprueba el escenario `validated_operating_system_uses_validation_extension_first`.
    """
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
    """Comprueba el escenario `validated_tar_gz_uses_filename_tokens_before_defaulting_to_linux`.
    """
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
    """Comprueba el escenario `winstall_zip_defaults_to_windows_when_platform_is_not_in_filename`.
    """
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


def test_matching_official_zip_defaults_to_windows_for_winstall_catalog() -> None:
    """Comprueba el escenario `matching_official_zip_defaults_to_windows_for_winstall_catalog`.
    """
    candidate = InstallerCandidate(
        url="https://www.proscan.org/ProScan_24_10.zip",
        source="href",
        label="Download ProScan 24.10",
        asset_kind="archive",
        match_tokens=("proscan",),
    )

    assert is_windows_winstall_archive(candidate)
    assert infer_validated_operating_system(
        candidate,
        ValidationResult(
            ok=True,
            url=candidate.url,
            final_url=candidate.url,
            extension=".zip",
            filename="ProScan_24_10.zip",
        ),
    ) == "windows"


def test_winstall_parent_index_uses_directory_containing_versioned_file() -> None:
    """Comprueba el escenario `winstall_parent_index_uses_directory_containing_versioned_file`.
    """
    assert winstall_parent_index_url(
        "https://xpra.org/stable/windows/Xpra-x86_64_Setup_6.3.2-r0.exe"
    ) == "https://xpra.org/stable/windows/"
    assert winstall_parent_index_url(
        "https://sourceforge.net/project/app/1.0/AppSetup.exe/download"
    ) == "https://sourceforge.net/project/app/1.0/"
    assert winstall_parent_index_url(
        "https://github.com/vendor/app/releases/download/v1/AppSetup.exe"
    ) is None


@pytest.mark.asyncio
async def test_platform_worker_includes_parent_index_fallback(monkeypatch) -> None:
    """Comprueba el escenario `platform_worker_includes_parent_index_fallback`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    worker = PlatformScraperWorker(Settings())
    stale = InstallerCandidate(
        url="https://downloads.example.com/app/App-1.0.exe",
        source="winstall_page",
        asset_kind="winstall_download",
    )
    current = InstallerCandidate(
        url="https://downloads.example.com/app/App-2.0.exe",
        source="winstall_parent_index",
        asset_kind="winstall_download",
    )

    async def collect_parent(_candidates):
        """Ejecuta la operación `collect_parent`.

        Args:
            _candidates (Any): Valor de `_candidates` utilizado por la operación.
        """
        return [current]

    monkeypatch.setattr(worker, "_collect_winstall_parent_index_candidates", collect_parent)

    result = await worker._collect_winstall_github_candidates(
        SimpleNamespace(latest_version="2.0"),
        [stale],
    )

    assert result == [current]


def test_epic_games_launcher_has_known_official_candidate() -> None:
    """Comprueba el escenario `epic_games_launcher_has_known_official_candidate`.
    """
    app = SimpleNamespace(package_id="EpicGames.EpicGamesLauncher")

    candidates = known_official_candidates(app)

    assert candidates[0].source == "official_known_endpoint"
    assert candidates[0].url.endswith("EpicGamesLauncherInstaller.exe")


def test_itch_has_known_official_cross_platform_installers() -> None:
    """Comprueba el escenario `itch_has_known_official_cross_platform_installers`.
    """
    app = SimpleNamespace(package_id="ItchIo.Itch")

    candidates = known_official_candidates(app)

    assert [candidate.url for candidate in candidates] == [
        "https://itch.io/app/download?platform=windows",
        "https://itch.io/app/download?platform=osx",
        "https://itch.io/app/download?platform=linux",
    ]
    assert [infer_operating_system(candidate) for candidate in candidates] == [
        "windows",
        "macos",
        "linux",
    ]
    assert all(candidate.asset_kind == "installer" for candidate in candidates)
    assert use_only_known_official_candidates(app, candidates)


def test_115_browser_has_known_cross_platform_official_candidates() -> None:
    """Comprueba el escenario `115_browser_has_known_cross_platform_official_candidates`.
    """
    app = SimpleNamespace(package_id="115.115Chrome", latest_version="36.0.0")

    candidates = known_official_candidates(app)

    urls = {candidate.url for candidate in candidates}
    assert "https://down.115.com/client/win/115br_v36.0.0_x64.exe" in urls
    assert "https://down.115.com/client/mac/115br_v36.0.0_arm64.dmg" in urls
    assert "https://down.115.com/client/115pc/lin/115br_v36.0.0.deb" in urls
    assert use_only_known_official_candidates(app, candidates)


def test_123pan_has_versioned_official_windows_installer() -> None:
    """Comprueba el escenario `123pan_has_versioned_official_windows_installer`.
    """
    app = SimpleNamespace(package_id="123.123pan", latest_version="3.2.0.0")

    candidates = known_official_candidates(app)

    assert [candidate.url for candidate in candidates] == [
        "https://app.123957.com/pc-pro/windows/320/123pan_3.2.0.exe"
    ]
    assert use_only_known_official_candidates(app, candidates)


def test_known_official_endpoint_bypasses_an_unavailable_marketing_page() -> None:
    """Comprueba el escenario `known_official_endpoint_bypasses_an_unavailable_marketing_page`.
    """
    app = SimpleNamespace(package_id="123.123pan", latest_version="3.2.0")

    assert should_collect_official_installers(
        app,
        "https://www.123pan.com/",
        use_official=False,
        fallback=[],
    )


def test_known_heavy_pages_can_use_winstall_fallback_only() -> None:
    """Comprueba el escenario `known_heavy_pages_can_use_winstall_fallback_only`.
    """
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


def test_javascript_download_control_does_not_suppress_browser_fallback() -> None:
    """Comprueba el escenario `javascript_download_control_does_not_suppress_browser_fallback`.
    """
    javascript_control = InstallerCandidate(
        url="javascript:;",
        source="href",
        label="Descargar el juego",
    )
    installer = InstallerCandidate(
        url="https://downloads.example.com/AppSetup.exe",
        source="href",
    )

    assert not is_actionable_installer_candidate(javascript_control)
    assert is_actionable_installer_candidate(installer)


@pytest.mark.asyncio
async def test_malformed_candidate_does_not_abort_candidate_group() -> None:
    """Comprueba el escenario `malformed_candidate_does_not_abort_candidate_group`.
    """
    worker = PlatformScraperWorker(Settings())
    app = SimpleNamespace(
        name="Example App",
        package_id="Vendor.Example",
        publisher="Vendor",
        latest_version="1.0.0",
    )

    valid_installers, diagnostics = await worker._validate_candidate_group(
        app,
        [InstallerCandidate(url="https://[broken/AppSetup.exe", source="href")],
        ResolutionStatus.DIRECT,
        max_candidates=5,
        max_valid=5,
    )

    assert valid_installers == []
    assert diagnostics.errors == {"ValueError": 1}


def test_mysql_deadlocks_and_lock_timeouts_are_transient() -> None:
    """Comprueba el escenario `mysql_deadlocks_and_lock_timeouts_are_transient`.
    """
    deadlock = OperationalError("UPDATE", {}, Exception(1213, "Deadlock"))
    lock_timeout = OperationalError("UPDATE", {}, Exception(1205, "Lock wait timeout"))
    connection_error = OperationalError("UPDATE", {}, Exception(2003, "Connection failed"))

    assert is_transient_mysql_lock_error(deadlock)
    assert is_transient_mysql_lock_error(lock_timeout)
    assert not is_transient_mysql_lock_error(connection_error)


def test_task_group_error_is_unwrapped_to_its_actionable_cause() -> None:
    """Comprueba el escenario `task_group_error_is_unwrapped_to_its_actionable_cause`.
    """
    root_cause = RuntimeError("worker failed")
    grouped = BaseExceptionGroup(
        "pipeline failed",
        [asyncio.CancelledError(), ExceptionGroup("worker", [root_cause])],
    )

    assert first_task_failure(grouped) is root_cause


@pytest.mark.asyncio
async def test_direct_and_fallback_candidates_are_validated_concurrently() -> None:
    """Comprueba el escenario `direct_and_fallback_candidates_are_validated_concurrently`.
    """
    worker = PlatformScraperWorker(Settings(request_timeout_seconds=1))
    app = SimpleNamespace(
        package_id="Vendor.App",
        name="Vendor App",
        publisher="Vendor",
        latest_version="1.0.0",
    )

    class SlowValidator:
        """Agrupa los escenarios de prueba de `SlowValidator`.
        """
        async def validate(self, candidate: InstallerCandidate) -> ValidationResult:
            """Ejecuta `validate` dentro de `SlowValidator`.

            Args:
                candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.

            Returns:
                ValidationResult: Resultado producido por la operación.
            """
            await asyncio.sleep(0.15)
            return ValidationResult(
                ok=True,
                url=candidate.url,
                final_url=candidate.url,
                final_domain="example.com",
                filename=candidate.url.rsplit("/", 1)[-1],
                extension=candidate.extension,
                content_type="application/octet-stream",
            )

    worker.validator = SlowValidator()
    loop = asyncio.get_running_loop()
    started = loop.time()
    installers, diagnostics = await worker._validate_installers(
        app=app,
        official_url="https://example.com",
        direct_candidates=[
            InstallerCandidate(
                url="https://example.com/Vendor-App.exe",
                source="href",
                label="Download",
            )
        ],
        fallback_candidates=[
            InstallerCandidate(
                url="https://cdn.example.com/Vendor-App.msi",
                source="winstall_page",
                label="Download",
                asset_kind="winstall_download",
            )
        ],
    )
    elapsed = loop.time() - started

    assert elapsed < 0.25
    assert len(installers) == 2
    assert diagnostics["direct"]["valid"] == 1
    assert diagnostics["fallback"]["valid"] == 1


@pytest.mark.asyncio
async def test_winstall_github_asset_refreshes_from_release_api() -> None:
    """Comprueba el escenario `winstall_github_asset_refreshes_from_release_api`.
    """
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
        """Ejecuta la operación `collect`.

        Args:
            url (str): URL del recurso que debe procesarse.
            version (str | None): Valor de `version` utilizado por la operación.

        Returns:
            list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
        """
        assert url == stale.url
        assert version == "40"
        return [refreshed_asset]

    worker = PlatformScraperWorker(Settings())
    worker.github = SimpleNamespace(collect=collect)

    candidates = await worker._collect_winstall_github_candidates(app, [stale])

    assert [candidate.url for candidate in candidates] == [refreshed_asset.url]
    assert candidates[0].source == "winstall_github_release_api"


@pytest.mark.asyncio
async def test_winstall_github_refresh_queries_each_repository_once() -> None:
    """Comprueba el escenario `winstall_github_refresh_queries_each_repository_once`.
    """
    app = SimpleNamespace(package_id="AdGuard.dnsproxy", latest_version="0.82.1")
    candidates = [
        InstallerCandidate(
            url=(
                "https://github.com/AdguardTeam/dnsproxy/releases/download/v0.82.1/"
                f"dnsproxy-windows-{architecture}-v0.82.1.zip"
            ),
            source="winstall_api",
            asset_kind="winstall_download",
        )
        for architecture in ("386", "amd64", "arm64")
    ]
    calls = []
    worker = PlatformScraperWorker(Settings())

    async def collect(url: str, version: str | None) -> list[InstallerCandidate]:
        """Ejecuta la operación `collect`.

        Args:
            url (str): URL del recurso que debe procesarse.
            version (str | None): Valor de `version` utilizado por la operación.

        Returns:
            list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
        """
        calls.append((url, version))
        return []

    worker.github = SimpleNamespace(collect=collect)

    assert await worker._collect_winstall_github_candidates(app, candidates) == []
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_filter_validates_current_winstall_asset_before_refreshing_github() -> None:
    """Comprueba el escenario `filter_validates_current_winstall_asset_before_refreshing_github`.
    """
    app = SimpleNamespace(
        package_id="AdGuard.dnsproxy",
        name="DNS Proxy",
        publisher="AdGuard",
        latest_version="0.82.1",
        versions=[],
    )
    current = (
        "https://github.com/AdguardTeam/dnsproxy/releases/download/v0.82.1/"
        "dnsproxy-windows-amd64-v0.82.1.zip"
    )
    worker = FilterWorker(Settings())

    class ValidCurrentAsset:
        """Agrupa los escenarios de prueba de `ValidCurrentAsset`.
        """
        async def validate(self, candidate: InstallerCandidate) -> ValidationResult:
            """Ejecuta `validate` dentro de `ValidCurrentAsset`.

            Args:
                candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.

            Returns:
                ValidationResult: Resultado producido por la operación.
            """
            return ValidationResult(ok=candidate.url == current, url=candidate.url)

    async def unexpected_refresh(*_args, **_kwargs):
        """Ejecuta la operación `unexpected_refresh`.

        Args:
            *_args (Any): Valor de `_args` utilizado por la operación.
            **_kwargs (Any): Valor de `_kwargs` utilizado por la operación.

        Throws:
            AssertionError: Si no puede completarse la operación bajo las condiciones requeridas.
        """
        raise AssertionError("A valid Winstall asset must not trigger a GitHub refresh")

    worker.validator = ValidCurrentAsset()
    worker.github = SimpleNamespace(collect=unexpected_refresh)

    assert await worker._fallback_download_valid(
        {"winstall_downloads": [{"url": current, "label": "Download (.zip)"}]},
        app,
    )


@pytest.mark.asyncio
async def test_filter_uses_refreshed_winstall_github_release_before_discarding() -> None:
    """Comprueba el escenario `filter_uses_refreshed_winstall_github_release_before_discarding`.
    """
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
        """Ejecuta la operación `collect`.

        Args:
            _url (str): Dirección de `` que debe procesarse.
            _version (str | None): Valor de `_version` utilizado por la operación.

        Returns:
            list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
        """
        return [
            InstallerCandidate(
                url=refreshed,
                source="github_release_api",
                asset_kind="installer",
            )
        ]

    class FakeValidator:
        """Agrupa los escenarios de prueba de `FakeValidator`.
        """
        async def validate(self, candidate: InstallerCandidate) -> ValidationResult:
            """Ejecuta `validate` dentro de `FakeValidator`.

            Args:
                candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.

            Returns:
                ValidationResult: Resultado producido por la operación.
            """
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
    """Comprueba el escenario `stale_control_command_rejects_only_old_control_commands`.
    """
    run_started_at = utc_now()
    old_pause = SimpleNamespace(command="pause", created_at=run_started_at.replace(year=2025))
    old_run_once = SimpleNamespace(command="run_once", created_at=run_started_at.replace(year=2025))
    fresh_stop = SimpleNamespace(command="stop", created_at=run_started_at)

    assert is_stale_control_command(old_pause, run_started_at)
    assert not is_stale_control_command(old_run_once, run_started_at)
    assert not is_stale_control_command(fresh_stop, run_started_at)


@pytest.mark.asyncio
async def test_searcher_backpressure_waits_until_queue_depth_drops(monkeypatch) -> None:
    """Comprueba el escenario `searcher_backpressure_waits_until_queue_depth_drops`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    depths = [3, 0]
    phases = []

    class FakePipeline:
        """Agrupa los escenarios de prueba de `FakePipeline`.
        """
        def __init__(self, _session) -> None:
            """Inicializa una instancia de `FakePipeline`.

            Args:
                _session (Any): Valor de `_session` utilizado por la operación.
            """
            pass

        async def queue_depth(self, _queue: str) -> int:
            """Ejecuta `queue_depth` dentro de `FakePipeline`.

            Args:
                _queue (str): Valor de `_queue` utilizado por la operación.

            Returns:
                int: Resultado producido por la operación.
            """
            return depths.pop(0)

    class FakeSession:
        """Agrupa los escenarios de prueba de `FakeSession`.
        """
        async def __aenter__(self):
            """Abre el contexto asíncrono y devuelve la instancia preparada.
            """
            return self

        async def __aexit__(self, *_args):
            """Cierra el contexto asíncrono y libera sus recursos.

            Args:
                *_args (Any): Valor de `_args` utilizado por la operación.
            """
            return None

    async def fake_set_current(_settings, _run_id, _package_id, _app_name, phase):
        """Ejecuta la operación `fake_set_current`.

        Args:
            _settings (Any): Valor de `_settings` utilizado por la operación.
            _run_id (Any): Identificador de `_run` utilizado por la operación.
            _package_id (Any): Identificador de `_package` utilizado por la operación.
            _app_name (Any): Valor de `_app_name` utilizado por la operación.
            phase (Any): Valor de `phase` utilizado por la operación.
        """
        phases.append(phase)

    async def fake_sleep(_seconds):
        """Ejecuta la operación `fake_sleep`.

        Args:
            _seconds (Any): Valor de `_seconds` utilizado por la operación.
        """
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


@pytest.mark.asyncio
async def test_catalog_fetcher_starts_configured_scraper_workers(monkeypatch) -> None:
    """Comprueba el escenario `catalog_fetcher_starts_configured_scraper_workers`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    started = []

    class FakeScraperWorker:
        """Agrupa los escenarios de prueba de `FakeScraperWorker`.
        """
        def __init__(self, _settings) -> None:
            """Inicializa una instancia de `FakeScraperWorker`.

            Args:
                _settings (Any): Valor de `_settings` utilizado por la operación.
            """
            self.index = len(started)
            """Estado de instancia asociado a `index`.
            """
            started.append(self.index)

        async def run(self, _runtime) -> None:
            """Ejecuta `run` dentro de `FakeScraperWorker`.

            Args:
                _runtime (Any): Valor de `_runtime` utilizado por la operación.
            """
            await asyncio.sleep(0)

    monkeypatch.setattr(catalog_fetcher, "PlatformScraperWorker", FakeScraperWorker)
    settings = Settings(scrape_concurrency=3)
    runtime = PipelineRuntime(settings=settings, run_id=uuid4(), run_started_at=utc_now())
    fetcher = CatalogFetcher(settings, SimpleNamespace())

    await fetcher._run_scraper_workers(runtime)

    assert started == [0, 1, 2]
    assert runtime.scraper_done.is_set()


@pytest.mark.asyncio
async def test_platform_worker_retries_transient_claim_failure(monkeypatch) -> None:
    """Comprueba el escenario `platform_worker_retries_transient_claim_failure`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    calls = 0

    async def fake_claim(*_args, **_kwargs):
        """Ejecuta la operación `fake_claim`.

        Args:
            *_args (Any): Valor de `_args` utilizado por la operación.
            **_kwargs (Any): Valor de `_kwargs` utilizado por la operación.

        Throws:
            OperationalError: Si no puede completarse la operación bajo las condiciones requeridas.
        """
        nonlocal calls
        calls += 1
        if calls == 1:
            raise OperationalError("SELECT", {}, Exception(1213, "Deadlock"))
        return None

    async def fake_sleep(_seconds):
        """Ejecuta la operación `fake_sleep`.

        Args:
            _seconds (Any): Valor de `_seconds` utilizado por la operación.
        """
        return None

    monkeypatch.setattr(catalog_fetcher, "claim_item", fake_claim)
    monkeypatch.setattr(catalog_fetcher.asyncio, "sleep", fake_sleep)
    settings = Settings()
    runtime = PipelineRuntime(settings=settings, run_id=uuid4(), run_started_at=utc_now())
    runtime.searcher_done.set()
    runtime.filter_done.set()

    await PlatformScraperWorker(settings).run(runtime)

    assert calls == 2


def valid(
    url: str,
    os: str,
    arch: str,
    version: str,
    score: int,
    *,
    status: ResolutionStatus = ResolutionStatus.DIRECT,
) -> ValidInstaller:
    """Ejecuta la operación `valid`.

    Args:
        url (str): URL del recurso que debe procesarse.
        os (str): Valor de `os` utilizado por la operación.
        arch (str): Valor de `arch` utilizado por la operación.
        version (str): Valor de `version` utilizado por la operación.
        score (int): Valor de `score` utilizado por la operación.
        status (ResolutionStatus): Valor de `status` utilizado por la operación.

    Returns:
        ValidInstaller: Resultado producido por la operación.
    """
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
