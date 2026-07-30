"""Contiene las pruebas de `test_validator`.
"""
import dns.exception
import dns.resolver
import httpx
import pytest
import respx

from app.core.config import Settings
from app.scraper.candidates import InstallerCandidate
from app.scraper.validator import (
    DownloadValidator,
    ValidationConfidence,
    domain_has_public_dns,
    is_public_ip,
    same_site_referer,
)


@pytest.mark.asyncio
async def test_domain_has_public_dns_rejects_loopback_literal() -> None:
    """Comprueba el escenario `domain_has_public_dns_rejects_loopback_literal`.
    """
    assert await domain_has_public_dns("127.0.0.1") is False


@pytest.mark.asyncio
async def test_domain_dns_resolution_retries_transient_failure(monkeypatch) -> None:
    """Comprueba el escenario `domain_dns_resolution_retries_transient_failure`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    calls: list[str] = []

    async def resolve(_hostname: str, record_type: str, **_kwargs):
        """Ejecuta la operación `resolve`.

        Args:
            _hostname (str): Valor de `_hostname` utilizado por la operación.
            record_type (str): Valor de `record_type` utilizado por la operación.
            **_kwargs (Any): Valor de `_kwargs` utilizado por la operación.

        Throws:
            dns.resolver.NoAnswer: Si no puede completarse la operación bajo las condiciones
                requeridas.
            dns.exception.Timeout: Si no puede completarse la operación bajo las condiciones
                requeridas.
        """
        calls.append(record_type)
        if len(calls) <= 2:
            raise dns.exception.Timeout
        if record_type == "AAAA":
            raise dns.resolver.NoAnswer
        return [type("Answer", (), {"address": "203.0.113.10"})()]

    monkeypatch.setattr("app.scraper.validator.dns.asyncresolver.resolve", resolve)
    monkeypatch.setattr(
        "app.scraper.validator.is_public_ip",
        lambda address: str(address) == "203.0.113.10",
    )

    assert await domain_has_public_dns("transient-retry.example.test") is True
    assert len(calls) == 4


def test_private_ips_are_not_public() -> None:
    """Comprueba el escenario `private_ips_are_not_public`.
    """
    import ipaddress

    assert is_public_ip(ipaddress.ip_address("10.0.0.1")) is False
    assert is_public_ip(ipaddress.ip_address("192.168.1.10")) is False


def test_cross_site_winstall_referer_is_not_sent_to_download_host() -> None:
    """Comprueba el escenario `cross_site_winstall_referer_is_not_sent_to_download_host`.
    """
    assert same_site_referer(
        "https://geeks3d.com/downloads/FurMark_Setup.exe",
        "https://winstall.app/apps/Geeks3D.FurMark.1",
    ) is None
    assert same_site_referer(
        "https://cdn.geeks3d.com/downloads/FurMark_Setup.exe",
        "https://www.geeks3d.com/furmark/downloads/",
    ) == "https://www.geeks3d.com/furmark/downloads/"


@pytest.mark.asyncio
async def test_validator_rejects_github_source_archives_before_network() -> None:
    """Comprueba el escenario `validator_rejects_github_source_archives_before_network`.
    """
    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url="https://codeload.github.com/vendor/app/zip/refs/heads/main",
            source="github_release_html",
        ),
    )

    assert result.ok is False
    assert result.reason == "github_source_archive"


@pytest.mark.asyncio
@respx.mock
async def test_validator_accepts_github_release_asset_redirect(monkeypatch) -> None:
    """Comprueba el escenario `validator_accepts_github_release_asset_redirect`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    respx.head(
        "https://github.com/vendor/app/releases/download/v1.0.0/AppSetup.exe"
    ).mock(
        return_value=httpx.Response(
            302,
            headers={
                "location": "https://release-assets.githubusercontent.com/github-production-release-asset/AppSetup.exe"
            },
        )
    )
    respx.head(
        "https://release-assets.githubusercontent.com/github-production-release-asset/AppSetup.exe"
    ).mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "application/octet-stream", "content-length": "1024"},
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url="https://github.com/vendor/app/releases/download/v1.0.0/AppSetup.exe",
            source="github_release_api",
        ),
    )

    assert result.ok is True
    assert result.final_domain == "githubusercontent.com"
    assert result.filename == "AppSetup.exe"
    assert result.extension == ".exe"
    assert result.confidence == ValidationConfidence.VALIDATED


