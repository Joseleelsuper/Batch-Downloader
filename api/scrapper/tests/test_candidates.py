from app.scraper.candidates import extract_candidates, score_candidate


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
