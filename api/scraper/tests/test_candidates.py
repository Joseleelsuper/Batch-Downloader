"""Contiene las pruebas de `test_candidates`.
"""
from app.scraper.candidates import (
    InstallerCandidate,
    detect_extension,
    elcomsoft_download_variant,
    extract_candidates,
    extract_version,
    https_upgrade_variant,
    infer_architecture,
    infer_operating_system,
    is_download_candidate,
    is_github_source_archive,
    s3_path_style_variant,
    score_candidate,
    sourceforge_mirror_variant,
)


def test_extract_candidates_from_links_and_scripts() -> None:
    """Comprueba el escenario `extract_candidates_from_links_and_scripts`.
    """
    html = """
    <a href="/downloads/app-x64.exe">Download Windows</a>
    <script>window.installer = "https://cdn.example.com/setup.msi";</script>
    """

    candidates = extract_candidates(html, "https://example.com/download")

    assert {candidate.url for candidate in candidates} == {
        "https://example.com/downloads/app-x64.exe",
        "https://cdn.example.com/setup.msi",
    }


def test_script_extraction_ignores_non_url_javascript_fragments() -> None:
    """Comprueba el escenario `script_extraction_ignores_non_url_javascript_fragments`.
    """
    candidates = extract_candidates(
        '<script>const broken = ").exe"; const real = "https://cdn.example.com/App.exe";</script>',
        "https://example.com",
    )

    assert [candidate.url for candidate in candidates] == ["https://cdn.example.com/App.exe"]


def test_extract_candidates_reads_dynamic_download_routes_and_skips_javascript_href() -> None:
    """Comprueba que la extracción usa rutas dinámicas y omite enlaces JavaScript."""
    html = """
    <a href="javascript:;" onclick="return AppCore.View.GetLauncher();">
      Descargar el juego
    </a>
    <button onclick="location.href='/download/launcherPC/'">Windows</button>
    <button onclick="location.href='/download/launcherOSX/'">macOS</button>
    <button data-download-url="/download/launcherLinux/">Linux</button>
    """

    candidates = extract_candidates(html, "https://warthunder.com/es")

    assert {candidate.url for candidate in candidates} == {
        "https://warthunder.com/download/launcherPC/",
        "https://warthunder.com/download/launcherOSX/",
        "https://warthunder.com/download/launcherLinux/",
    }
    assert all(candidate.url.startswith("https://") for candidate in candidates)


def test_extract_candidates_skips_malformed_ipv6_urls() -> None:
    """Comprueba el escenario `extract_candidates_skips_malformed_ipv6_urls`.
    """
    candidates = extract_candidates(
        '<a href="https://[broken/download.exe">Broken</a>'
        '<a href="https://cdn.example.com/AppSetup.exe">Download</a>',
        "https://example.com",
    )

    assert [candidate.url for candidate in candidates] == [
        "https://cdn.example.com/AppSetup.exe"
    ]


def test_score_prefers_windows_installer_over_docs() -> None:
    """Comprueba el escenario `score_prefers_windows_installer_over_docs`.
    """
    good = score_candidate(
        extract_candidates(
            '<a href="https://example.com/app-x64.exe">Download installer</a>',
            "https://example.com",
        )[0],
    )
    bad = score_candidate(
        extract_candidates(
            '<a href="https://example.com/release-notes.zip">Source docs</a>',
            "https://example.com",
        )[0],
    )

    assert good.score > bad.score
    assert good.score >= 100


def test_score_does_not_prefer_one_desktop_operating_system() -> None:
    """Comprueba el escenario `score_does_not_prefer_one_desktop_operating_system`.
    """
    scores = {
        extension: score_candidate(
            InstallerCandidate(
                url=f"https://downloads.example.com/AppSetup{extension}",
                source="href",
                label="Download installer",
            ),
            app_name="Example App",
        ).score
        for extension in (".exe", ".deb", ".dmg")
    }

    assert len(set(scores.values())) == 1


