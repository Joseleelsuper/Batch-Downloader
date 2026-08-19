"""Implementa las responsabilidades del módulo `validator`.
"""
from __future__ import annotations

import asyncio
import ipaddress
import re
import time
from dataclasses import dataclass, replace
from enum import StrEnum
from pathlib import PurePosixPath
from urllib.parse import unquote, urljoin, urlparse, urlunparse

import dns.asyncresolver
import dns.resolver
import httpx

from app.core.config import Settings
from app.scraper.artifacts import (
    DEFAULT_ARTIFACT_FORMAT_REGISTRY,
    GENERIC_BINARY_MEDIA_TYPES,
    ArtifactFormatRegistry,
)
from app.scraper.candidates import (
    PREFERRED_EXTENSIONS,
    UNSUPPORTED_DOWNLOAD_EXTENSIONS,
    InstallerCandidate,
    candidate_has_download_intent,
    detect_extension,
    filename_from_url,
    is_github_release_asset,
    is_github_source_archive,
    registered_domain,
)

BINARY_CONTENT_TYPES = (
    DEFAULT_ARTIFACT_FORMAT_REGISTRY.binary_media_types | GENERIC_BINARY_MEDIA_TYPES
)
"""Constante que define `BINARY_CONTENT_TYPES`.
"""

DNS_POSITIVE_TTL_SECONDS = 600.0
"""Constante que define `DNS_POSITIVE_TTL_SECONDS`.
"""
DNS_NEGATIVE_TTL_SECONDS = 20.0
"""Constante que define `DNS_NEGATIVE_TTL_SECONDS`.
"""
_DNS_CACHE: dict[str, tuple[float, bool]] = {}
"""Constante que define `_DNS_CACHE`.
"""
_DNS_INFLIGHT: dict[tuple[int, str], asyncio.Task[bool]] = {}
"""Constante que define `_DNS_INFLIGHT`.
"""
_SOURCEFORGE_LOCKS: dict[asyncio.AbstractEventLoop, asyncio.Lock] = {}
_SOURCEFORGE_NEXT_REQUEST: dict[asyncio.AbstractEventLoop, float] = {}
SOURCEFORGE_MIN_INTERVAL_SECONDS = 1.0
BROWSER_COMPATIBLE_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/139.0.0.0 Safari/537.36"
)


class ValidationConfidence(StrEnum):
    """Enumera los valores admitidos por `ValidationConfidence`.
    """
    UNVERIFIED = "unverified"
    """Constante que define `UNVERIFIED`.
    """
    VALIDATED = "validated"
    """Constante que define `VALIDATED`.
    """
    ATTESTED = "attested"
    """Constante que define `ATTESTED`.
    """


@dataclass(frozen=True)
class ValidationResult:
    """Representa el resultado de `Validation`.
    """
    ok: bool
    """Atributo de clase `ok` de `ValidationResult`.
    """
    url: str
    """Atributo de clase `url` de `ValidationResult`.
    """
    final_url: str | None = None
    """Atributo de clase `final_url` de `ValidationResult`.
    """
    final_domain: str | None = None
    """Atributo de clase `final_domain` de `ValidationResult`.
    """
    filename: str | None = None
    """Atributo de clase `filename` de `ValidationResult`.
    """
    extension: str | None = None
    """Atributo de clase `extension` de `ValidationResult`.
    """
    content_type: str | None = None
    """Atributo de clase `content_type` de `ValidationResult`.
    """
    size_bytes: int | None = None
    """Atributo de clase `size_bytes` de `ValidationResult`.
    """
    reason: str | None = None
    """Atributo de clase `reason` de `ValidationResult`.
    """
    transport_security: str | None = None
    """Atributo de clase `transport_security` de `ValidationResult`.
    """
    confidence: ValidationConfidence = ValidationConfidence.UNVERIFIED
    """Atributo de clase `confidence` de `ValidationResult`.
    """


