from app.scraper.candidates import (
    InstallerCandidate,
    detect_extension,
    extract_candidates,
    extract_version,
    infer_architecture,
    infer_operating_system,
    is_download_candidate,
    is_github_source_archive,
    score_candidate,
    s3_path_style_variant,
)


def test_extract_candidates_from_links_and_scripts() -> None:
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
    candidates = extract_candidates(
        '<script>const broken = ").exe"; const real = "https://cdn.example.com/App.exe";</script>',
        "https://example.com",
    )

    assert [candidate.url for candidate in candidates] == ["https://cdn.example.com/App.exe"]


def test_score_prefers_windows_installer_over_docs() -> None:
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
    candidate = InstallerCandidate(
        url="https://down.360safe.com/se/360se16.1.2000.64.exe",
        source="winstall_page",
        label="Download (.exe)",
        context='<svg><path d="M21 15v4"></path></svg>',
    )

    assert infer_architecture(candidate) == "x86_64"


def test_sourceforge_download_path_keeps_installer_extension_and_score() -> None:
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


def test_extensionless_winstall_download_is_eligible_for_final_url_validation() -> None:
    candidate = InstallerCandidate(
        url="https://dist.0patch.com/download/latestagent",
        source="winstall_page",
        label="Download (.msi)",
        asset_kind="winstall_download",
    )

    assert infer_operating_system(candidate) is None
    assert is_download_candidate(candidate)


def test_s3_legacy_bucket_uses_secure_path_style_variant() -> None:
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