def test_score_rejects_github_source_archive_candidate() -> None:
    """Comprueba el escenario `score_rejects_github_source_archive_candidate`.
    """
    candidate = score_candidate(
        InstallerCandidate(
            url="https://github.com/vendor/app/archive/refs/heads/main.zip",
            source="href",
            label="Download ZIP",
        ),
        app_name="Vendor App",
        package_id="Vendor.App",
    )

    assert is_github_source_archive(candidate.url)
    assert candidate.score <= 0
    assert candidate.asset_kind == "source_archive"


def test_score_prefers_geogebra_matching_variant() -> None:
    """Comprueba el escenario `score_prefers_geogebra_matching_variant`.
    """
    candidates = [
        InstallerCandidate(
            url="https://download.geogebra.org/package/win-suite",
            source="href",
            label="Calculator Suite",
        ),
        InstallerCandidate(
            url="https://download.geogebra.org/package/windows-graphing",
            source="href",
            label="Graphing Calculator",
        ),
        InstallerCandidate(
            url="https://download.geogebra.org/package/windows-geometry",
            source="href",
            label="Geometry",
        ),
        InstallerCandidate(
            url="https://download.geogebra.org/package/windows-cas",
            source="href",
            label="CAS Calculator",
        ),
    ]

    def best_url(app_name: str) -> str:
        """Ejecuta la operación `best_url`.

        Args:
            app_name (str): Valor de `app_name` utilizado por la operación.

        Returns:
            str: Resultado producido por la operación.
        """
        scored = [
            score_candidate(
                candidate,
                app_name=app_name,
                package_id=app_name.replace(" ", "."),
            )
            for candidate in candidates
        ]
        return sorted(scored, key=lambda candidate: candidate.score, reverse=True)[0].url

    assert best_url("GeoGebra Graphing Calculator").endswith("/windows-graphing")
    assert best_url("GeoGebra Geometry").endswith("/windows-geometry")
    assert best_url("GeoGebra CAS Calculator").endswith("/windows-cas")
    assert best_url("GeoGebra Calculator Suite").endswith("/win-suite")


def test_infers_platform_architecture_and_version_for_multios_assets() -> None:
    """Comprueba el escenario `infers_platform_architecture_and_version_for_multios_assets`.
    """
    mac = InstallerCandidate(
        url="https://example.com/PDF-Over-4.4.8-aarch64.dmg",
        source="href",
        label="Mac OS ARM",
    )
    linux = InstallerCandidate(
        url="https://example.com/PDF-Over-4.4.8.jar",
        source="href",
        label="Linux",
    )

    assert infer_operating_system(mac) == "macos"
    assert infer_architecture(mac) == "aarch64"
    assert extract_version(mac) == "4.4.8"
    assert infer_operating_system(linux) == "linux"


def test_infer_architecture_ignores_svg_path_fragments() -> None:
    """Comprueba el escenario `infer_architecture_ignores_svg_path_fragments`.
    """
    candidate = InstallerCandidate(
        url="https://down.360safe.com/se/360se16.1.2000.64.exe",
        source="winstall_page",
        label="Download (.exe)",
        context='<svg><path d="M21 15v4"></path></svg>',
    )

    assert infer_architecture(candidate) == "x86_64"


def test_sourceforge_download_path_keeps_installer_extension_and_score() -> None:
    """Comprueba el escenario `sourceforge_download_path_keeps_installer_extension_and_score`.
    """
    candidate = score_candidate(
        InstallerCandidate(
            url=(
                "https://sourceforge.net/projects/akelpad/files/AkelPad%204/4.9.9/"
                "x64/AkelPad-4.9.9-x64-setup.exe/download"
            ),
            source="winstall_page",
            label="Download (.nullsoft)",
            asset_kind="winstall_download",
        ),
        app_name="AkelPad",
        package_id="AkelPad.AkelPad",
    )

    assert detect_extension(candidate.url) == ".exe"
    assert infer_operating_system(candidate) == "windows"
    assert candidate.score > 0


