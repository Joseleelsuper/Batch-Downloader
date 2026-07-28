from __future__ import annotations

import pytest

from app.scraper.manual_installer import (
    description_provenance,
    parse_page_evidence,
    reviewed_field_sources,
    suggested_version,
)
from app.scraper.safe_http import (
    SafeHttpError,
    has_sensitive_query,
    validate_public_https_syntax,
)


def test_page_evidence_prefers_allowlisted_software_application_json_ld() -> None:
    html = b"""
    <html>
      <head>
        <link rel="canonical" href="/download">
        <meta property="og:title" content="Fallback name">
        <meta property="og:description" content="Fallback description">
        <meta property="og:image" content="/fallback.png">
        <script type="application/ld+json">
          {
            "@context": "https://schema.org",
            "@type": "SoftwareApplication",
            "name": "Trusted Product",
            "publisher": {"@type": "Organization", "name": "Trusted Vendor"},
            "softwareVersion": "3.2.1",
            "description": "Product description",
            "image": "/product.png",
            "ignored": "do not expose"
          }
        </script>
      </head>
    </html>
    """

    evidence = parse_page_evidence(html, "https://downloads.example.com/apps/product")

    assert evidence == {
        "name": "Trusted Product",
        "name_source": "json_ld",
        "publisher": "Trusted Vendor",
        "version": "3.2.1",
        "description": "Product description",
        "description_source": "json_ld",
        "icon": "https://downloads.example.com/product.png",
        "icon_source": "json_ld",
        "canonical": "https://downloads.example.com/download",
    }


def test_page_evidence_rejects_cross_site_or_secret_canonical_urls() -> None:
    cross_site = parse_page_evidence(
        b'<link rel="canonical" href="https://private.example.net/app">',
        "https://example.com/download",
    )
    secret_query = parse_page_evidence(
        b'<link rel="canonical" href="/app?token=secret">',
        "https://example.com/download",
    )

    assert "canonical" not in cross_site
    assert "canonical" not in secret_query


def test_page_evidence_records_open_graph_name_provenance() -> None:
    evidence = parse_page_evidence(
        b'<meta property="og:title" content="Metadata Product">',
        "https://example.com/download",
    )

    assert evidence["name"] == "Metadata Product"
    assert evidence["name_source"] == "open_graph"


@pytest.mark.parametrize(
    "url",
    [
        "https://example.com/app?token=secret",
        "https://example.com/app?X-Amz-Signature=secret",
        "https://example.com/app?X-Goog-Credential=secret",
        "https://example.com/app?api-key=secret",
    ],
)
def test_sensitive_query_detects_provider_specific_secret_keys(url: str) -> None:
    assert has_sensitive_query(url)


def test_suggested_version_only_advances_a_deterministic_version() -> None:
    assert suggested_version("2.4.0", "2.3.9", "2.5.0") == ("2.5.0", "filename")
    assert suggested_version("2.4.0", "release-next", "2.3.0") == ("2.4.0", "current")
    assert suggested_version(None, "v1.8.2", None) == ("v1.8.2", "json_ld")


def test_description_provenance_distinguishes_generated_and_manual_content() -> None:
    ai_state = {"provider": "groq", "model": "model-test"}

    assert description_provenance(
        "Descripción generada",
        "Descripción  generada",
        ai_state,
    ) == ("completed", "groq", "model-test")
    assert description_provenance(
        "Descripción revisada por el administrador",
        "Descripción generada",
        ai_state,
    ) == ("completed", "admin_manual", None)


def test_reviewed_field_sources_marks_only_changed_values_as_manual() -> None:
    assert reviewed_field_sources(
        {
            "name": {"value": "Example", "source": "json_ld"},
            "longDescription": {
                "value": "Descripción generada",
                "source": "generated_ai",
            },
        },
        {
            "name": "Example",
            "longDescription": "Descripción revisada",
        },
    ) == {
        "name": "json_ld",
        "longDescription": "manual",
    }


@pytest.mark.parametrize(
    ("url", "code"),
    [
        ("http://example.com/App.exe", "https_required"),
        ("https://user:password@example.com/App.exe", "url_credentials_forbidden"),
        ("https://example.com/\nApp.exe", "invalid_url"),
    ],
)
def test_public_url_syntax_rejects_unsafe_inputs(url: str, code: str) -> None:
    with pytest.raises(SafeHttpError) as error:
        validate_public_https_syntax(url)

    assert error.value.code == code
