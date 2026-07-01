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
        allowed_domains={"github.com"},
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
        allowed_domains={"github.com", "githubusercontent.com"},
    )

    assert result.ok is True
    assert result.final_domain == "githubusercontent.com"


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
        allowed_domains={"cevio.jp"},
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
        allowed_domains={"codesector.com"},
    )

    assert result.ok is True
    assert result.final_domain == "digitaloceanspaces.com"
    assert result.extension == ".exe"


@pytest.mark.asyncio
async def test_validator_rejects_non_winstall_http_before_network() -> None:
    result = await DownloadValidator(Settings()).validate(
        InstallerCandidate(
            url="http://downloads.example.com/AppSetup.exe",
            source="href",
        ),
        allowed_domains={"example.com"},
    )

    assert result.ok is False
    assert result.reason == "unsupported_scheme"