class DownloadValidator:
    """Representa el componente `DownloadValidator`.
    """
    def __init__(
        self,
        settings: Settings,
        client: httpx.AsyncClient | None = None,
        formats: ArtifactFormatRegistry = DEFAULT_ARTIFACT_FORMAT_REGISTRY,
    ) -> None:
        """Inicializa una instancia de `DownloadValidator`.

        Args:
            settings (Settings): Configuración del servicio.
            client (httpx.AsyncClient | None): Cliente utilizado para ejecutar el escenario.
            formats (ArtifactFormatRegistry): Valor de `formats` utilizado por la operación.
        """
        self.settings = settings
        """Estado de instancia asociado a `settings`.
        """
        self.client = client
        """Estado de instancia asociado a `client`.
        """
        self.formats = formats
        """Estado de instancia asociado a `formats`.
        """

    async def validate(
        self,
        candidate: InstallerCandidate,
        *,
        require_signature: bool = False,
    ) -> ValidationResult:
        """Ejecuta `validate` dentro de `DownloadValidator`.

        Args:
            candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.
            require_signature (bool): Valor de `require_signature` utilizado por la operación.

        Returns:
            ValidationResult: Resultado producido por la operación.
        """
        try:
            parsed = urlparse(candidate.url)
            hostname = parsed.hostname
        except ValueError:
            return self._fail(candidate.url, "invalid_url")
        if not self._scheme_allowed(candidate, parsed.scheme):
            return self._fail(candidate.url, "unsupported_scheme")
        if not hostname:
            return self._fail(candidate.url, "missing_domain")
        if parsed.username is not None or parsed.password is not None:
            return self._fail(candidate.url, "url_credentials_forbidden")
        if is_non_binary_installer_reference(candidate.url):
            return self._fail(candidate.url, "non_binary_installer_reference")
        if is_github_source_archive(candidate.url):
            return self._fail(candidate.url, "github_source_archive")
        if (
            hostname.lower().endswith("github.com")
            and detect_extension(candidate.url) == ".zip"
            and not is_github_release_asset(candidate.url)
            and not is_verified_winstall_candidate(candidate)
        ):
            return self._fail(candidate.url, "github_zip_not_release_asset")
        if not await domain_has_public_dns(hostname):
            return self._fail(candidate.url, "dns_not_public")

        owns_client = self.client is None
        client = self.client or httpx.AsyncClient(
            timeout=self.settings.request_timeout_seconds,
            follow_redirects=False,
            headers={"User-Agent": "BatchDownloaderScraper/0.1"},
        )
        try:
            try:
                return await self._validate_http(
                    client,
                    candidate,
                    require_signature=require_signature,
                )
            except httpx.ConnectError as exc:
                http_candidate = winstall_http_tls_fallback(candidate, exc)
                if http_candidate is None:
                    raise
                return await self._validate_http(
                    client,
                    http_candidate,
                    require_signature=require_signature,
                )
        finally:
            if owns_client:
                await client.aclose()

    async def _validate_http(
        self,
        client: httpx.AsyncClient,
        candidate: InstallerCandidate,
        *,
        require_signature: bool,
    ) -> ValidationResult:
        """Ejecuta el paso interno `_validate_http`.

        Args:
            client (httpx.AsyncClient): Cliente utilizado para ejecutar el escenario.
            candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.
            require_signature (bool): Valor de `require_signature` utilizado por la operación.

        Returns:
            ValidationResult: Resultado producido por la operación.
        """
        current_url = candidate.url
        previous_url: str | None = None
        response: httpx.Response | None = None
        for _ in range(self.settings.max_redirects + 1):
            request_referer = previous_url or same_site_referer(current_url, candidate.referer)
            response = await request_metadata(
                client,
                current_url,
                referer=request_referer,
                probe_html=candidate_has_download_intent(candidate),
            )
            if response.is_redirect:
                location = response.headers.get("location")
                if not location:
                    return self._fail(current_url, "redirect_without_location")
                previous_url = current_url
                try:
                    current_url = urljoin(current_url, location)
                except ValueError:
                    return self._fail(current_url, "redirect_invalid_url")
                try:
                    parsed = urlparse(current_url)
                    hostname = parsed.hostname
                except ValueError:
                    return self._fail(current_url, "redirect_invalid_url")
                if not self._scheme_allowed(candidate, parsed.scheme):
                    return self._fail(current_url, "redirect_unsupported_scheme")
                if not hostname:
                    return self._fail(current_url, "redirect_missing_domain")
                if parsed.username is not None or parsed.password is not None:
                    return self._fail(current_url, "redirect_url_credentials_forbidden")
                if is_github_source_archive(current_url):
                    return self._fail(current_url, "redirect_github_source_archive")
                if (
                    hostname.lower().endswith("github.com")
                    and detect_extension(current_url) == ".zip"
                    and not is_github_release_asset(current_url)
                    and not is_verified_winstall_candidate(candidate)
                ):
                    return self._fail(current_url, "redirect_github_zip_not_release_asset")
                if not await domain_has_public_dns(hostname):
                    return self._fail(current_url, "redirect_dns_not_public")
                continue
            break
        else:
            return self._fail(current_url, "too_many_redirects")

        if response is None:
            return self._fail(current_url, "no_response")
        if response.status_code >= 400:
            attested = self._winstall_edge_attested_result(candidate, current_url, response)
            if attested:
                return attested
            return self._fail(current_url, f"http_{response.status_code}")

        content_type = response.headers.get("content-type", "").split(";")[0].strip().lower()
        size_bytes = response_size_bytes(response)
        if size_bytes and size_bytes > self.settings.max_download_size_bytes:
            return self._fail(current_url, "file_too_large")

        disposition = response.headers.get("content-disposition", "")
        filename = (
            filename_from_content_disposition(disposition)
            or filename_from_url(str(response.url))
            or filename_from_url(candidate.url)
        )
        extension = (
            detect_extension(current_url)
            or detect_extension(disposition)
            or detect_extension(filename or "")
            or candidate.extension
        )
        unsupported_extension = unsupported_filename_extension(filename or str(response.url))
        if unsupported_extension:
            return self._fail(current_url, f"unsupported_extension:{unsupported_extension}")
        disposition = response.headers.get("content-disposition", "").lower()
        looks_binary = (
            content_type in self.formats.binary_media_types | GENERIC_BINARY_MEDIA_TYPES
            or "attachment" in disposition
        )
        if content_type.startswith("text/html"):
            attested = self._winstall_edge_attested_result(candidate, current_url, response)
            if attested:
                return attested
            return self._fail(current_url, "html_response")
        signature_response: httpx.Response | None = None
        if not extension:
            signature_response = response
            if not signature_response.content:
                signature_response = await request_partial(
                    client,
                    current_url,
                    referer=previous_url or same_site_referer(current_url, candidate.referer),
                )
            if signature_response.status_code >= 400:
                return self._fail(current_url, f"http_{signature_response.status_code}")
            extension = self.formats.infer_extension(signature_response.content)
            if not extension or not candidate_has_download_intent(candidate):
                return self._fail(current_url, "missing_installer_extension")
            filename = filename or filename_for_inferred_extension(current_url, extension)
            looks_binary = True
        if require_signature:
            artifact_format = self.formats.get(extension)
            if artifact_format is None:
                return self._fail(current_url, "unsupported_installer_format")
            signature_response = signature_response or response
            if artifact_format.signatures:
                if not signature_response.content:
                    signature_response = await request_partial(
                        client,
                        current_url,
                        referer=previous_url
                        or same_site_referer(current_url, candidate.referer),
                    )
                if signature_response.status_code >= 400:
                    return self._fail(
                        current_url,
                        f"http_{signature_response.status_code}",
                    )
                if not self.formats.matches_signature(extension, signature_response.content):
                    return self._fail(current_url, "installer_signature_mismatch")
            elif (
                content_type not in set(artifact_format.media_types)
                and content_type not in GENERIC_BINARY_MEDIA_TYPES
                and "attachment" not in disposition
            ):
                return self._fail(current_url, "installer_content_type_mismatch")
        if not looks_binary:
            signature_response = signature_response or response
            if not signature_response.content:
                signature_response = await request_partial(
                    client,
                    current_url,
                    referer=previous_url or same_site_referer(current_url, candidate.referer),
                )
            if signature_response.status_code >= 400:
                return self._fail(current_url, "not_an_installer")
            if not self.formats.matches_signature(extension, signature_response.content):
                actual_extension = self.formats.infer_extension(signature_response.content)
                if not actual_extension or not is_verified_winstall_candidate(candidate):
                    return self._fail(current_url, "not_an_installer")
                extension = actual_extension
                filename = filename_with_actual_extension(filename, current_url, extension)

        return ValidationResult(
            ok=True,
            url=candidate.url,
            final_url=str(response.url),
            final_domain=download_host(str(response.url)),
            filename=filename,
            extension=extension,
            content_type=content_type or None,
            size_bytes=size_bytes,
            transport_security=transport_security_for(str(response.url), candidate),
            confidence=ValidationConfidence.VALIDATED,
        )

    def _fail(self, url: str, reason: str) -> ValidationResult:
        """Ejecuta el paso interno `_fail`.

        Args:
            url (str): URL del recurso que debe procesarse.
            reason (str): Valor de `reason` utilizado por la operación.

        Returns:
            ValidationResult: Resultado producido por la operación.
        """
        return ValidationResult(ok=False, url=url, reason=reason)

    def _scheme_allowed(self, candidate: InstallerCandidate, scheme: str) -> bool:
        """Ejecuta el paso interno `_scheme_allowed`.

        Args:
            candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.
            scheme (str): Valor de `scheme` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        if scheme in self.settings.allowed_download_schemes:
            return True
        return scheme == "http" and is_verified_winstall_candidate(candidate)

    def _winstall_edge_attested_result(
        self,
        candidate: InstallerCandidate,
        current_url: str,
        response: httpx.Response,
    ) -> ValidationResult | None:
        """Ejecuta el paso interno `_winstall_edge_attested_result`.

        Args:
            candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.
            current_url (str): Dirección de `current` que debe procesarse.
            response (httpx.Response): Respuesta que debe procesarse.

        Returns:
            ValidationResult | None: Resultado producido por la operación.
        """
        extension = (
            detect_extension(current_url)
            or candidate.extension
            or declared_candidate_extension(candidate)
        )
        if not (
            is_verified_winstall_candidate(candidate)
            and urlparse(current_url).scheme == "https"
            and extension in self.formats.extensions
            and is_edge_challenge(response)
        ):
            return None
        return ValidationResult(
            ok=True,
            url=candidate.url,
            final_url=current_url,
            final_domain=download_host(current_url),
            filename=(
                filename_from_url(current_url)
                or filename_from_url(candidate.url)
                or filename_for_inferred_extension(current_url, extension)
            ),
            extension=extension,
            transport_security="https_winstall_edge_attested",
            confidence=ValidationConfidence.ATTESTED,
        )


def is_verified_winstall_candidate(candidate: InstallerCandidate) -> bool:
    """Indica si se cumple la operación `verified_winstall_candidate`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    return candidate.source in {"winstall_api", "winstall_page"} or (
        candidate.asset_kind == "winstall_download"
    )


