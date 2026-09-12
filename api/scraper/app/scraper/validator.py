"""Valida destinos de instalador, redirecciones, formatos y evidencia binaria sin descargar el
archivo completo.
Distingue binario comprobado de evidencia Winstall atestiguada para que la resolución interna
pueda aplicar una confianza más estricta.

See Also:
    app.scraper.artifacts.ArtifactFormatRegistry: Define formatos y prefijos binarios.
    app.domain.source_resolution.source_trust_status: Decide qué confianza puede publicarse al
        worker.
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


DNS_POSITIVE_TTL_SECONDS = 600.0

DNS_NEGATIVE_TTL_SECONDS = 20.0

_DNS_CACHE: dict[str, tuple[float, bool]] = {}

_DNS_INFLIGHT: dict[tuple[int, str], asyncio.Task[bool]] = {}

_SOURCEFORGE_LOCKS: dict[asyncio.AbstractEventLoop, asyncio.Lock] = {}
_SOURCEFORGE_NEXT_REQUEST: dict[asyncio.AbstractEventLoop, float] = {}
SOURCEFORGE_MIN_INTERVAL_SECONDS = 1.0
BROWSER_COMPATIBLE_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/139.0.0.0 Safari/537.36"
)
SOURCEFORGE_USER_AGENT = "BatchDownloaderScraper/0.1"
"""Identificación no simulada que SourceForge admite para descargas parciales."""


class ValidationConfidence(StrEnum):
    """Expresa qué evidencia sostiene el resultado técnico de un candidato.

    Attributes:
        UNVERIFIED: No se obtuvo una comprobación aceptable.
        VALIDATED: La comprobación aceptó evidencia técnica del instalador.
        ATTESTED: El origen Winstall acredita el candidato, pero un desafío del servidor
            impide comprobar el binario.
    """

    UNVERIFIED = "unverified"

    VALIDATED = "validated"

    ATTESTED = "attested"



@dataclass(frozen=True)
class ValidationResult:
    """Devuelve aceptación, confianza y metadatos observados de un candidato sin persistirlos.

    Attributes:
        ok, reason: Resultado de aceptación y motivo del rechazo, cuando corresponde.
        url, final_url, final_domain: Destino inicial, destino observado y dominio publicable.
        filename, extension, content_type, size_bytes: Nombre, formato, MIME y tamaño total
            conocido en bytes.
        transport_security: Marca de excepción histórica Winstall, o None para el transporte
            ordinario.
        confidence: Garantía de comprobación; ok por sí solo no equivale a descarga HTTPS
            verificada.
    """

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

    confidence: ValidationConfidence = ValidationConfidence.UNVERIFIED



@dataclass(frozen=True)
class _HttpNavigation:
    """Conserva respuesta final y contexto de navegación necesario para sondas posteriores del
    binario.

    Attributes:
        response: Respuesta final de metadatos.
        current_url, previous_url: Destino actual y salto anterior opcional utilizado como
            Referer.
    """

    response: httpx.Response
    current_url: str
    previous_url: str | None


@dataclass
class _ArtifactEvidence:
    """Acumula metadatos y muestra del artefacto entre comprobaciones para evitar sondas
    repetidas.

    Attributes:
        filename, extension, content_type, disposition, size_bytes: Evidencia de nombre,
            formato, MIME, disposición y bytes declarados.
        looks_binary: Las cabeceras ya apuntan a un archivo binario.
        signature_response: Muestra reutilizable para inferir o comprobar el prefijo del
            formato.
    """

    filename: str | None
    extension: str | None
    content_type: str
    disposition: str
    size_bytes: int | None
    looks_binary: bool
    signature_response: httpx.Response | None = None


class DownloadValidator:
    """Coordina validación de URL y DNS, navegación explícita y comprobaciones de instalador con
    formatos sustituibles.
    Cierra solo el cliente HTTP que crea; los errores de transporte sin recuperación se
    propagan para decidir reintentos.

    See Also:
        app.scraper.ports.CandidateValidator: Contrato consumido por los resolutores.
        app.scraper.artifacts.ArtifactFormatRegistry: Política de reconocimiento de formatos.
    """

    def __init__(
        self,
        settings: Settings,
        client: httpx.AsyncClient | None = None,
        formats: ArtifactFormatRegistry = DEFAULT_ARTIFACT_FORMAT_REGISTRY,
    ) -> None:
        """Conecta límites, cliente opcional y registro de formatos sin abrir conexiones todavía.

        Args:
            settings: Configuración de timeout, redirecciones, tamaño y esquemas admitidos.
            client: Cliente HTTPX externo; None permite que el validador cree y cierre uno
                propio donde se admite.
            formats: Registro de formatos, MIME y prefijos binarios admitidos.
        """
        self.settings = settings

        self.client = client

        self.formats = formats


    async def validate(
        self,
        candidate: InstallerCandidate,
        *,
        require_signature: bool = False,
    ) -> ValidationResult:
        """Valida la URL inicial, abre o reutiliza cliente y comprueba metadatos y binario.
        Conserva el respaldo HTTP histórico solo para un candidato Winstall con error de
        certificado; la confianza de transporte queda marcada por separado.

        Args:
            candidate: URL candidata con origen, etiqueta, formato sugerido y página de
                referencia.
            require_signature: True exige comprobar el prefijo binario del formato; esta firma
                de archivo no es una firma criptográfica del editor.

        Returns:
            resultado aceptado o rechazado con evidencia técnica.

        Raises:
            httpx.RequestError: Si falla el transporte y no existe una recuperación aplicable.
        """
        rejection = await self._validate_candidate_url(candidate)
        if rejection is not None:
            return rejection

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
        """Obtiene la respuesta final, clasifica errores HTTP y permite evidencia atestiguada
        Winstall ante desafíos del servidor.

        Args:
            client: Cliente HTTPX externo; None permite que el validador cree y cierre uno
                propio donde se admite.
            candidate: URL candidata con origen, etiqueta, formato sugerido y página de
                referencia.
            require_signature: True exige comprobar el prefijo binario del formato; esta firma
                de archivo no es una firma criptográfica del editor.

        Returns:
            resultado de rechazo, atestiguación o comprobación del artefacto.
        """
        navigation = await self._request_final_response(client, candidate)
        if isinstance(navigation, ValidationResult):
            return navigation

        response = navigation.response
        current_url = navigation.current_url
        if response.status_code >= 400:
            attested = self._winstall_edge_attested_result(candidate, current_url, response)
            if attested:
                return attested
            return self._fail(current_url, f"http_{response.status_code}")

        return await self._validate_artifact_response(
            client,
            candidate,
            navigation,
            require_signature=require_signature,
        )

    async def _request_final_response(
        self,
        client: httpx.AsyncClient,
        candidate: InstallerCandidate,
    ) -> _HttpNavigation | ValidationResult:
        """Consulta metadatos salto a salto con Referer controlado y valida cada redirección
        antes de solicitarla.

        Args:
            client: Cliente HTTPX externo; None permite que el validador cree y cierre uno
                propio donde se admite.
            candidate: URL candidata con origen, etiqueta, formato sugerido y página de
                referencia.

        Returns:
            respuesta final con contexto de navegación o rechazo por destino o saltos.
        """
        current_url = candidate.url
        previous_url: str | None = None
        for _ in range(self.settings.max_redirects + 1):
            request_referer = previous_url or same_site_referer(current_url, candidate.referer)
            response = await request_metadata(
                client,
                current_url,
                referer=request_referer,
                probe_html=candidate_has_download_intent(candidate),
            )
            if not response.is_redirect:
                return _HttpNavigation(response, current_url, previous_url)

            location = response.headers.get("location")
            if not location:
                return self._fail(current_url, "redirect_without_location")
            redirected = await self._validated_redirect_url(candidate, current_url, location)
            if isinstance(redirected, ValidationResult):
                return redirected
            previous_url, current_url = current_url, redirected
        return self._fail(current_url, "too_many_redirects")

    async def _validated_redirect_url(
        self,
        candidate: InstallerCandidate,
        current_url: str,
        location: str,
    ) -> str | ValidationResult:
        """Resuelve Location y rechaza esquemas, credenciales, archivos fuente GitHub o DNS no
        admitidos antes de continuar.

        Args:
            candidate: URL candidata con origen, etiqueta, formato sugerido y página de
                referencia.
            current_url: URL del salto que se acaba de consultar.
            location: Destino de redirección absoluto o relativo recibido en Location.

        Returns:
            URL redirigida admitida o resultado de rechazo con motivo específico.
        """
        try:
            redirected_url = urljoin(current_url, location)
            parsed = urlparse(redirected_url)
            hostname = parsed.hostname
        except ValueError:
            return self._fail(current_url, "redirect_invalid_url")
        if not self._scheme_allowed(candidate, parsed.scheme):
            return self._fail(redirected_url, "redirect_unsupported_scheme")
        if not hostname:
            return self._fail(redirected_url, "redirect_missing_domain")
        if parsed.username is not None or parsed.password is not None:
            return self._fail(redirected_url, "redirect_url_credentials_forbidden")
        if is_github_source_archive(redirected_url):
            return self._fail(redirected_url, "redirect_github_source_archive")
        if self._is_unverified_github_zip(candidate, redirected_url, hostname):
            return self._fail(redirected_url, "redirect_github_zip_not_release_asset")
        if not await domain_has_public_dns(hostname):
            return self._fail(redirected_url, "redirect_dns_not_public")
        return redirected_url

    @staticmethod
    def _is_unverified_github_zip(
        candidate: InstallerCandidate,
        url: str,
        hostname: str,
    ) -> bool:
        """Detecta ZIP alojados bajo un host terminado en github.com que no son assets de release
        ni candidatos acreditados por Winstall.

        Args:
            candidate: URL candidata con origen, etiqueta, formato sugerido y página de
                referencia.
            url: URL del candidato o recurso que se consulta.
            hostname: Host DNS o literal de IP; None no es admitido.

        Returns:
            True si debe rechazarse esta referencia GitHub.
        """
        return (
            hostname.lower().endswith("github.com")
            and detect_extension(url) == ".zip"
            and not is_github_release_asset(url)
            and not is_verified_winstall_candidate(candidate)
        )

    async def _validate_artifact_response(
        self,
        client: httpx.AsyncClient,
        candidate: InstallerCandidate,
        navigation: _HttpNavigation,
        *,
        require_signature: bool,
    ) -> ValidationResult:
        """Extrae nombre, tamaño, formato y MIME, rechaza exceso de tamaño o HTML y completa
        evidencia binaria antes de aceptar.

        Args:
            client: Cliente HTTPX externo; None permite que el validador cree y cierre uno
                propio donde se admite.
            candidate: URL candidata con origen, etiqueta, formato sugerido y página de
                referencia.
            navigation: Respuesta final y URL actual/anterior de la navegación ya validada.
            require_signature: True exige comprobar el prefijo binario del formato; esta firma
                de archivo no es una firma criptográfica del editor.

        Returns:
            resultado técnico con confianza VALIDATED, evidencia ATTESTED o motivo de rechazo.
        """
        response = navigation.response
        current_url = navigation.current_url

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
        evidence = _ArtifactEvidence(
            filename=filename,
            extension=extension,
            content_type=content_type,
            disposition=disposition,
            size_bytes=size_bytes,
            looks_binary=(
                content_type in self.formats.binary_media_types | GENERIC_BINARY_MEDIA_TYPES
                or "attachment" in disposition
            ),
        )
        if content_type.startswith("text/html"):
            attested = self._winstall_edge_attested_result(candidate, current_url, response)
            if attested:
                return attested
            return self._fail(current_url, "html_response")

        error = await self._verify_artifact_evidence(
            client, candidate, navigation, evidence, require_signature
        )
        if error is not None:
            return error

        return ValidationResult(
            ok=True,
            url=candidate.url,
            final_url=str(response.url),
            final_domain=download_host(str(response.url)),
            filename=evidence.filename,
            extension=evidence.extension,
            content_type=content_type or None,
            size_bytes=size_bytes,
            transport_security=transport_security_for(str(response.url), candidate),
            confidence=ValidationConfidence.VALIDATED,
        )

    async def _infer_missing_extension(
        self,
        client: httpx.AsyncClient,
        candidate: InstallerCandidate,
        navigation: _HttpNavigation,
        evidence: _ArtifactEvidence,
    ) -> ValidationResult | None:
        """Obtiene una muestra para inferir formato y exige además intención de descarga en el
        candidato; completa nombre y evidencia binaria.

        Args:
            client: Cliente HTTPX externo; None permite que el validador cree y cierre uno
                propio donde se admite.
            candidate: URL candidata con origen, etiqueta, formato sugerido y página de
                referencia.
            navigation: Respuesta final y URL actual/anterior de la navegación ya validada.
            evidence: Metadatos del artefacto que se completan durante las comprobaciones.

        Returns:
            None si pudo inferir el instalador o rechazo HTTP/de formato.
        """
        evidence.signature_response = await self._response_with_content(
            client, candidate, navigation, navigation.response
        )
        if evidence.signature_response.status_code >= 400:
            return self._fail(
                navigation.current_url,
                f"http_{evidence.signature_response.status_code}",
            )
        evidence.extension = self.formats.infer_extension(evidence.signature_response.content)
        if not evidence.extension or not candidate_has_download_intent(candidate):
            return self._fail(navigation.current_url, "missing_installer_extension")
        evidence.filename = evidence.filename or filename_for_inferred_extension(
            navigation.current_url, evidence.extension
        )
        evidence.looks_binary = True
        return None

    async def _verify_required_signature(
        self,
        client: httpx.AsyncClient,
        candidate: InstallerCandidate,
        navigation: _HttpNavigation,
        evidence: _ArtifactEvidence,
    ) -> ValidationResult | None:
        """Comprueba el prefijo binario del formato cuando existe; para formatos sin prefijo
        exige MIME compatible o disposición attachment.

        Args:
            client: Cliente HTTPX externo; None permite que el validador cree y cierre uno
                propio donde se admite.
            candidate: URL candidata con origen, etiqueta, formato sugerido y página de
                referencia.
            navigation: Respuesta final y URL actual/anterior de la navegación ya validada.
            evidence: Metadatos del artefacto que se completan durante las comprobaciones.

        Returns:
            None si la evidencia cumple la política o un rechazo específico de formato, HTTP o
                prefijo.
        """
        extension = evidence.extension
        if extension is None:
            return self._fail(navigation.current_url, "missing_installer_extension")
        artifact_format = self.formats.get(extension)
        if artifact_format is None:
            return self._fail(navigation.current_url, "unsupported_installer_format")
        if not artifact_format.signatures:
            accepted_type = (
                evidence.content_type in set(artifact_format.media_types)
                or evidence.content_type in GENERIC_BINARY_MEDIA_TYPES
                or "attachment" in evidence.disposition
            )
            return (
                None
                if accepted_type
                else self._fail(navigation.current_url, "installer_content_type_mismatch")
            )

        evidence.signature_response = await self._response_with_content(
            client,
            candidate,
            navigation,
            evidence.signature_response or navigation.response,
        )
        if evidence.signature_response.status_code >= 400:
            return self._fail(
                navigation.current_url,
                f"http_{evidence.signature_response.status_code}",
            )
        if not self.formats.matches_signature(extension, evidence.signature_response.content):
            return self._fail(navigation.current_url, "installer_signature_mismatch")
        return None

    async def _verify_binary_evidence(
        self,
        client: httpx.AsyncClient,
        candidate: InstallerCandidate,
        navigation: _HttpNavigation,
        evidence: _ArtifactEvidence,
    ) -> ValidationResult | None:
        """Sondea el cuerpo cuando las cabeceras no prueban que sea binario.
        Solo para candidatos Winstall permite corregir extensión y nombre si los bytes
        identifican otro formato admitido.

        Args:
            client: Cliente HTTPX externo; None permite que el validador cree y cierre uno
                propio donde se admite.
            candidate: URL candidata con origen, etiqueta, formato sugerido y página de
                referencia.
            navigation: Respuesta final y URL actual/anterior de la navegación ya validada.
            evidence: Metadatos del artefacto que se completan durante las comprobaciones.

        Returns:
            None si la muestra acredita un instalador o rechazo not_an_installer.
        """
        extension = evidence.extension
        if extension is None:
            return self._fail(navigation.current_url, "missing_installer_extension")
        evidence.signature_response = await self._response_with_content(
            client,
            candidate,
            navigation,
            evidence.signature_response or navigation.response,
        )
        if evidence.signature_response.status_code >= 400:
            return self._fail(navigation.current_url, "not_an_installer")
        if self.formats.matches_signature(extension, evidence.signature_response.content):
            return None

        actual_extension = self.formats.infer_extension(evidence.signature_response.content)
        if not actual_extension or not is_verified_winstall_candidate(candidate):
            return self._fail(navigation.current_url, "not_an_installer")
        evidence.extension = actual_extension
        evidence.filename = filename_with_actual_extension(
            evidence.filename, navigation.current_url, actual_extension
        )
        return None

    async def _response_with_content(
        self,
        client: httpx.AsyncClient,
        candidate: InstallerCandidate,
        navigation: _HttpNavigation,
        response: httpx.Response,
    ) -> httpx.Response:
        """Reutiliza una muestra ya leída y, si falta, solicita unos bytes del destino final con
        Referer de navegación.

        Args:
            client: Cliente HTTPX externo; None permite que el validador cree y cierre uno
                propio donde se admite.
            candidate: URL candidata con origen, etiqueta, formato sugerido y página de
                referencia.
            navigation: Respuesta final y URL actual/anterior de la navegación ya validada.
            response: Respuesta HTTP con cabeceras y muestra opcional del cuerpo.

        Returns:
            respuesta con contenido de muestra.
        """
        if response.content:
            return response
        return await request_partial(
            client,
            navigation.current_url,
            referer=navigation.previous_url
            or same_site_referer(navigation.current_url, candidate.referer),
        )

    def _fail(self, url: str, reason: str) -> ValidationResult:
        """Construye un rechazo con URL y código de motivo, sin atribuir metadatos no observados.

        Args:
            url: URL del candidato o recurso que se consulta.
            reason: Código estable del motivo de rechazo.

        Returns:
            resultado ok=False con confianza UNVERIFIED.
        """
        return ValidationResult(ok=False, url=url, reason=reason)

    def _scheme_allowed(self, candidate: InstallerCandidate, scheme: str) -> bool:
        """Acepta los esquemas configurados y conserva la excepción histórica HTTP para
        candidatos procedentes de Winstall.

        Args:
            candidate: URL candidata con origen, etiqueta, formato sugerido y página de
                referencia.
            scheme: Esquema de URL que debe cumplir la política del candidato.

        Returns:
            True si el esquema es admisible para esta comprobación.
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
        """Reconoce un desafío perimetral en una URL HTTPS acreditada por Winstall y de formato
        conocido, sin afirmar que se haya leído el binario.

        Args:
            candidate: URL candidata con origen, etiqueta, formato sugerido y página de
                referencia.
            current_url: URL del salto que se acaba de consultar.
            response: Respuesta HTTP con cabeceras y muestra opcional del cuerpo.

        Returns:
            resultado ATTESTED con marca https_winstall_edge_attested o None.
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

    async def _validate_candidate_url(
        self, candidate: InstallerCandidate
    ) -> ValidationResult | None:
        """Rechaza URL inválidas, esquemas y credenciales no admitidos, fichas sin binario,
        fuentes GitHub y DNS restringido.

        Args:
            candidate: URL candidata con origen, etiqueta, formato sugerido y página de
                referencia.

        Returns:
            rechazo inicial o None para continuar con HTTP.
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
        return None

    async def _verify_artifact_evidence(
        self,
        client: httpx.AsyncClient,
        candidate: InstallerCandidate,
        navigation: _HttpNavigation,
        evidence: _ArtifactEvidence,
        require_signature: bool,
    ) -> ValidationResult | None:
        """Completa extensión ausente, verifica el prefijo obligatorio si se pide y sondea el
        cuerpo cuando las cabeceras no bastan.

        Args:
            client: Cliente HTTPX externo; None permite que el validador cree y cierre uno
                propio donde se admite.
            candidate: URL candidata con origen, etiqueta, formato sugerido y página de
                referencia.
            navigation: Respuesta final y URL actual/anterior de la navegación ya validada.
            evidence: Metadatos del artefacto que se completan durante las comprobaciones.
            require_signature: True exige comprobar el prefijo binario del formato; esta firma
                de archivo no es una firma criptográfica del editor.

        Returns:
            primer rechazo encontrado o None si todas las comprobaciones requeridas pasan.
        """
        if not evidence.extension:
            extension_error = await self._infer_missing_extension(
                client, candidate, navigation, evidence
            )
            if extension_error:
                return extension_error
        if require_signature:
            signature_error = await self._verify_required_signature(
                client, candidate, navigation, evidence
            )
            if signature_error:
                return signature_error
        if not evidence.looks_binary:
            binary_error = await self._verify_binary_evidence(
                client, candidate, navigation, evidence
            )
            if binary_error:
                return binary_error
        return None


