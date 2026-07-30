"""Contiene las pruebas de `test_website_discovery`.
"""
from __future__ import annotations

import httpx
import pytest

from app.core.config import Settings
from app.scraper.safe_http import SafeHttpError, SafeHttpResponse
from app.scraper.validator import ValidationConfidence, ValidationResult
from app.scraper.website_discovery import (
    DiscoveredInstaller,
    WebsiteAppDiscoverer,
    best_installer_version,
    fetch_official_page,
    website_discovery_input_hash,
)


@pytest.fixture(autouse=True)
def disable_description_provider(monkeypatch) -> None:
    """Ejecuta la operación `disable_description_provider`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    monkeypatch.setattr(
        "app.scraper.website_discovery.AppDescriptionLLMClient.has_provider",
        lambda _client: False,
    )


def test_website_discovery_input_hash_is_keyed_and_url_free() -> None:
    """Comprueba el escenario `website_discovery_input_hash_is_keyed_and_url_free`.
    """
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


def test_best_installer_version_ignores_nulls_and_normalizes_once() -> None:
    """Comprueba el escenario `best_installer_version_ignores_nulls_and_normalizes_once`.
    """
    def installer(version: str | None) -> DiscoveredInstaller:
        """Ejecuta la operación `installer`.

        Args:
            version (str | None): Valor de `version` utilizado por la operación.

        Returns:
            DiscoveredInstaller: Resultado producido por la operación.
        """
        return DiscoveredInstaller(
            url="https://downloads.example.com/app.exe",
            final_domain="downloads.example.com",
            filename="app.exe",
            extension=".exe",
            content_type="application/x-msdownload",
            size_bytes=1024,
            version=version,
            operating_system="windows",
            architecture="x86_64",
            score=100,
        )

    assert best_installer_version(
        [installer(None), installer("   "), installer("2.9.9"), installer(" v2.10.0 ")]
    ) == "v2.10.0"
    assert (
        best_installer_version(
            [installer(None), installer(" beta "), installer("release")]
        )
        == "beta"
    )
    assert best_installer_version([installer(None), installer(" ")]) is None


@pytest.mark.asyncio
async def test_discovery_retries_queryless_page_after_safe_http_403(
    monkeypatch,
) -> None:
    """Comprueba el escenario `discovery_retries_queryless_page_after_safe_http_403`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    requested_urls: list[str] = []

    async def fetch_page(url: str, **_kwargs) -> SafeHttpResponse:
        """Recupera la operación `page`.

        Args:
            url (str): URL del recurso que debe procesarse.
            **_kwargs (Any): Valor de `_kwargs` utilizado por la operación.

        Returns:
            SafeHttpResponse: Resultado de `fetch_page`.

        Throws:
            SafeHttpError: Si no puede completarse la operación bajo las condiciones requeridas.
        """
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
        """Establece la operación `phase`.

        Args:
            _phase (str): Valor de `_phase` utilizado por la operación.
        """
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
    """Comprueba el escenario `query_fallback_never_strips_sensitive_parameters`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    requested_urls: list[str] = []

    async def fetch_page(url: str, **_kwargs) -> SafeHttpResponse:
        """Recupera la operación `page`.

        Args:
            url (str): URL del recurso que debe procesarse.
            **_kwargs (Any): Valor de `_kwargs` utilizado por la operación.

        Returns:
            SafeHttpResponse: Resultado de `fetch_page`.

        Throws:
            SafeHttpError: Si no puede completarse la operación bajo las condiciones requeridas.
        """
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
    """Comprueba el escenario `optional_installer_uri_binds_a_neutral_artifact_to_its_os_slot`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    official_url = "https://example.com/product"
    linux_url = "https://downloads.example.com/Product-portable.zip"

    async def fetch_page(*_args, **_kwargs) -> SafeHttpResponse:
        """Recupera la operación `page`.

        Args:
            *_args (Any): Valor de `_args` utilizado por la operación.
            **_kwargs (Any): Valor de `_kwargs` utilizado por la operación.

        Returns:
            SafeHttpResponse: Resultado de `fetch_page`.
        """
        return SafeHttpResponse(
            final_url=official_url,
            status_code=200,
            content_type="text/html",
            content=b"<html><title>Product</title></html>",
            headers=httpx.Headers({"content-type": "text/html"}),
        )

    async def validate_installer(_validator, candidate, *, require_signature=False):
        """Valida la operación `installer`.

        Args:
            _validator (Any): Valor de `_validator` utilizado por la operación.
            candidate (Any): Valor de `candidate` utilizado por la operación.
            require_signature (bool): Valor de `require_signature` utilizado por la operación.
        """
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
        """Establece la operación `phase`.

        Args:
            _phase (str): Valor de `_phase` utilizado por la operación.
        """
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
    """Comprueba el escenario `optional_installer_uri_rejects_a_deterministic_os_mismatch`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    official_url = "https://example.com/product"
    windows_url = "https://downloads.example.com/Product.exe"

    async def fetch_page(*_args, **_kwargs) -> SafeHttpResponse:
        """Recupera la operación `page`.

        Args:
            *_args (Any): Valor de `_args` utilizado por la operación.
            **_kwargs (Any): Valor de `_kwargs` utilizado por la operación.

        Returns:
            SafeHttpResponse: Resultado de `fetch_page`.
        """
        return SafeHttpResponse(
            final_url=official_url,
            status_code=200,
            content_type="text/html",
            content=b"<html><title>Product</title></html>",
            headers=httpx.Headers({"content-type": "text/html"}),
        )

    async def validate_installer(_validator, candidate, *, require_signature=False):
        """Valida la operación `installer`.

        Args:
            _validator (Any): Valor de `_validator` utilizado por la operación.
            candidate (Any): Valor de `candidate` utilizado por la operación.
            require_signature (bool): Valor de `require_signature` utilizado por la operación.
        """
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
        """Establece la operación `phase`.

        Args:
            _phase (str): Valor de `_phase` utilizado por la operación.
        """
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
    """Comprueba el escenario `discovery_extracts_metadata_and_keeps_only_validated_installers`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
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
        """Recupera la operación `page`.

        Args:
            *_args (Any): Valor de `_args` utilizado por la operación.
            **_kwargs (Any): Valor de `_kwargs` utilizado por la operación.

        Returns:
            SafeHttpResponse: Resultado de `fetch_page`.
        """
        return SafeHttpResponse(
            final_url=official_url,
            status_code=200,
            content_type="text/html",
            content=html,
            headers=httpx.Headers({"content-type": "text/html"}),
        )

    async def validate_installer(_validator, candidate, *, require_signature=False):
        """Valida la operación `installer`.

        Args:
            _validator (Any): Valor de `_validator` utilizado por la operación.
            candidate (Any): Valor de `candidate` utilizado por la operación.
            require_signature (bool): Valor de `require_signature` utilizado por la operación.
        """
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
        """Establece la operación `phase`.

        Args:
            phase (str): Valor de `phase` utilizado por la operación.
        """
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