@pytest.mark.asyncio
@respx.mock
async def test_validator_blocks_a_public_to_private_redirect(monkeypatch) -> None:
    """Comprueba el escenario `validator_blocks_a_public_to_private_redirect`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            hostname (str | None): Valor de `hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return hostname == "downloads.example.com"

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    url = "https://downloads.example.com/AppSetup.exe"
    respx.head(url).mock(
        return_value=httpx.Response(
            302,
            headers={"location": "https://127.0.0.1/private/AppSetup.exe"},
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(url=url, source="admin_manual", label="installer setup"),
        require_signature=True,
    )

    assert result.ok is False
    assert result.reason == "redirect_dns_not_public"
    assert len(respx.calls) == 1


@pytest.mark.asyncio
@respx.mock
async def test_validator_rejects_redirect_credentials_before_following(monkeypatch) -> None:
    """Comprueba el escenario `validator_rejects_redirect_credentials_before_following`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    url = "https://downloads.example.com/AppSetup.exe"
    respx.head(url).mock(
        return_value=httpx.Response(
            302,
            headers={
                "location": "https://user:secret@cdn.example.com/AppSetup.exe",
            },
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(url=url, source="admin_manual", label="installer setup"),
        require_signature=True,
    )

    assert result.ok is False
    assert result.reason == "redirect_url_credentials_forbidden"
    assert len(respx.calls) == 1


@pytest.mark.asyncio
@respx.mock
async def test_manual_validator_requires_a_signature_even_for_binary_content_type(
    monkeypatch,
) -> None:
    """Comprueba el escenario `manual_validator_requires_a_signature_even_for_binary_content_type`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    url = "https://downloads.example.com/AppSetup.exe"
    respx.head(url).mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "application/octet-stream", "content-length": "4096"},
        )
    )
    respx.get(url).mock(
        return_value=httpx.Response(
            206,
            headers={"content-type": "application/octet-stream"},
            content=b"<html>not an executable</html>",
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(url=url, source="admin_manual", label="installer setup"),
        require_signature=True,
    )

    assert result.ok is False
    assert result.reason == "installer_signature_mismatch"


@pytest.mark.asyncio
@respx.mock
async def test_manual_validator_accepts_a_matching_signature(monkeypatch) -> None:
    """Comprueba el escenario `manual_validator_accepts_a_matching_signature`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    url = "https://downloads.example.com/AppSetup.exe"
    respx.head(url).mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "application/octet-stream", "content-length": "4096"},
        )
    )
    respx.get(url).mock(
        return_value=httpx.Response(
            206,
            headers={"content-type": "application/octet-stream"},
            content=b"MZ" + b"\x00" * 32,
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(url=url, source="admin_manual", label="installer setup"),
        require_signature=True,
    )

    assert result.ok is True
    assert result.confidence == ValidationConfidence.VALIDATED


@pytest.mark.asyncio
@respx.mock
async def test_validator_preserves_candidate_filename_when_redirect_hides_it(monkeypatch) -> None:
    """Comprueba el escenario `validator_preserves_candidate_filename_when_redirect_hides_it`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    respx.head("https://downloads.example.com/releases/AppSetup.msi").mock(
        return_value=httpx.Response(
            302,
            headers={"location": "https://cdn.example.net/opaque/12345"},
        )
    )
    respx.head("https://cdn.example.net/opaque/12345").mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "application/octet-stream", "content-length": "2048"},
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url="https://downloads.example.com/releases/AppSetup.msi",
            source="href",
        ),
    )

    assert result.ok is True
    assert result.filename == "AppSetup.msi"
    assert result.extension == ".msi"


@pytest.mark.asyncio
@respx.mock
async def test_validator_accepts_public_cross_domain_redirect_without_allowlist(
    monkeypatch,
) -> None:
    """Comprueba el escenario `validator_accepts_public_cross_domain_redirect_without_allowlist`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    respx.head("https://downloads.vendor.com/AppSetup.exe").mock(
        return_value=httpx.Response(
            302,
            headers={"location": "https://cloudflare-cdn.net/AppSetup.exe"},
        )
    )
    respx.head("https://cloudflare-cdn.net/AppSetup.exe").mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "application/x-msdownload", "content-length": "1024"},
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url="https://downloads.vendor.com/AppSetup.exe",
            source="href",
        )
    )

    assert result.ok is True
    assert result.final_url == "https://cloudflare-cdn.net/AppSetup.exe"


