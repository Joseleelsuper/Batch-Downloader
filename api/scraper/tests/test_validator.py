import pytest
import httpx
import respx

from app.core.config import Settings
from app.scraper.candidates import InstallerCandidate
from app.scraper.validator import DownloadValidator, domain_has_public_dns, is_public_ip


@pytest.mark.asyncio
async def test_domain_has_public_dns_rejects_loopback_literal() -> None:
    assert await domain_has_public_dns("127.0.0.1") is False


def test_private_ips_are_not_public() -> None:
    import ipaddress

    assert is_public_ip(ipaddress.ip_address("10.0.0.1")) is False
    assert is_public_ip(ipaddress.ip_address("192.168.1.10")) is False


@pytest.mark.asyncio
async def test_validator_rejects_github_source_archives_before_network() -> None:
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
    async def public_dns(_hostname: str | None) -> bool:
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


@pytest.mark.asyncio
@respx.mock
async def test_validator_preserves_candidate_filename_when_redirect_hides_it(monkeypatch) -> None:
    async def public_dns(_hostname: str | None) -> bool:
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
async def test_validator_accepts_public_cross_domain_redirect_without_allowlist(monkeypatch) -> None:
    async def public_dns(_hostname: str | None) -> bool:
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
async def test_validator_rejects_known_non_desktop_binary_extensions(monkeypatch) -> None:
    async def public_dns(_hostname: str | None) -> bool:
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
async def test_validator_accepts_verified_winstall_http_installer(monkeypatch) -> None:
    async def public_dns(_hostname: str | None) -> bool:
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
    async def public_dns(_hostname: str | None) -> bool:
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
async def test_validator_accepts_visible_winstall_installer_blocked_by_cloudflare(monkeypatch) -> None:
    async def public_dns(_hostname: str | None) -> bool:
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


@pytest.mark.asyncio
@respx.mock
async def test_validator_does_not_attest_generic_cloudflare_candidate(monkeypatch) -> None:
    async def public_dns(_hostname: str | None) -> bool:
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
    async def public_dns(_hostname: str | None) -> bool:
        return True

    async def handler(request: httpx.Request) -> httpx.Response:
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