def winstall_http_tls_fallback(
    candidate: InstallerCandidate,
    error: httpx.ConnectError,
) -> InstallerCandidate | None:
    """Ejecuta la operación `winstall_http_tls_fallback`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.
        error (httpx.ConnectError): Error que debe registrarse o propagarse.

    Returns:
        InstallerCandidate | None: Resultado producido por la operación.
    """
    parsed = urlparse(candidate.url)
    if not (
        is_verified_winstall_candidate(candidate)
        and parsed.scheme == "https"
        and "certificate verify failed" in str(error).lower()
    ):
        return None
    return replace(candidate, url=urlunparse(parsed._replace(scheme="http")))


def transport_security_for(url: str, candidate: InstallerCandidate) -> str | None:
    """Ejecuta la operación `transport_security_for`.

    Args:
        url (str): URL del recurso que debe procesarse.
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.

    Returns:
        str | None: Resultado producido por la operación.
    """
    if urlparse(url).scheme == "http" and is_verified_winstall_candidate(candidate):
        return "http_winstall_verified"
    return None


def metadata_headers(referer: str | None = None, *, partial: bool = False) -> dict[str, str]:
    """Ejecuta la operación `metadata_headers`.

    Args:
        referer (str | None): Valor de `referer` utilizado por la operación.
        partial (bool): Valor de `partial` utilizado por la operación.

    Returns:
        dict[str, str]: Mapa con los datos producidos por la operación.
    """
    headers = {
        "Accept": "application/octet-stream,application/x-msdownload,application/x-msi,*/*",
        "Accept-Language": "en-US,en;q=0.9,es;q=0.8",
        "User-Agent": BROWSER_COMPATIBLE_USER_AGENT,
    }
    if referer:
        headers["Referer"] = referer
    if partial:
        headers["Range"] = "bytes=0-1023"
        headers["Accept-Encoding"] = "identity"
    return headers