def is_verified_winstall_candidate(candidate: InstallerCandidate) -> bool:
    """Reconoce candidatos de API o página Winstall y los marcados como winstall_download.

    Args:
        candidate: URL candidata con origen, etiqueta, formato sugerido y página de
            referencia.

    Returns:
        True si el origen permite aplicar la política histórica específica de Winstall.
    """
    return candidate.source in {"winstall_api", "winstall_page"} or (
        candidate.asset_kind == "winstall_download"
    )


def winstall_http_tls_fallback(
    candidate: InstallerCandidate,
    error: httpx.ConnectError,
) -> InstallerCandidate | None:
    """Deriva una copia HTTP únicamente para candidatos Winstall HTTPS cuyo error indica
    certificate verify failed.

    Args:
        candidate: URL candidata con origen, etiqueta, formato sugerido y página de
            referencia.
        error: Fallo de conexión observado al acceder al candidato HTTPS.

    Returns:
        candidato de respaldo sin modificar el original o None si no corresponde.
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
    """Marca explícitamente el transporte HTTP excepcional de candidatos acreditados por
    Winstall.

    Args:
        url: URL del candidato o recurso que se consulta.
        candidate: URL candidata con origen, etiqueta, formato sugerido y página de
            referencia.

    Returns:
        http_winstall_verified en ese caso o None.
    """
    if urlparse(url).scheme == "http" and is_verified_winstall_candidate(candidate):
        return "http_winstall_verified"
    return None


def metadata_headers(
    referer: str | None = None,
    *,
    partial: bool = False,
    user_agent: str = BROWSER_COMPATIBLE_USER_AGENT,
) -> dict[str, str]:
    """Construye cabeceras de negociación y añade Referer y Range de bytes 0-1023 cuando se
    solicita una sonda parcial.

    Args:
        referer: Página anterior o del mismo sitio, o None para omitir Referer.
        partial: True añade Range y solicita bytes sin compresión de transporte.
        user_agent: Identidad enviada en la cabecera User-Agent.

    Returns:
        cabeceras para HEAD o GET de comprobación.
    """
    headers = {
        "Accept": "application/octet-stream,application/x-msdownload,application/x-msi,*/*",
        "Accept-Language": "en-US,en;q=0.9,es;q=0.8",
        "User-Agent": user_agent,
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
    """Usa la vía limitada de SourceForge o intenta HEAD y recurre a GET parcial ante 403/405,
    MIME ausente o evidencia no binaria que debe sondearse.

    Args:
        client: Cliente HTTPX externo; None permite que el validador cree y cierre uno propio
            donde se admite.
        url: URL del candidato o recurso que se consulta.
        referer: Página anterior o del mismo sitio, o None para omitir Referer.
        probe_html: True permite sondear por GET parcial contenido que no parezca binario
            cuando el candidato tiene intención de descarga.

    Returns:
        respuesta de metadatos o muestra; el llamador interpreta estado y redirección.
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
    """Serializa sondas de SourceForge por bucle de eventos y mantiene al menos un segundo de
    separación desde el final de la petición anterior.

    Args:
        client: Cliente HTTPX externo; None permite que el validador cree y cierre uno propio
            donde se admite.
        url: URL del candidato o recurso que se consulta.
        referer: Página anterior o del mismo sitio, o None para omitir Referer.

    Returns:
        respuesta parcial obtenida con el User-Agent específico del scraper.
    """
    loop = asyncio.get_running_loop()
    lock = _SOURCEFORGE_LOCKS.setdefault(loop, asyncio.Lock())
    async with lock:
        delay = _SOURCEFORGE_NEXT_REQUEST.get(loop, 0.0) - loop.time()
        if delay > 0:
            await asyncio.sleep(delay)
        try:
            return await request_partial(
                client,
                url,
                referer=referer,
                user_agent=SOURCEFORGE_USER_AGENT,
            )
        finally:
            _SOURCEFORGE_NEXT_REQUEST[loop] = loop.time() + SOURCEFORGE_MIN_INTERVAL_SECONDS