def test_http_candidate_gets_https_variant_without_changing_the_original() -> None:
    """La sonda TLS se añade antes de considerar el enlace HTTP atestado."""
    original = InstallerCandidate(
        url="http://downloads.example.com/AppSetup.msi",
        source="winstall_api",
        asset_kind="winstall_download",
    )

    upgraded = https_upgrade_variant(original)

    assert upgraded is not None
    assert upgraded.url == "https://downloads.example.com/AppSetup.msi"
    assert upgraded.source == "winstall_api_https_upgrade"
    assert upgraded.asset_kind == "winstall_download"
    assert original.url.startswith("http://")


def test_github_raw_branch_binary_is_not_misclassified_as_source_archive() -> None:
    """Un MSI/EXE versionado en una rama sigue siendo un binario validable."""
    candidate = score_candidate(
        InstallerCandidate(
            url=(
                "https://github.com/Zype-Z/Compass/raw/refs/heads/main/"
                "download/setup-1.1.0.msi"
            ),
            source="winstall_api",
            asset_kind="winstall_download",
        ),
        app_name="Compass",
        package_id="Zype.Compass",
        version="1.1.0",
    )

    assert not is_github_source_archive(candidate.url)
    assert candidate.asset_kind == "winstall_download"
    assert candidate.score > 0


def test_winstall_github_raw_zip_is_scored_as_declared_binary() -> None:
    """Un blob ZIP explícito no se confunde con el archivo fuente generado."""
    candidate = score_candidate(
        InstallerCandidate(
            url=(
                "https://github.com/TextAnalysisTool/Releases/raw/refs/heads/"
                "master/TextAnalysisTool.NET.zip"
            ),
            source="winstall_api",
            asset_kind="winstall_download",
        ),
        app_name="TextAnalysisTool.NET",
        package_id="DavidAnson.TextAnalysisToolNET",
        version="1.0.9456.32311",
    )

    assert not is_github_source_archive(candidate.url)
    assert candidate.asset_kind == "winstall_download"
    assert candidate.score > 0


def test_github_generated_source_zip_remains_rejected() -> None:
    """El permiso de blobs raw no habilita los archivos fuente de GitHub."""
    candidate = score_candidate(
        InstallerCandidate(
            url="https://github.com/vendor/app/archive/refs/heads/main.zip",
            source="winstall_api",
            asset_kind="winstall_download",
        ),
        app_name="App",
        package_id="Vendor.App",
    )

    assert is_github_source_archive(candidate.url)
    assert candidate.asset_kind == "source_archive"
    assert candidate.score < 0


def test_extensionless_winstall_download_is_eligible_for_final_url_validation() -> None:
    """Comprueba que una descarga sin extensión puede validar su URL final."""
    candidate = InstallerCandidate(
        url="https://dist.0patch.com/download/latestagent",
        source="winstall_page",
        label="Download (.msi)",
        asset_kind="winstall_download",
    )

    assert infer_operating_system(candidate) is None
    assert is_download_candidate(candidate)


def test_winstall_portable_distribution_is_not_scored_out() -> None:
    """Comprueba el escenario `winstall_portable_distribution_is_not_scored_out`.
    """
    candidate = score_candidate(
        InstallerCandidate(
            url="https://github.com/mozilla-ai/llamafile/releases/download/0.10.3/llamafile-0.10.3",
            source="winstall_page",
            label="Download (.portable)",
            asset_kind="winstall_download",
        ),
        app_name="llamafile",
        package_id="Mozilla.llamafile",
        version="0.10.3",
    )

    assert candidate.score > 0