async def request_metadata(
    client: httpx.AsyncClient,
    url: str,
    referer: str | None = None,
    *,
    probe_html: bool = False,
) -> httpx.Response:
    """Ejecuta la operación `request_metadata`.

    Args:
        client (httpx.AsyncClient): Cliente utilizado para ejecutar el escenario.
        url (str): URL del recurso que debe procesarse.
        referer (str | None): Valor de `referer` utilizado por la operación.
        probe_html (bool): Valor de `probe_html` utilizado por la operación.

    Returns:
        httpx.Response: Resultado producido por la operación.
    """
    if is_sourceforge_download_url(url):
        return await request_sourceforge_metadata(client, url, referer=referer)

    response = await client.head(url, headers=metadata_headers(referer))
    content_type = response.headers.get("content-type", "").lower()
    if not response.is_redirect and (
        response.status_code in {405, 403}
        or (response.status_code < 400 and not content_type)
        or (
            probe_html
            and content_type
            and content_type.split(";", 1)[0].strip() not in BINARY_CONTENT_TYPES
        )
    ):
        response = await request_partial(client, url, referer=referer)
    return response


async def request_sourceforge_metadata(
    client: httpx.AsyncClient,
    url: str,
    *,
    referer: str | None = None,
) -> httpx.Response:
    """Sigue el contrato de descarga de SourceForge sin disparar su protección.

    SourceForge documenta GET con redirección a su red de mirrors. Los HEAD
    paralelos suelen devolver una página de desafío y no el artefacto. Se serializa
    este proveedor y se descarga solo el prefijo necesario para validar la firma.
    """
    loop = asyncio.get_running_loop()
    lock = _SOURCEFORGE_LOCKS.setdefault(loop, asyncio.Lock())
    async with lock:
        delay = _SOURCEFORGE_NEXT_REQUEST.get(loop, 0.0) - loop.time()
        if delay > 0:
            await asyncio.sleep(delay)
        try:
            return await request_partial(client, url, referer=referer)
        finally:
            _SOURCEFORGE_NEXT_REQUEST[loop] = (
                loop.time() + SOURCEFORGE_MIN_INTERVAL_SECONDS
            )