def is_sourceforge_download_url(url: str) -> bool:
    """Reconoce sourceforge.net y sus subdominios para aplicar la política de consultas
    serializadas.

    Args:
        url: URL del candidato o recurso que se consulta.

    Returns:
        True si el host pertenece a ese conjunto, sin exigir una ruta específica.
    """
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
    user_agent: str = BROWSER_COMPATIBLE_USER_AGENT,
) -> httpx.Response:
    """Envía un GET con Range y lee como máximo la muestra configurada aunque el servidor
    responda con el archivo completo.
    Cierra el streaming y conserva estado, cabeceras y petición original en una respuesta
    local.

    Args:
        client: Cliente HTTPX externo; None permite que el validador cree y cierre uno propio
            donde se admite.
        url: URL del candidato o recurso que se consulta.
        referer: Página anterior o del mismo sitio, o None para omitir Referer.
        max_bytes: Máximo de bytes crudos leídos de la respuesta parcial; predeterminado 4096.
        user_agent: Identidad enviada en la cabecera User-Agent.

    Returns:
        respuesta con bytes de muestra, que puede ser una redirección o un error HTTP.
    """
    async with client.stream(
        "GET",
        url,
        headers=metadata_headers(
            referer,
            partial=True,
            user_agent=user_agent,
        ),
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
    """Prefiere el tamaño total de Content-Range al Content-Length para no confundir bytes de la
    sonda con tamaño del instalador.

    Args:
        response: Respuesta HTTP con cabeceras y muestra opcional del cuerpo.

    Returns:
        tamaño declarado total o None si las cabeceras no permiten determinarlo.
    """
    content_range = response.headers.get("content-range", "")
    match = re.search(r"/(\d+)\s*$", content_range)
    if match:
        return int(match.group(1))
    content_length = response.headers.get("content-length")
    return int(content_length) if content_length and content_length.isdigit() else None


def matches_installer_signature(extension: str, content: bytes) -> bool:
    """Comprueba el prefijo binario utilizando el registro predeterminado de formatos.

    Args:
        extension: Extensión completa cuyo formato se comprueba o asigna.
        content: Bytes iniciales de un archivo, usados como evidencia de formato.

    Returns:
        True si los bytes coinciden con una firma de formato admitida.
    """
    return DEFAULT_ARTIFACT_FORMAT_REGISTRY.matches_signature(extension, content)


def infer_installer_extension(content: bytes) -> str | None:
    """Busca el primer formato predeterminado que permite inferencia y coincide con los bytes
    iniciales.

    Args:
        content: Bytes iniciales de un archivo, usados como evidencia de formato.

    Returns:
        extensión inferida o None.
    """
    return DEFAULT_ARTIFACT_FORMAT_REGISTRY.infer_extension(content)


def declared_candidate_extension(candidate: InstallerCandidate) -> str | None:
    """Busca sufijos de instalador delimitados en etiqueta y contexto y prioriza extensiones
    compuestas más largas.

    Args:
        candidate: URL candidata con origen, etiqueta, formato sugerido y página de
            referencia.

    Returns:
        extensión mencionada por el candidato o None.
    """
    text = f"{candidate.label or ''} {candidate.context or ''}".lower()
    for extension in sorted(PREFERRED_EXTENSIONS, key=len, reverse=True):
        if re.search(rf"(?<![a-z0-9]){re.escape(extension)}(?![a-z0-9])", text):
            return extension
    return None


def filename_for_inferred_extension(url: str, extension: str) -> str:
    """Deriva un nombre del último segmento de URL, sanea caracteres y limita longitud para
    añadir la extensión reconocida.

    Args:
        url: URL del candidato o recurso que se consulta.
        extension: Extensión completa cuyo formato se comprueba o asigna.

    Returns:
        nombre de hasta 255 caracteres; usa download como base si no puede obtener otra.
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
    """Retira un sufijo de instalador conocido del nombre y añade el formato observado, o deriva
    el nombre de URL si falta.

    Args:
        filename: Nombre propuesto del archivo, o None si aún debe derivarse de la URL.
        url: URL del candidato o recurso que se consulta.
        extension: Extensión completa cuyo formato se comprueba o asigna.

    Returns:
        nombre con la extensión real y longitud acotada.
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
    """Obtiene el dominio registrado del destino y usa el hostname en minúsculas si no tiene
    sufijo público reconocido.

    Args:
        url: URL del candidato o recurso que se consulta.

    Returns:
        dominio publicable o None si no puede leerse el host.
    """
    try:
        hostname = urlparse(url).hostname
    except ValueError:
        return None
    if not hostname:
        return None
    return registered_domain(url) or hostname.lower()


def same_site_referer(download_url: str, referer: str | None) -> str | None:
    """Conserva Referer solo cuando su dominio registrado coincide con el de la descarga.

    Args:
        download_url: Destino de descarga cuyo dominio debe coincidir con el de Referer.
        referer: Página anterior o del mismo sitio, o None para omitir Referer.

    Returns:
        página recibida o None para evitar enviar una referencia entre sitios distintos.
    """
    if not referer:
        return None
    download_domain = registered_domain(download_url)
    referer_domain = registered_domain(referer)
    if download_domain and download_domain == referer_domain:
        return referer
    return None


def is_edge_challenge(response: httpx.Response) -> bool:
    """Busca cabeceras y marcadores de desafíos antirobot en los primeros 4096 bytes de
    respuesta.

    Args:
        response: Respuesta HTTP con cabeceras y muestra opcional del cuerpo.

    Returns:
        True si detecta evidencia de desafío perimetral; no acredita el contenido del
            instalador.
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
    """Prioriza filename* UTF-8 y después filename, decodifica el nombre y solo acepta valores
    con un punto.

    Args:
        value: Valor recibido de Content-Disposition o nombre/ruta de archivo, según la
            operación.

    Returns:
        nombre limitado a 255 caracteres o None.
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
    """Busca sufijos descartados en la ruta decodificada y conserva tar.gz como excepción
    admitida.

    Args:
        value: Valor recibido de Content-Disposition o nombre/ruta de archivo, según la
            operación.

    Returns:
        extensión no soportada encontrada o None.
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
    """Reconoce el endpoint de ficha Winstall y páginas de tienda Microsoft sin extensión de
    instalador.

    Args:
        url: URL del candidato o recurso que se consulta.

    Returns:
        True para esas referencias sin binario o URL que no puede interpretarse.
    """
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
    """Valida literales IP directamente y deduplica consultas DNS simultáneas por bucle y host.
    Protege la consulta compartida frente a cancelación individual y cachea éxito durante 600
    segundos y rechazo durante 20.

    Args:
        hostname: Host DNS o literal de IP; None no es admitido.

    Returns:
        True si las direcciones resueltas cumplen la política de IP; False si falta host o la
            resolución no es admisible.
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
    """Consulta A y AAAA con hasta tres intentos; exige al menos una dirección y ningún fallo
    transitorio pendiente antes de aceptar.
    Rechaza NXDOMAIN y cualquier dirección restringida y aplica pausas breves entre intentos
    recuperables.

    Args:
        hostname: Host DNS o literal de IP; None no es admitido.

    Returns:
        True solo cuando todas las direcciones obtenidas superan is_public_ip.
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
    """Rechaza direcciones clasificadas como privadas, loopback, link-local, multicast,
    reservadas o no especificadas por ipaddress.

    Args:
        ip: Dirección IPv4 o IPv6 ya interpretada.

    Returns:
        True cuando no pertenece a ninguna de esas categorías.
    """
    return not (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_multicast
        or ip.is_reserved
        or ip.is_unspecified
    )
