from __future__ import annotations

import ipaddress
from dataclasses import dataclass
from urllib.parse import urljoin, urlparse

import dns.asyncresolver
import httpx

from app.core.config import Settings
from app.scraper.candidates import (
    InstallerCandidate,
    detect_extension,
    filename_from_url,
    is_github_release_asset,
    is_github_source_archive,
    registered_domain,
)

BINARY_CONTENT_TYPES = (
    "application/octet-stream",
    "application/x-msdownload",
    "application/x-msi",
    "application/x-ms-installer",
    "application/vnd.microsoft.portable-executable",
    "application/zip",
    "application/x-zip-compressed",
    "application/x-debian-package",
    "application/x-rpm",
    "application/x-apple-diskimage",
    "binary/octet-stream",
)


@dataclass(frozen=True)
class ValidationResult:
    ok: bool
    url: str
    final_url: str | None = None
    final_domain: str | None = None
    filename: str | None = None
    extension: str | None = None
    content_type: str | None = None
    size_bytes: int | None = None
    reason: str | None = None
    transport_security: str | None = None


class DownloadValidator:
    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        self.settings = settings
        self.client = client

    async def validate(
        self,
        candidate: InstallerCandidate,
        allowed_domains: set[str],
    ) -> ValidationResult:
        parsed = urlparse(candidate.url)
        if not self._scheme_allowed(candidate, parsed.scheme):
            return self._fail(candidate.url, "unsupported_scheme")
        domain = registered_domain(candidate.url)
        if not domain:
            return self._fail(candidate.url, "missing_domain")
        if is_github_source_archive(candidate.url):
            return self._fail(candidate.url, "github_source_archive")
        if (
            domain == "github.com"
            and detect_extension(candidate.url) == ".zip"
            and not is_github_release_asset(candidate.url)
        ):
            return self._fail(candidate.url, "github_zip_not_release_asset")
        if allowed_domains and domain not in allowed_domains:
            return self._fail(candidate.url, "domain_not_allowed")
        if not await domain_has_public_dns(parsed.hostname):
            return self._fail(candidate.url, "dns_not_public")

        owns_client = self.client is None
        client = self.client or httpx.AsyncClient(
            timeout=self.settings.request_timeout_seconds,
            follow_redirects=False,
            headers={"User-Agent": "BatchDownloaderScraper/0.1"},
        )
        try:
            return await self._validate_http(client, candidate, allowed_domains)
        finally:
            if owns_client:
                await client.aclose()

    async def _validate_http(
        self,
        client: httpx.AsyncClient,
        candidate: InstallerCandidate,
        allowed_domains: set[str],
    ) -> ValidationResult:
        current_url = candidate.url
        previous_url: str | None = None
        response: httpx.Response | None = None
        for _ in range(self.settings.max_redirects + 1):
            response = await request_metadata(client, current_url, referer=previous_url)
            if response.is_redirect:
                location = response.headers.get("location")
                if not location:
                    return self._fail(current_url, "redirect_without_location")
                previous_url = current_url
                current_url = urljoin(current_url, location)
                parsed = urlparse(current_url)
                if not self._scheme_allowed(candidate, parsed.scheme):
                    return self._fail(current_url, "redirect_unsupported_scheme")
                domain = registered_domain(current_url)
                if is_github_source_archive(current_url):
                    return self._fail(current_url, "redirect_github_source_archive")
                if (
                    domain == "github.com"
                    and detect_extension(current_url) == ".zip"
                    and not is_github_release_asset(current_url)
                ):
                    return self._fail(current_url, "redirect_github_zip_not_release_asset")
                if allowed_domains and not redirect_domain_allowed(
                    candidate=candidate,
                    allowed_domains=allowed_domains,
                    previous_url=previous_url,
                    redirected_domain=domain,
                ):
                    return self._fail(current_url, "redirect_domain_not_allowed")
                if not await domain_has_public_dns(parsed.hostname):
                    return self._fail(current_url, "redirect_dns_not_public")
                continue
            break
        else:
            return self._fail(current_url, "too_many_redirects")

        if response is None:
            return self._fail(current_url, "no_response")
        if response.status_code >= 400:
            return self._fail(current_url, f"http_{response.status_code}")

        content_type = response.headers.get("content-type", "").split(";")[0].strip().lower()
        content_length = response.headers.get("content-length")
        size_bytes = int(content_length) if content_length and content_length.isdigit() else None
        if size_bytes and size_bytes > self.settings.max_download_size_bytes:
            return self._fail(current_url, "file_too_large")

        extension = detect_extension(current_url) or detect_extension(
            response.headers.get("content-disposition", "")
        )
        disposition = response.headers.get("content-disposition", "").lower()
        looks_binary = content_type in BINARY_CONTENT_TYPES or "attachment" in disposition
        if content_type.startswith("text/html"):
            return self._fail(current_url, "html_response")
        if not extension and not looks_binary:
            return self._fail(current_url, "not_an_installer")

        return ValidationResult(
            ok=True,
            url=candidate.url,
            final_url=str(response.url),
            final_domain=registered_domain(str(response.url)),
            filename=filename_from_url(str(response.url)),
            extension=extension,
            content_type=content_type or None,
            size_bytes=size_bytes,
            transport_security=transport_security_for(str(response.url), candidate),
        )

    def _fail(self, url: str, reason: str) -> ValidationResult:
        return ValidationResult(ok=False, url=url, reason=reason)

    def _scheme_allowed(self, candidate: InstallerCandidate, scheme: str) -> bool:
        if scheme in self.settings.allowed_download_schemes:
            return True
        return scheme == "http" and is_verified_winstall_candidate(candidate)