def is_sourceforge_download_url(url: str) -> bool:
    """Identifica tanto la página de descarga como cualquiera de sus mirrors."""
    try:
        host = (urlparse(url).hostname or "").lower()
    except ValueError:
        return False
    return host == "sourceforge.net" or host.endswith(".sourceforge.net")


async def request_partial(
    client: httpx.AsyncClient,
    url: str,
    *,
    referer: str | None = None,
    max_bytes: int = 4096,
) -> httpx.Response:
    """Ejecuta la operación `request_partial`.

    Args:
        client (httpx.AsyncClient): Cliente utilizado para ejecutar el escenario.
        url (str): URL del recurso que debe procesarse.
        referer (str | None): Valor de `referer` utilizado por la operación.
        max_bytes (int): Valor de `max_bytes` utilizado por la operación.

    Returns:
        httpx.Response: Resultado producido por la operación.
    """
    async with client.stream(
        "GET",
        url,
        headers=metadata_headers(referer, partial=True),
    ) as streamed:
        content = bytearray()
        async for chunk in streamed.aiter_raw():
            remaining = max_bytes - len(content)
            if remaining <= 0:
                break
            content.extend(chunk[:remaining])
            if len(content) >= max_bytes:
                break
        return httpx.Response(
            streamed.status_code,
            headers=streamed.headers,
            content=bytes(content),
            request=streamed.request,
        )


def response_size_bytes(response: httpx.Response) -> int | None:
    """Ejecuta la operación `response_size_bytes`.

    Args:
        response (httpx.Response): Respuesta que debe procesarse.

    Returns:
        int | None: Resultado producido por la operación.
    """
    content_range = response.headers.get("content-range", "")
    match = re.search(r"/(\d+)\s*$", content_range)
    if match:
        return int(match.group(1))
    content_length = response.headers.get("content-length")
    return int(content_length) if content_length and content_length.isdigit() else None


