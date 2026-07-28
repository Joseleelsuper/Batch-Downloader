from __future__ import annotations

import httpx
import pytest

from app.core.config import Settings
from app.scraper.safe_http import SafeHttpError, SafeHttpResponse
from app.scraper.validator import ValidationConfidence, ValidationResult
from app.scraper.website_discovery import (
    WebsiteAppDiscoverer,
    fetch_official_page,
    website_discovery_input_hash,
)


@pytest.fixture(autouse=True)
def disable_description_provider(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.scraper.website_discovery.AppDescriptionLLMClient.has_provider",
        lambda _client: False,
    )


def test_website_discovery_input_hash_is_keyed_and_url_free() -> None:
    official_url = "https://example.com/products/desktop"

    first = website_discovery_input_hash(official_url, "secret-a")
    second = website_discovery_input_hash(official_url, "secret-b")

    assert first != second
    assert len(first) == 64
    assert official_url not in first
    assert first != website_discovery_input_hash(
        official_url,
        "secret-a",
        {"windows": "https://downloads.example.com/Product.exe"},
    )


@pytest.mark.asyncio
async def test_discovery_retries_queryless_page_after_safe_http_403(
    monkeypatch,
) -> None:
    requested_urls: list[str] = []

    async def fetch_page(url: str, **_kwargs) -> SafeHttpResponse:
        requested_urls.append(url)
        if url.endswith("?show_all=1"):
            raise SafeHttpError("http_403")
        return SafeHttpResponse(
            final_url=url,
            status_code=200,
            content_type="text/html",
            content=b"<html><title>FileZilla</title></html>",
            headers=httpx.Headers({"content-type": "text/html"}),
        )

    monkeypatch.setattr(
        "app.scraper.website_discovery.fetch_public_resource",
        fetch_page,
    )

    async def set_phase(_phase: str) -> None:
        return None

    result, installers, warnings = await WebsiteAppDiscoverer(Settings()).inspect(
        "https://filezilla-project.org/download.php?show_all=1",
        set_phase=set_phase,
    )

    assert requested_urls[:2] == [
        "https://filezilla-project.org/download.php?show_all=1",
        "https://filezilla-project.org/download.php",
    ]
    assert result["suggestions"]["officialUrl"]["value"] == (
        "https://filezilla-project.org/download.php"
    )
    assert installers == []
    assert "official_url:query_removed_after_http_403" in warnings


@pytest.mark.asyncio
async def test_query_fallback_never_strips_sensitive_parameters(monkeypatch) -> None:
    requested_urls: list[str] = []

    async def fetch_page(url: str, **_kwargs) -> SafeHttpResponse:
        requested_urls.append(url)
        raise SafeHttpError("http_403")

    monkeypatch.setattr(
        "app.scraper.website_discovery.fetch_public_resource",
        fetch_page,
    )

    with pytest.raises(SafeHttpError, match="http_403"):
        await fetch_official_page(
            "https://example.com/download?token=secret",
            Settings(),
        )

    assert requested_urls == ["https://example.com/download?token=secret"]


@pytest.mark.asyncio
async def test_optional_installer_uri_binds_a_neutral_artifact_to_its_os_slot(
    monkeypatch,
) -> None:
    official_url = "https://example.com/product"
    linux_url = "https://downloads.example.com/Product-portable.zip"

    async def fetch_page(*_args, **_kwargs) -> SafeHttpResponse:
        return SafeHttpResponse(
            final_url=official_url,
            status_code=200,
            content_type="text/html",
            content=b"<html><title>Product</title></html>",
            headers=httpx.Headers({"content-type": "text/html"}),
        )

    async def validate_installer(_validator, candidate, *, require_signature=False):
        assert require_signature is True
        assert candidate.url == linux_url
        return ValidationResult(
            ok=True,
            url=candidate.url,
            final_url=candidate.url,
            final_domain="example.com",
            filename="Product-portable.zip",
            extension=".zip",
            content_type="application/zip",
            size_bytes=4096,
            confidence=ValidationConfidence.VALIDATED,
        )

    monkeypatch.setattr(
        "app.scraper.website_discovery.fetch_public_resource",
        fetch_page,
    )
    monkeypatch.setattr(
        "app.scraper.website_discovery.DownloadValidator.validate",
        validate_installer,
    )

    async def set_phase(_phase: str) -> None:
        return None

    _result, installers, _warnings = await WebsiteAppDiscoverer(Settings()).inspect(
        official_url,
        {"linux": linux_url},
        set_phase=set_phase,
    )

    assert len(installers) == 1
    assert installers[0].operating_system == "linux"
    assert installers[0].url == linux_url