@pytest.mark.asyncio
@respx.mock
async def test_validator_accepts_common_windows_executable_mime_alias(monkeypatch) -> None:
    """Comprueba el escenario `validator_accepts_common_windows_executable_mime_alias`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    url = "https://downloads.example.com/AppSetup.exe"
    respx.head(url).mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "application/x-msdos-program", "content-length": "4096"},
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(url=url, source="winstall_page", asset_kind="winstall_download")
    )

    assert result.ok is True
    assert result.extension == ".exe"


@pytest.mark.asyncio
@respx.mock
async def test_validator_accepts_mislabeled_msi_after_partial_signature_probe(monkeypatch) -> None:
    """Comprueba el escenario `validator_accepts_mislabeled_msi_after_partial_signature_probe`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    url = "https://downloads.example.com/AppSetup.msi"
    respx.head(url).mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "text/plain", "content-length": "100000"},
        )
    )
    respx.get(url).mock(
        return_value=httpx.Response(
            206,
            headers={"content-type": "text/plain", "content-range": "bytes 0-1023/100000"},
            content=b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + (b"\0" * 32),
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(url=url, source="winstall_page", asset_kind="winstall_download")
    )

    assert result.ok is True
    assert result.extension == ".msi"
    assert result.size_bytes == 100000


@pytest.mark.asyncio
@respx.mock
async def test_validator_uses_actual_zip_signature_for_winstall_mislabeled_exe(monkeypatch) -> None:
    """Comprueba el escenario `validator_uses_actual_zip_signature_for_winstall_mislabeled_exe`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    url = "https://downloads.example.com/MyApp-Win64.exe"
    respx.head(url).mock(
        return_value=httpx.Response(200, headers={"content-type": "text/plain"})
    )
    respx.get(url).mock(
        return_value=httpx.Response(206, content=b"PK\x03\x04" + (b"\0" * 32))
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url=url,
            source="winstall_page",
            label="Download (.exe)",
            asset_kind="winstall_download",
        )
    )

    assert result.ok is True
    assert result.extension == ".zip"
    assert result.filename == "MyApp-Win64.zip"


@pytest.mark.asyncio
@respx.mock
async def test_validator_follows_redirect_revealed_only_by_partial_get(monkeypatch) -> None:
    """Comprueba el escenario `validator_follows_redirect_revealed_only_by_partial_get`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    original = "https://downloads.example.com/App-1.0.exe"
    endpoint = "https://downloads.example.com/current"
    current = "https://downloads.example.com/App-2.0.zip"
    respx.head(original).mock(
        return_value=httpx.Response(302, headers={"location": endpoint})
    )
    respx.head(endpoint).mock(
        return_value=httpx.Response(200, headers={"content-type": "text/plain"})
    )
    respx.get(endpoint).mock(
        return_value=httpx.Response(302, headers={"location": current})
    )
    respx.head(current).mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "application/zip", "content-length": "4096"},
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url=original,
            source="winstall_page",
            label="Download (.exe)",
            asset_kind="winstall_download",
        )
    )

    assert result.ok is True
    assert result.final_url == current
    assert result.extension == ".zip"


@pytest.mark.asyncio
@respx.mock
async def test_validator_rejects_mislabeled_text_without_binary_signature(monkeypatch) -> None:
    """Comprueba el escenario `validator_rejects_mislabeled_text_without_binary_signature`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    url = "https://downloads.example.com/AppSetup.exe"
    respx.head(url).mock(
        return_value=httpx.Response(200, headers={"content-type": "text/plain"})
    )
    respx.get(url).mock(
        return_value=httpx.Response(206, content=b"This is not an executable")
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(url=url, source="winstall_page", asset_kind="winstall_download")
    )

    assert result.ok is False
    assert result.reason == "not_an_installer"


@pytest.mark.asyncio
async def test_validator_rejects_malformed_url_without_aborting() -> None:
    """Comprueba el escenario `validator_rejects_malformed_url_without_aborting`.
    """
    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(url="https://[broken/AppSetup.exe", source="href")
    )

    assert result.ok is False
    assert result.reason == "invalid_url"


@pytest.mark.asyncio
@respx.mock
async def test_validator_rejects_extensionless_octet_stream(monkeypatch) -> None:
    """Comprueba el escenario `validator_rejects_extensionless_octet_stream`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    respx.head("https://tracking.example.net/opaque-download").mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "application/octet-stream", "content-length": "0"},
        )
    )
    respx.get("https://tracking.example.net/opaque-download").mock(
        return_value=httpx.Response(206, content=b"opaque telemetry payload")
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url="https://tracking.example.net/opaque-download",
            source="playwright_request",
        )
    )

    assert result.ok is False
    assert result.reason == "missing_installer_extension"