def matches_installer_signature(extension: str, content: bytes) -> bool:
    """Ejecuta la operación `matches_installer_signature`.

    Args:
        extension (str): Valor de `extension` utilizado por la operación.
        content (bytes): Contenido que debe procesarse.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    return DEFAULT_ARTIFACT_FORMAT_REGISTRY.matches_signature(extension, content)


def infer_installer_extension(content: bytes) -> str | None:
    """Ejecuta la operación `infer_installer_extension`.

    Args:
        content (bytes): Contenido que debe procesarse.

    Returns:
        str | None: Resultado producido por la operación.
    """
    return DEFAULT_ARTIFACT_FORMAT_REGISTRY.infer_extension(content)


def declared_candidate_extension(candidate: InstallerCandidate) -> str | None:
    """Ejecuta la operación `declared_candidate_extension`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.

    Returns:
        str | None: Resultado producido por la operación.
    """
    text = f"{candidate.label or ''} {candidate.context or ''}".lower()
    for extension in sorted(PREFERRED_EXTENSIONS, key=len, reverse=True):
        if re.search(rf"(?<![a-z0-9]){re.escape(extension)}(?![a-z0-9])", text):
            return extension
    return None


def filename_for_inferred_extension(url: str, extension: str) -> str:
    """Ejecuta la operación `filename_for_inferred_extension`.

    Args:
        url (str): URL del recurso que debe procesarse.
        extension (str): Valor de `extension` utilizado por la operación.

    Returns:
        str: Resultado producido por la operación.
    """
    try:
        name = PurePosixPath(unquote(urlparse(url).path)).name
    except ValueError:
        name = ""
    name = re.sub(r"[^A-Za-z0-9._ -]+", "_", name).strip(" ._") or "download"
    return f"{name[: max(1, 255 - len(extension))]}{extension}"


def filename_with_actual_extension(
    filename: str | None,
    url: str,
    extension: str,
) -> str:
    """Ejecuta la operación `filename_with_actual_extension`.

    Args:
        filename (str | None): Valor de `filename` utilizado por la operación.
        url (str): URL del recurso que debe procesarse.
        extension (str): Valor de `extension` utilizado por la operación.

    Returns:
        str: Resultado producido por la operación.
    """
    if not filename:
        return filename_for_inferred_extension(url, extension)
    lowered = filename.lower()
    for declared in sorted(PREFERRED_EXTENSIONS, key=len, reverse=True):
        if lowered.endswith(declared):
            filename = filename[: -len(declared)]
            break
    return f"{filename[: max(1, 255 - len(extension))]}{extension}"


def download_host(url: str) -> str | None:
    """Ejecuta la operación `download_host`.

    Args:
        url (str): URL del recurso que debe procesarse.

    Returns:
        str | None: Resultado producido por la operación.
    """
    try:
        hostname = urlparse(url).hostname
    except ValueError:
        return None
    if not hostname:
        return None
    return registered_domain(url) or hostname.lower()


def same_site_referer(download_url: str, referer: str | None) -> str | None:
    """Ejecuta la operación `same_site_referer`.

    Args:
        download_url (str): Dirección de `download` que debe procesarse.
        referer (str | None): Valor de `referer` utilizado por la operación.

    Returns:
        str | None: Resultado producido por la operación.
    """
    if not referer:
        return None
    download_domain = registered_domain(download_url)
    referer_domain = registered_domain(referer)
    if download_domain and download_domain == referer_domain:
        return referer
    return None


def is_edge_challenge(response: httpx.Response) -> bool:
    """Indica si se cumple la operación `edge_challenge`.

    Args:
        response (httpx.Response): Respuesta que debe procesarse.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    headers = " ".join(
        value.lower()
        for key, value in response.headers.items()
        if key.lower() in {"server", "cf-ray", "cf-mitigated", "x-sucuri-id"}
    )
    if (
        "cloudflare" in headers
        or "akamaighost" in headers
        or "cf-ray" in response.headers
        or "cf-mitigated" in response.headers
    ):
        return True
    if not response.content:
        return False
    probe = response.content[:4096].lower()
    return any(
        marker in probe
        for marker in (
            b"/cdn-cgi/",
            b"just a moment",
            b"cloudflare",
            b"/.well-known/sgcaptcha/",
            b"/.within.website/",
            b"making sure you&#39;re not a bot",
            b"teocaptchawidget",
            b"protected by tencent cloud edgeone",
            b"security verification",
            b"errors&#46;edgesuite&#46;net",
            b"errors.edgesuite.net",
        )
    )