def is_verified_winstall_candidate(candidate: InstallerCandidate) -> bool:
    return candidate.source in {"winstall_api", "winstall_page"} or (
        candidate.asset_kind == "winstall_download"
    )


def transport_security_for(url: str, candidate: InstallerCandidate) -> str | None:
    if urlparse(url).scheme == "http" and is_verified_winstall_candidate(candidate):
        return "http_winstall_verified"
    return None


def redirect_domain_allowed(
    candidate: InstallerCandidate,
    allowed_domains: set[str],
    previous_url: str | None,
    redirected_domain: str | None,
) -> bool:
    if not redirected_domain:
        return False
    if redirected_domain in allowed_domains:
        return True
    previous_domain = registered_domain(previous_url or candidate.url)
    return bool(
        is_verified_winstall_candidate(candidate)
        and previous_domain
        and previous_domain in allowed_domains
    )


def metadata_headers(referer: str | None = None, *, partial: bool = False) -> dict[str, str]:
    headers = {
        "Accept": "application/octet-stream,application/x-msdownload,application/x-msi,*/*",
        "Accept-Language": "en-US,en;q=0.9,es;q=0.8",
        "User-Agent": "Mozilla/5.0 BatchDownloaderScraper/0.1",
    }
    if referer:
        headers["Referer"] = referer
    if partial:
        headers["Range"] = "bytes=0-1023"
    return headers


async def request_metadata(
    client: httpx.AsyncClient,
    url: str,
    referer: str | None = None,
) -> httpx.Response:
    response = await client.head(url, headers=metadata_headers(referer))
    if not response.is_redirect and (response.status_code in {405, 403} or (
        response.status_code < 400 and not response.headers.get("content-type")
    )):
        response = await client.get(url, headers=metadata_headers(referer, partial=True))
    return response


async def domain_has_public_dns(hostname: str | None) -> bool:
    if not hostname:
        return False
    try:
        ip = ipaddress.ip_address(hostname)
        return is_public_ip(ip)
    except ValueError:
        pass

    try:
        answers = await dns.asyncresolver.resolve(hostname, "A")
    except Exception:
        return False
    return bool(answers) and all(is_public_ip(ipaddress.ip_address(answer.address)) for answer in answers)


def is_public_ip(ip: ipaddress._BaseAddress) -> bool:
    return not (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_multicast
        or ip.is_reserved
        or ip.is_unspecified
    )