@pytest.mark.asyncio
@respx.mock
async def test_validator_infers_extensionless_winstall_pe_executable(monkeypatch) -> None:
    """Comprueba el escenario `validator_infers_extensionless_winstall_pe_executable`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    url = "https://frontend.example.com/download/bbk_cli_win_amd64-1.2.2"
    respx.head(url).mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "application/octet-stream", "content-length": "4096"},
        )
    )
    respx.get(url).mock(
        return_value=httpx.Response(
            206,
            headers={"content-range": "bytes 0-1023/4096"},
            content=b"MZ" + (b"\0" * 32),
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url=url,
            source="winstall_page",
            label="Download (.exe)",
            asset_kind="winstall_download",
        )
    )

    assert result.ok is True
    assert result.extension == ".exe"
    assert result.filename == "bbk_cli_win_amd64-1.2.2.exe"


@pytest.mark.asyncio
@respx.mock
async def test_validator_accepts_winstall_distribution_zip_outside_github_releases(
    monkeypatch,
) -> None:
    """Comprueba el escenario `validator_accepts_winstall_distribution_zip_outside_github_releases`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    url = "https://github.com/vendor/app/raw/master/dist/App-v140.zip"
    respx.head(url).mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "application/zip", "content-length": "4096"},
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url=url,
            source="winstall_page",
            label="Download (.zip)",
            asset_kind="winstall_download",
        )
    )

    assert result.ok is True
    assert result.extension == ".zip"


@pytest.mark.asyncio
async def test_validator_still_rejects_generic_github_zip_outside_releases() -> None:
    """Comprueba el escenario `validator_still_rejects_generic_github_zip_outside_releases`.
    """
    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url="https://github.com/vendor/app/raw/master/dist/App.zip",
            source="href",
            label="Download ZIP",
        )
    )

    assert result.ok is False
    assert result.reason == "github_zip_not_release_asset"


@pytest.mark.asyncio
@respx.mock
async def test_validator_accepts_public_ip_host_for_winstall_download() -> None:
    """Comprueba el escenario `validator_accepts_public_ip_host_for_winstall_download`.
    """
    url = "http://120.24.245.232/app/pcr532.exe"
    respx.head(url).mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "application/x-msdownload", "content-length": "4096"},
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url=url,
            source="winstall_page",
            label="Download (.exe)",
            asset_kind="winstall_download",
        )
    )

    assert result.ok is True
    assert result.final_domain == "120.24.245.232"