def filename_from_content_disposition(value: str | None) -> str | None:
    """Ejecuta la operación `filename_from_content_disposition`.

    Args:
        value (str | None): Valor que debe procesarse.

    Returns:
        str | None: Resultado producido por la operación.
    """
    if not value:
        return None
    match = re.search(r"filename\*\s*=\s*UTF-8''([^;]+)", value, flags=re.I)
    if match:
        filename = unquote(match.group(1).strip().strip('"'))
        return filename[:255] if filename and "." in filename else None
    match = re.search(r"filename\s*=\s*\"?([^\";]+)\"?", value, flags=re.I)
    if not match:
        return None
    filename = unquote(match.group(1).strip())
    return filename[:255] if filename and "." in filename else None


def unsupported_filename_extension(value: str | None) -> str | None:
    """Ejecuta la operación `unsupported_filename_extension`.

    Args:
        value (str | None): Valor que debe procesarse.

    Returns:
        str | None: Resultado producido por la operación.
    """
    if not value:
        return None
    try:
        parsed_path = unquote(urlparse(value).path or value).lower()
    except ValueError:
        return None
    if parsed_path.endswith(".tar.gz"):
        return None
    for extension in UNSUPPORTED_DOWNLOAD_EXTENSIONS:
        if parsed_path.endswith(extension):
            return extension
    return None


def is_non_binary_installer_reference(url: str) -> bool:
    """Rechaza tiendas, comandos y wrappers que no son un artefacto instalable."""
    try:
        parsed = urlparse(url)
    except ValueError:
        return True
    hostname = (parsed.hostname or "").lower()
    path = parsed.path.rstrip("/").lower()
    if hostname in {"winstall.app", "www.winstall.app"} and path == "/api/installer":
        return True
    if detect_extension(url):
        return False
    if hostname in {"apps.microsoft.com", "www.microsoft.com"} and (
        "/store/" in path or path.startswith("/detail/")
    ):
        return True
    return False


async def domain_has_public_dns(hostname: str | None) -> bool:
    """Ejecuta la operación `domain_has_public_dns`.

    Args:
        hostname (str | None): Valor de `hostname` utilizado por la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    if not hostname:
        return False
    try:
        ip = ipaddress.ip_address(hostname)
        return is_public_ip(ip)
    except ValueError:
        pass

    normalized = hostname.rstrip(".").lower()
    cached = _DNS_CACHE.get(normalized)
    now = time.monotonic()
    if cached and cached[0] > now:
        return cached[1]

    loop = asyncio.get_running_loop()
    key = (id(loop), normalized)
    task = _DNS_INFLIGHT.get(key)
    if task is None or task.done():
        task = loop.create_task(resolve_public_dns(normalized))
        _DNS_INFLIGHT[key] = task
    try:
        result = await asyncio.shield(task)
    finally:
        if task.done() and _DNS_INFLIGHT.get(key) is task:
            _DNS_INFLIGHT.pop(key, None)
    ttl = DNS_POSITIVE_TTL_SECONDS if result else DNS_NEGATIVE_TTL_SECONDS
    _DNS_CACHE[normalized] = (time.monotonic() + ttl, result)
    return result


async def resolve_public_dns(hostname: str) -> bool:
    """Resuelve la operación `public_dns`.

    Args:
        hostname (str): Valor de `hostname` utilizado por la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    for attempt in range(3):
        addresses: list[ipaddress.IPv4Address | ipaddress.IPv6Address] = []
        transient_failure = False
        for record_type in ("A", "AAAA"):
            try:
                answers = await dns.asyncresolver.resolve(
                    hostname,
                    record_type,
                    lifetime=4.0,
                )
            except dns.resolver.NXDOMAIN:
                return False
            except dns.resolver.NoAnswer:
                continue
            except Exception:
                transient_failure = True
                continue
            addresses.extend(ipaddress.ip_address(answer.address) for answer in answers)
        if addresses and not transient_failure:
            return all(is_public_ip(address) for address in addresses)
        if not transient_failure:
            return False
        if attempt < 2:
            await asyncio.sleep(0.15 * (attempt + 1))
    return False


def is_public_ip(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    """Indica si se cumple la operación `public_ip`.

    Args:
        ip (ipaddress.IPv4Address | ipaddress.IPv6Address): Valor de `ip` utilizado por la
            operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    return not (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_multicast
        or ip.is_reserved
        or ip.is_unspecified
    )
