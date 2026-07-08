from app.scraper.candidates import (
    InstallerCandidate,
    extract_candidates,
    extract_version,
    infer_architecture,
    infer_operating_system,
    is_github_source_archive,
    score_candidate,
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


def test_score_prefers_windows_installer_over_docs() -> None:
    good = score_candidate(
        extract_candidates(
            '<a href="https://example.com/app-x64.exe">Download installer</a>',
            "https://example.com",
        )[0],
        allowed_domains={"example.com"},
    )
    bad = score_candidate(
        extract_candidates(
            '<a href="https://example.com/release-notes.zip">Source docs</a>',
            "https://example.com",
        )[0],
        allowed_domains={"example.com"},
    )

    assert good.score > bad.score
    assert good.score >= 100


def test_score_rejects_github_source_archive_candidate() -> None:
    candidate = score_candidate(
        InstallerCandidate(
            url="https://github.com/vendor/app/archive/refs/heads/main.zip",
            source="href",
            label="Download ZIP",
        ),
        allowed_domains={"github.com"},
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
                allowed_domains={"geogebra.org"},
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