def test_msixbundle_is_recognized_as_a_windows_installer() -> None:
    """Comprueba el escenario `msixbundle_is_recognized_as_a_windows_installer`.
    """
    candidate = InstallerCandidate(
        url=(
            "https://staticcdn.duckduckgo.com/release/0.164.1.0/"
            "DuckDuckGo_0.164.1.0.msixbundle"
        ),
        source="winstall_page",
        asset_kind="winstall_download",
    )

    assert detect_extension(candidate.url) == ".msixbundle"
    assert infer_operating_system(candidate) == "windows"
    assert is_download_candidate(candidate)


def test_s3_legacy_bucket_uses_secure_path_style_variant() -> None:
    """Comprueba el escenario `s3_legacy_bucket_uses_secure_path_style_variant`.
    """
    candidate = InstallerCandidate(
        url=(
            "https://build_archives.s3.amazonaws.com/Wireframes-Windows/"
            "Balsamiq_Wireframes_4.8.6_x86_Setup.exe"
        ),
        source="winstall_page",
        asset_kind="winstall_download",
    )

    variant = s3_path_style_variant(candidate)

    assert variant is not None
    assert variant.url == (
        "https://s3.amazonaws.com/build_archives/Wireframes-Windows/"
        "Balsamiq_Wireframes_4.8.6_x86_Setup.exe"
    )
    assert variant.source == "winstall_page_s3_path_style"


def test_elcomsoft_download_uses_the_canonical_tls_valid_cdn() -> None:
    """El endpoint oficial estable evita el mirror regional con certificado inválido."""
    candidate = InstallerCandidate(
        url="https://www.elcomsoft.com/download/apdfpr_setup_en.msi",
        source="winstall_api",
        asset_kind="winstall_download",
        referer="https://www.elcomsoft.com/apdfpr.html",
    )

    variant = elcomsoft_download_variant(candidate)

    assert variant is not None
    assert variant.url == "https://download.elcomsoft.com/apdfpr_setup_en.msi"
    assert variant.source == "winstall_api_elcomsoft_canonical"
    assert variant.asset_kind == "winstall_download"


def test_sourceforge_manifest_placeholder_uses_public_router() -> None:
    """Comprueba el escenario `sourceforge_manifest_placeholder_uses_public_router`.
    """
    candidate = InstallerCandidate(
        url=(
            "https://udomain.dl.sourceforge.net/project/maxlauncher/"
            "MaxLauncher/1.31.0.0/maxlauncher_1.31.0.0_setup.exe"
        ),
        source="winstall_page",
        asset_kind="winstall_download",
    )

    variant = sourceforge_mirror_variant(candidate)

    assert variant is not None
    assert variant.url == (
        "https://downloads.sourceforge.net/project/maxlauncher/"
        "MaxLauncher/1.31.0.0/maxlauncher_1.31.0.0_setup.exe"
    )
    assert variant.source == "winstall_page_sourceforge_router"

    regional = sourceforge_mirror_variant(
        InstallerCandidate(
            url="https://cyfuture.dl.sourceforge.net/project/app/AppPortable.zip",
            source="winstall_page",
            asset_kind="winstall_download",
        )
    )
    assert regional is not None
    assert regional.url == "https://downloads.sourceforge.net/project/app/AppPortable.zip"

    download_page = sourceforge_mirror_variant(
        InstallerCandidate(
            url=(
                "https://sourceforge.net/projects/jdreplace/files/4.3/"
                "jdReplace-4.3-WindowsInstaller.exe/download"
            ),
            source="winstall_api",
            asset_kind="winstall_download",
        )
    )
    assert download_page is not None
    assert download_page.url == (
        "https://downloads.sourceforge.net/project/jdreplace/4.3/"
        "jdReplace-4.3-WindowsInstaller.exe"
    )


def test_version_extraction_does_not_treat_ip_host_as_version() -> None:
    """Comprueba el escenario `version_extraction_does_not_treat_ip_host_as_version`.
    """
    candidate = InstallerCandidate(
        url="http://120.24.245.232/app/pcr532.exe",
        source="winstall_page",
        context='<a href="http://120.24.245.232/app/pcr532.exe">Download</a>',
    )

    assert extract_version(candidate) is None