@pytest.mark.asyncio
@respx.mock
async def test_validator_rejects_known_non_desktop_binary_extensions(monkeypatch) -> None:
    """Comprueba el escenario `validator_rejects_known_non_desktop_binary_extensions`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    respx.head("https://store.example.com/steamlink-android.apk").mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "application/octet-stream", "content-length": "4096"},
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url="https://store.example.com/steamlink-android.apk",
            source="href",
        ),
    )

    assert result.ok is False
    assert result.reason == "unsupported_extension:.apk"


@pytest.mark.asyncio
@respx.mock
async def test_validator_accepts_msixbundle(monkeypatch) -> None:
    """Comprueba el escenario `validator_accepts_msixbundle`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    url = "https://staticcdn.duckduckgo.com/release/DuckDuckGo_0.164.1.0.msixbundle"
    respx.head(url).mock(
        return_value=httpx.Response(
            200,
            headers={
                "content-type": "application/msixbundle",
                "content-length": "1048576",
            },
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(url=url, source="winstall_page", asset_kind="winstall_download")
    )

    assert result.ok is True
    assert result.extension == ".msixbundle"


@pytest.mark.asyncio
@respx.mock
async def test_validator_accepts_verified_winstall_http_installer(monkeypatch) -> None:
    """Comprueba el escenario `validator_accepts_verified_winstall_http_installer`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    respx.head(
        "http://storage.cevio.jp/product/CeVIO_Voice_Ginsaki_Yamato_Setup_(1.1.2).msi"
    ).mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "application/octet-stream", "content-length": "1942016"},
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url="http://storage.cevio.jp/product/CeVIO_Voice_Ginsaki_Yamato_Setup_(1.1.2).msi",
            source="winstall_page",
            asset_kind="winstall_download",
        ),
    )

    assert result.ok is True
    assert result.extension == ".msi"
    assert result.transport_security == "http_winstall_verified"


@pytest.mark.asyncio
@respx.mock
async def test_validator_allows_verified_winstall_redirect_to_public_cdn(monkeypatch) -> None:
    """Comprueba el escenario `validator_allows_verified_winstall_redirect_to_public_cdn`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    respx.head("https://codesector.com/files/teracopy3.10.exe").mock(
        return_value=httpx.Response(
            302,
            headers={
                "location": "https://codesector.nyc3.cdn.digitaloceanspaces.com/teracopy3.10.exe"
            },
        )
    )
    respx.head(
        "https://codesector.nyc3.cdn.digitaloceanspaces.com/teracopy3.10.exe"
    ).mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "application/x-msdownload", "content-length": "11605880"},
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url="https://codesector.com/files/teracopy3.10.exe",
            source="winstall_api",
            asset_kind="winstall_download",
        ),
    )

    assert result.ok is True
    assert result.final_domain == "digitaloceanspaces.com"
    assert result.extension == ".exe"


@pytest.mark.asyncio
@respx.mock
async def test_validator_accepts_visible_winstall_installer_blocked_by_cloudflare(
    monkeypatch,
) -> None:
    """Comprueba el escenario `validator_accepts_visible_winstall_installer_blocked_by_cloudflare`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    url = (
        "https://sourceforge.net/projects/beebeep/files/Windows/"
        "beebeep-setup-5.8.6.exe/download"
    )
    respx.head(url).mock(
        return_value=httpx.Response(200, headers={"content-type": "text/html"})
    )
    respx.get(url).mock(
        return_value=httpx.Response(
            403,
            headers={"server": "cloudflare", "content-type": "text/html"},
            content=b"<html><a href='/cdn-cgi/challenge-platform/'>Challenge</a></html>",
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url=url,
            source="winstall_page",
            label="Download (.inno)",
            asset_kind="winstall_download",
        )
    )

    assert result.ok is True
    assert result.filename == "beebeep-setup-5.8.6.exe"
    assert result.extension == ".exe"
    assert result.transport_security == "https_winstall_edge_attested"
    assert result.confidence == ValidationConfidence.ATTESTED


@pytest.mark.asyncio
@respx.mock
async def test_validator_attests_siteground_challenge_with_winstall_declared_extension(
    monkeypatch,
) -> None:
    """Comprueba la validación de un reto de SiteGround con la extensión declarada.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    url = "https://www.uniconta.com/download/uniconta-setup-msi/?wpdmdl=17651"
    challenge = (
        b'<html><meta http-equiv="refresh" content="0;/.well-known/sgcaptcha/'
        b'?r=%2Fdownload%2Funiconta-setup-msi"></html>'
    )
    respx.head(url).mock(
        return_value=httpx.Response(202, headers={"content-type": "text/html"})
    )
    respx.get(url).mock(
        return_value=httpx.Response(
            202,
            headers={"content-type": "text/html"},
            content=challenge,
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url=url,
            source="winstall_page",
            label="Download (.msi)",
            asset_kind="winstall_download",
        )
    )

    assert result.ok is True
    assert result.extension == ".msi"
    assert result.filename == "uniconta-setup-msi.msi"
    assert result.transport_security == "https_winstall_edge_attested"