@pytest.mark.asyncio
async def test_optional_installer_uri_rejects_a_deterministic_os_mismatch(
    monkeypatch,
) -> None:
    official_url = "https://example.com/product"
    windows_url = "https://downloads.example.com/Product.exe"

    async def fetch_page(*_args, **_kwargs) -> SafeHttpResponse:
        return SafeHttpResponse(
            final_url=official_url,
            status_code=200,
            content_type="text/html",
            content=b"<html><title>Product</title></html>",
            headers=httpx.Headers({"content-type": "text/html"}),
        )

    async def validate_installer(_validator, candidate, *, require_signature=False):
        return ValidationResult(
            ok=True,
            url=candidate.url,
            final_url=candidate.url,
            final_domain="example.com",
            filename="Product.exe",
            extension=".exe",
            content_type="application/x-msdownload",
            size_bytes=4096,
            confidence=ValidationConfidence.VALIDATED,
        )

    monkeypatch.setattr(
        "app.scraper.website_discovery.fetch_public_resource",
        fetch_page,
    )
    monkeypatch.setattr(
        "app.scraper.website_discovery.DownloadValidator.validate",
        validate_installer,
    )

    async def set_phase(_phase: str) -> None:
        return None

    _result, installers, warnings = await WebsiteAppDiscoverer(Settings()).inspect(
        official_url,
        {"macos": windows_url},
        set_phase=set_phase,
    )

    assert installers == []
    assert "installers:not_found" in warnings


@pytest.mark.asyncio
async def test_discovery_extracts_metadata_and_keeps_only_validated_installers(
    monkeypatch,
) -> None:
    official_url = "https://example.com/products/desktop"
    installer_url = "https://downloads.example.com/ExampleDesktop-2.4.1.exe"
    html = f"""
    <html>
      <head>
        <script type="application/ld+json">
          {{
            "@context": "https://schema.org",
            "@type": "SoftwareApplication",
            "name": "Example Desktop",
            "publisher": {{"@type": "Organization", "name": "Example Vendor"}},
            "softwareVersion": "2.4.1",
            "description": "Cliente de escritorio para Example."
          }}
        </script>
      </head>
      <body><a href="{installer_url}">Descargar para Windows</a></body>
    </html>
    """.encode()

    async def fetch_page(*_args, **_kwargs) -> SafeHttpResponse:
        return SafeHttpResponse(
            final_url=official_url,
            status_code=200,
            content_type="text/html",
            content=html,
            headers=httpx.Headers({"content-type": "text/html"}),
        )

    async def validate_installer(_validator, candidate, *, require_signature=False):
        assert require_signature is True
        assert candidate.url == installer_url
        return ValidationResult(
            ok=True,
            url=candidate.url,
            final_url=candidate.url,
            final_domain="example.com",
            filename="ExampleDesktop-2.4.1.exe",
            extension=".exe",
            content_type="application/x-msdownload",
            size_bytes=4096,
            confidence=ValidationConfidence.VALIDATED,
        )

    monkeypatch.setattr(
        "app.scraper.website_discovery.fetch_public_resource",
        fetch_page,
    )
    monkeypatch.setattr(
        "app.scraper.website_discovery.DownloadValidator.validate",
        validate_installer,
    )

    phases: list[str] = []

    async def set_phase(phase: str) -> None:
        phases.append(phase)

    result, installers, warnings = await WebsiteAppDiscoverer(Settings()).inspect(
        official_url,
        set_phase=set_phase,
    )

    assert result["suggestions"]["name"] == {
        "value": "Example Desktop",
        "source": "json_ld",
    }
    assert result["suggestions"]["publisher"]["value"] == "Example Vendor"
    assert result["suggestions"]["latestVersion"]["value"] == "2.4.1"
    assert result["suggestions"]["officialUrl"]["value"] == official_url
    assert result["installerCount"] == 1
    assert installers[0].filename == "ExampleDesktop-2.4.1.exe"
    assert installers[0].operating_system == "windows"
    assert installers[0].url == installer_url
    assert "installers:not_found" not in warnings
    assert phases == [
        "validating_website",
        "reading_website_metadata",
        "searching_installers",
        "generating_description",
    ]