@pytest.mark.asyncio
@respx.mock
async def test_validator_attests_tencent_edgeone_challenge(monkeypatch) -> None:
    """Comprueba el escenario `validator_attests_tencent_edgeone_challenge`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    url = "https://cdn.example.com/AppSetup.exe"
    respx.head(url).mock(
        return_value=httpx.Response(200, headers={"content-type": "text/html"})
    )
    respx.get(url).mock(
        return_value=httpx.Response(
            200,
            headers={"content-type": "text/html"},
            content=(
                b"<html><title>Security Verification</title>"
                b"<script src='TEOCaptchaWidget-global.js'></script>"
                b"Protected by Tencent Cloud EdgeOne</html>"
            ),
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url=url,
            source="winstall_page",
            label="Download (.exe)",
            asset_kind="winstall_download",
        )
    )

    assert result.ok is True
    assert result.extension == ".exe"
    assert result.transport_security == "https_winstall_edge_attested"


@pytest.mark.asyncio
@respx.mock
async def test_validator_attests_akamai_edge_denial_for_winstall_binary(monkeypatch) -> None:
    """Comprueba el escenario `validator_attests_akamai_edge_denial_for_winstall_binary`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    url = "https://downloads.example.com/AppSetup.msi"
    denied = (
        b"<html><title>Access Denied</title>"
        b"https&#58;&#47;&#47;errors&#46;edgesuite&#46;net&#47;18</html>"
    )
    respx.head(url).mock(
        return_value=httpx.Response(
            403,
            headers={"server": "AkamaiGHost", "content-type": "text/html"},
        )
    )
    respx.get(url).mock(
        return_value=httpx.Response(
            403,
            headers={"server": "AkamaiGHost", "content-type": "text/html"},
            content=denied,
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url=url,
            source="winstall_page",
            label="Download (.msi)",
            asset_kind="winstall_download",
        )
    )

    assert result.ok is True
    assert result.extension == ".msi"
    assert result.transport_security == "https_winstall_edge_attested"


@pytest.mark.asyncio
@respx.mock
async def test_validator_does_not_attest_generic_cloudflare_candidate(monkeypatch) -> None:
    """Comprueba el escenario `validator_does_not_attest_generic_cloudflare_candidate`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    url = "https://downloads.example.com/AppSetup.exe"
    respx.head(url).mock(
        return_value=httpx.Response(200, headers={"content-type": "text/html"})
    )
    respx.get(url).mock(
        return_value=httpx.Response(
            403,
            headers={"server": "cloudflare", "content-type": "text/html"},
            content=b"<html>/cdn-cgi/challenge-platform</html>",
        )
    )

    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(url=url, source="href", label="Download installer")
    )

    assert result.ok is False
    assert result.reason == "http_403"


@pytest.mark.asyncio
async def test_validator_rejects_non_winstall_http_before_network() -> None:
    """Comprueba el escenario `validator_rejects_non_winstall_http_before_network`.
    """
    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url="http://downloads.example.com/AppSetup.exe",
            source="href",
        ),
    )

    assert result.ok is False
    assert result.reason == "unsupported_scheme"


@pytest.mark.asyncio
async def test_validator_retries_verified_winstall_tls_failure_over_http(monkeypatch) -> None:
    """Comprueba el escenario `validator_retries_verified_winstall_tls_failure_over_http`.

    Args:
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    async def public_dns(_hostname: str | None) -> bool:
        """Ejecuta la operación `public_dns`.

        Args:
            _hostname (str | None): Valor de `_hostname` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return True

    async def handler(request: httpx.Request) -> httpx.Response:
        """Ejecuta la operación `handler`.

        Args:
            request (httpx.Request): Solicitud recibida por la operación.

        Returns:
            httpx.Response: Resultado producido por la operación.

        Throws:
            httpx.ConnectError: Si no puede completarse la operación bajo las condiciones
                requeridas.
        """
        if request.url.scheme == "https":
            raise httpx.ConnectError(
                "[SSL: CERTIFICATE_VERIFY_FAILED] certificate verify failed",
                request=request,
            )
        return httpx.Response(
            206,
            headers={"content-type": "application/octet-stream", "content-length": "1024"},
            request=request,
        )

    monkeypatch.setattr("app.scraper.validator.domain_has_public_dns", public_dns)
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler), follow_redirects=False)
    try:
        result = await DownloadValidator(Settings(), client).validate(
            InstallerCandidate(
                url="https://downloads.example.com/Concept2UtilitySetup.exe",
                source="winstall_page",
                asset_kind="winstall_download",
            )
        )
    finally:
        await client.aclose()

    assert result.ok is True
    assert result.final_url == "http://downloads.example.com/Concept2UtilitySetup.exe"
    assert result.transport_security == "http_winstall_verified"
