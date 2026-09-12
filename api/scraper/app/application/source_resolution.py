"""Resuelve una referencia exacta para el worker, revalida evidencia caducada y confirma cambios
de confianza antes de responder.

See Also:
    app.api.internal_routes: Autentica y traduce los fallos de este caso de uso a HTTP.
    app.domain.source_resolution: Define el significado de la confianza publicada.
"""

from __future__ import annotations

from datetime import datetime
from urllib.parse import urlparse

import httpx
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.time import utc_after, utc_now
from app.core.url_protector import UrlProtector
from app.db.enums import ResolutionStatus, ValidationStatus
from app.db.models import ResolvedSource, SoftwareApp, SoftwareAppDependency
from app.domain.source_resolution import SourceTrustStatus, source_trust_status
from app.repositories.catalog import CatalogRepository
from app.schemas.internal import (
    InternalSourceResolution,
)
from app.schemas.linux_install import default_profile
from app.scraper.candidates import InstallerCandidate, infer_operating_system
from app.scraper.installer_policy import (
    infer_validated_operating_system,
    known_official_candidates_for_package,
)
from app.scraper.linux_install import bundled_signature
from app.scraper.validator import DownloadValidator, ValidationConfidence, ValidationResult


class SourceNotFoundError(LookupError):
    """Indica que la referencia solicitada no identifica una resolución existente, sin
    sustituirla por otra fuente de la aplicación.

    See Also:
        resolve_source: Busca y bloquea la referencia solicitada.
    """


class SourceRevalidationTransientError(RuntimeError):
    """Aplaza la descarga porque la revalidación no pudo concluir con garantías suficientes ante
    un fallo reintentable.
    Conserva la causa de red cuando está disponible y no convierte ese fallo temporal en
    ausencia permanente.

    See Also:
        resolve_source: Propaga el aplazamiento al adaptador HTTP.
        _is_transient_revalidation_failure: Clasifica respuestas y evidencias recuperables.
    """


async def resolve_source(
    source_ref: str,
    session: AsyncSession,
    settings: Settings,
) -> InternalSourceResolution:
    """Bloquea la referencia exacta, comprueba confianza y recupera una URL HTTPS solo cuando
    está verificada.
    Puede revalidar una resolución caducada; para Linux incorpora receta, dependencias y
    firma. Confirma la sesión antes de devolver el resultado.

    Args:
        source_ref: UUID textual de la resolución exacta elegida para la descarga.
        session: Sesión del caso de uso; las confirmaciones liberan los bloqueos y persisten
            la revalidación.
        settings: Configuración de red, límites de comprobación y secreto de las URL.

    Returns:
        contrato interno de la misma referencia con URL disponible o None y confianza
            explícita.

    Raises:
        SourceNotFoundError: Si la referencia no es válida o no existe.
        SourceRevalidationTransientError: Si una comprobación recuperable impide decidir su
            validez.
    """
    catalog = CatalogRepository(session, UrlProtector(settings.url_protection_secret))
    # Bloquea tanto el candidato como su fuente superior. Un sourceRef puede permanecer
    # en cola y la fuente puede haber pasado mientras tanto a broken, review o invalid.
    resolved = await catalog.get_resolved_source_by_ref_for_update(source_ref)
    if resolved is None:
        raise SourceNotFoundError("source_not_found")

    metadata = resolved.metadata_json or {}
    trust_status = (
        source_trust_status(
            validation_status=resolved.validation_status,
            resolution_status=resolved.status,
            expires_at=resolved.expires_at,
            metadata=metadata,
            now=utc_now(),
        )
        if _parent_source_is_available(resolved)
        else SourceTrustStatus.UNRESOLVED
    )
    url = catalog.reveal_url(resolved) if trust_status == SourceTrustStatus.VERIFIED else None
    if trust_status == SourceTrustStatus.UNRESOLVED and _can_revalidate_expired(resolved, metadata):
        url = await _revalidate_expired_source(resolved, catalog, settings, session)
        if url is not None:
            metadata = resolved.metadata_json or {}
            trust_status = SourceTrustStatus.VERIFIED
    if trust_status == SourceTrustStatus.VERIFIED and (
        url is None or urlparse(url).scheme != "https"
    ):
        url = await _invalidate_unusable_source(resolved, catalog, session)
        if url is None:
            metadata = resolved.metadata_json or {}
            trust_status = SourceTrustStatus.UNRESOLVED
    sha256 = metadata.get("sha256") or metadata.get("expected_sha256")
    expected_sha256 = (
        sha256.lower()
        if isinstance(sha256, str)
        and len(sha256) == 64
        and all(character in "0123456789abcdefABCDEF" for character in sha256)
        else None
    )
    profile = None
    app_name = None
    signature = None
    if resolved.source.operating_system == "linux":
        row = resolved.install_profile
        profile = (
            dict(row.profile_json)
            if row and row.status == "approved"
            else default_profile(resolved.extension)
        )
        profile["dependencies"] = [
            str(d)
            for d in (
                await session.scalars(
                    select(SoftwareAppDependency.dependency_app_id).where(
                        SoftwareAppDependency.app_id == resolved.source.software_app_id
                    )
                )
            ).all()
        ]
        app_name = await session.scalar(
            select(SoftwareApp.name).where(SoftwareApp.id == resolved.source.software_app_id)
        )
        if trust_status == SourceTrustStatus.VERIFIED:
            signature = await bundled_signature(profile)
    response = InternalSourceResolution(
        sourceRef=str(resolved.id),
        appId=str(resolved.source.software_app_id),
        url=url,
        expectedFilename=resolved.filename,
        expectedSizeBytes=resolved.size_bytes,
        expectedSha256=expected_sha256,
        expectedMime=resolved.content_type,
        operatingSystem=resolved.source.operating_system,
        architecture=resolved.source.architecture,
        trustStatus=trust_status,
        appName=app_name,
        version=resolved.version,
        extension=resolved.extension,
        installationProfile=profile,
        signatureBase64=signature,
    )
    await session.commit()
    return response


def _can_revalidate_expired(resolved: ResolvedSource, metadata: dict) -> bool:
    """Permite revalidar únicamente resoluciones caducadas con historial válido y fuente
    disponible, excluyendo evidencia solo atestiguada.

    Args:
        resolved: Resolución persistida cuyo instalador y fuente se comprueban.
        metadata: Evidencias persistidas de confianza y transporte de la resolución.

    Returns:
        True si procede intentar una nueva comprobación binaria.
    """
    confidence = str(metadata.get("validation_confidence") or "").lower()
    return (
        _parent_source_is_available(resolved)
        and resolved.validation_status == ValidationStatus.VALID.value
        and resolved.status in {ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value}
        and resolved.expires_at <= utc_now()
        and confidence in {"", "validated", "verified"}
        and metadata.get("transport_security")
        not in {"https_winstall_edge_attested", "http_winstall_verified"}
    )


def _parent_source_is_available(resolved: ResolvedSource) -> bool:
    """Exige fuente padre disponible en la proyección y estados directo o fallback con validación
    válida.

    Args:
        resolved: Resolución persistida cuyo instalador y fuente se comprueban.

    Returns:
        False si falta la fuente o cualquiera de sus garantías.
    """
    source = resolved.source
    return (
        source is not None
        and source.catalog_available is True
        and source.resolution_status
        in {ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value}
        and source.validation_status == ValidationStatus.VALID.value
    )


async def _validate_revalidation_candidate(
    resolved: ResolvedSource,
    candidate: InstallerCandidate,
    settings: Settings,
    session: AsyncSession,
) -> tuple[InstallerCandidate, ValidationResult]:
    """Comprueba el candidato y busca una recuperación oficial conocida si falla la red o no se
    obtiene HTTPS validado.
    Si ninguna vía ofrece resultado por un fallo temporal, confirma la sesión y propaga el
    aplazamiento.

    Args:
        resolved: Resolución persistida cuyo instalador y fuente se comprueban.
        candidate: URL candidata con procedencia, etiqueta y contexto de navegación.
        settings: Configuración de red, límites de comprobación y secreto de las URL.
        session: Sesión del caso de uso; las confirmaciones liberan los bloqueos y persisten
            la revalidación.

    Returns:
        candidato finalmente utilizado y resultado técnico, que todavía puede ser un rechazo
            definitivo.

    Raises:
        SourceRevalidationTransientError: Si no hay resultado utilizable tras el fallo de red
            y el respaldo oficial.
    """
    validation_error: httpx.RequestError | None = None
    try:
        result = await DownloadValidator(settings).validate(candidate)
    except httpx.RequestError as exc:
        validation_error = exc
        result = None

    if result is None or not _is_verified_https_result(result, candidate.url):
        official = await _validate_known_official_recovery(resolved, settings)
        if official is not None:
            candidate, result = official
        elif validation_error is not None:
            await session.commit()
            raise SourceRevalidationTransientError(
                "source_revalidation_transient"
            ) from validation_error

    if result is None:
        await session.commit()
        raise SourceRevalidationTransientError("source_revalidation_transient")

    return candidate, result


async def _revalidate_expired_source(
    resolved: ResolvedSource,
    catalog: CatalogRepository,
    settings: Settings,
    session: AsyncSession,
) -> str | None:
    """Refresca bajo bloqueo la misma resolución y reutiliza una validación concurrente si ya
    existe.
    Si sigue caducada, valida su URL o un respaldo oficial compatible; confirma aceptación,
    caducidad terminal o aplazamiento sin cambiar su identidad.

    Args:
        resolved: Resolución persistida cuyo instalador y fuente se comprueban.
        catalog: Repositorio que comparte esta sesión y protege las URL del despliegue.
        settings: Configuración de red, límites de comprobación y secreto de las URL.
        session: Sesión del caso de uso; las confirmaciones liberan los bloqueos y persisten
            la revalidación.

    Returns:
        URL que quedó validada o None si no puede utilizarse.

    Raises:
        SourceRevalidationTransientError: Si el resultado debe reintentarse sin invalidar
            definitivamente la fuente.
    """
    # Una lectura actual bajo bloqueo de fila evita duplicar la validación de red.
    # ``populate_existing`` en el repositorio también observa una renovación o una
    # invalidación terminal confirmada mientras esta solicitud esperaba.
    locked = await catalog.get_resolved_source_by_ref_for_update(str(resolved.id))
    if locked is None:
        await session.commit()
        return None
    resolved = locked
    metadata = dict(resolved.metadata_json or {})
    if not _parent_source_is_available(resolved):
        await session.commit()
        return None
    trust_status = source_trust_status(
        validation_status=resolved.validation_status,
        resolution_status=resolved.status,
        expires_at=resolved.expires_at,
        metadata=metadata,
        now=utc_now(),
    )
    if trust_status == SourceTrustStatus.VERIFIED:
        url = catalog.reveal_url(resolved)
        await session.commit()
        return url
    if not _can_revalidate_expired(resolved, metadata):
        await session.commit()
        return None

    protected_url = catalog.reveal_url(resolved)
    if protected_url is None:
        await _expire_terminal_candidate(
            resolved,
            metadata,
            "source_url_unreadable",
            session,
        )
        return None
    candidate = InstallerCandidate(
        url=protected_url,
        source=str(metadata.get("candidate_source") or "internal_revalidation"),
        label=str(metadata.get("candidate_label") or "") or None,
        asset_kind=str(metadata.get("asset_kind") or "") or None,
        referer=resolved.source.initial_url,
    )
    candidate, result = await _validate_revalidation_candidate(
        resolved,
        candidate,
        settings,
        session,
    )

    now = utc_now()
    final_url = result.final_url or candidate.url
    if (
        not result.ok
        or result.confidence != ValidationConfidence.VALIDATED
        or urlparse(final_url).scheme != "https"
    ):
        reason = (
            result.reason
            or ("source_not_https" if urlparse(final_url).scheme != "https" else None)
            or "source_not_verified"
        )
        if _is_transient_revalidation_failure(result, reason):
            await session.commit()
            raise SourceRevalidationTransientError("source_revalidation_transient")
        await _expire_terminal_candidate(resolved, metadata, reason, session, now=now)
        return None

    return await _accept_revalidation(resolved, catalog, session, candidate, result, now)


async def _accept_revalidation(
    resolved: ResolvedSource,
    catalog: CatalogRepository,
    session: AsyncSession,
    candidate: InstallerCandidate,
    result: ValidationResult,
    now: datetime,
) -> str:
    """Actualiza en la misma resolución la URL protegida y metadatos comprobados, renueva su
    vigencia 24 horas y confirma la sesión.
    Una recuperación oficial conocida se marca directa y reciente; la fuente padre recupera
    validación válida.

    Args:
        resolved: Resolución persistida cuyo instalador y fuente se comprueban.
        catalog: Repositorio que comparte esta sesión y protege las URL del despliegue.
        session: Sesión del caso de uso; las confirmaciones liberan los bloqueos y persisten
            la revalidación.
        candidate: URL candidata con procedencia, etiqueta y contexto de navegación.
        result: Resultado técnico de validar el candidato.
        now: Instante UTC sin tzinfo; None usa el reloj actual donde se permite.

    Returns:
        URL final aceptada.
    """
    metadata = dict(resolved.metadata_json or {})
    final_url = result.final_url or candidate.url
    resolved.checked_at = now
    resolved.resolved_url_encrypted = catalog.url_protector.protect(final_url)
    resolved.final_domain = result.final_domain or resolved.final_domain
    resolved.filename = result.filename or resolved.filename
    resolved.extension = result.extension or resolved.extension
    resolved.content_type = result.content_type or resolved.content_type
    resolved.size_bytes = (
        result.size_bytes if result.size_bytes is not None else resolved.size_bytes
    )
    resolved.validation_status = ValidationStatus.VALID.value
    resolved.expires_at = utc_after(hours=24)
    if candidate.source == "official_known_endpoint":
        resolved.status = ResolutionStatus.DIRECT.value
        resolved.release_rank = 0
        resolved.is_latest = True
        resolved.version_status = "latest"
    resolved.source.resolution_status = resolved.status
    resolved.source.validation_status = ValidationStatus.VALID.value
    metadata["candidate_source"] = candidate.source
    if candidate.label:
        metadata["candidate_label"] = candidate.label
    if candidate.asset_kind:
        metadata["asset_kind"] = candidate.asset_kind
    metadata["validation_confidence"] = ValidationConfidence.VALIDATED.value
    metadata.pop("last_revalidation_error", None)
    if result.transport_security:
        metadata["transport_security"] = result.transport_security
    else:
        metadata.pop("transport_security", None)
    resolved.metadata_json = metadata
    await session.commit()
    return final_url


def _is_verified_https_result(result: ValidationResult, candidate_url: str) -> bool:
    """Exige éxito, confianza de validación binaria y esquema HTTPS en el destino final o URL
    inicial.

    Args:
        result: Resultado técnico de validar el candidato.
        candidate_url: URL inicial usada si la comprobación no devuelve un destino final.

    Returns:
        True si el resultado permite entregar esa URL al worker.
    """
    final_url = result.final_url or candidate_url
    return (
        result.ok
        and result.confidence == ValidationConfidence.VALIDATED
        and urlparse(final_url).scheme == "https"
    )


async def _validate_known_official_recovery(
    resolved: ResolvedSource,
    settings: Settings,
) -> tuple[InstallerCandidate, ValidationResult] | None:
    """Recorre en orden los candidatos oficiales conocidos de la aplicación y exige plataforma
    compatible antes y después de validar.
    Descarta errores de petición y continúa con el siguiente candidato.

    Args:
        resolved: Resolución persistida cuyo instalador y fuente se comprueban.
        settings: Configuración de red, límites de comprobación y secreto de las URL.

    Returns:
        primer candidato HTTPS verificado con su resultado, o None.
    """
    source = resolved.source
    app = source.software_app if source is not None else None
    if app is None:
        return None
    candidates = known_official_candidates_for_package(
        app.winstall_id,
        app.latest_version,
    )
    for candidate in candidates:
        if infer_operating_system(candidate) != source.operating_system:
            continue
        try:
            result = await DownloadValidator(settings).validate(candidate)
        except httpx.RequestError:
            continue
        if not _is_verified_https_result(result, candidate.url):
            continue
        if infer_validated_operating_system(candidate, result) != source.operating_system:
            continue
        return candidate, result
    return None


async def _invalidate_unusable_source(
    resolved: ResolvedSource,
    catalog: CatalogRepository,
    session: AsyncSession,
) -> str | None:
    """Vuelve a bloquear y leer la URL para respetar una reparación concurrente; si sigue
    ilegible o sin HTTPS, caduca la resolución y confirma.

    Args:
        resolved: Resolución persistida cuyo instalador y fuente se comprueban.
        catalog: Repositorio que comparte esta sesión y protege las URL del despliegue.
        session: Sesión del caso de uso; las confirmaciones liberan los bloqueos y persisten
            la revalidación.

    Returns:
        URL HTTPS reparada mientras se esperaba el bloqueo, o None.
    """
    locked = await catalog.get_resolved_source_by_ref_for_update(str(resolved.id))
    if locked is None:
        await session.commit()
        return None
    url = catalog.reveal_url(locked)
    if url is not None and urlparse(url).scheme == "https":
        await session.commit()
        return url
    await _expire_terminal_candidate(
        locked,
        dict(locked.metadata_json or {}),
        "source_url_unreadable" if url is None else "source_not_https",
        session,
    )
    return None


async def _expire_terminal_candidate(
    resolved: ResolvedSource,
    metadata: dict,
    reason: str,
    session: AsyncSession,
    *,
    now: datetime | None = None,
) -> None:
    """Fija validación expired, comprobación y caducidad al mismo instante, guarda el motivo y
    confirma la transacción.

    Args:
        resolved: Resolución persistida cuyo instalador y fuente se comprueban.
        metadata: Evidencias persistidas de confianza y transporte de la resolución.
        reason: Código del motivo que impide aceptar la resolución.
        session: Sesión del caso de uso; las confirmaciones liberan los bloqueos y persisten
            la revalidación.
        now: Instante UTC sin tzinfo; None usa el reloj actual donde se permite.
    """
    invalidated_at = now or utc_now()
    resolved.checked_at = invalidated_at
    resolved.validation_status = ValidationStatus.EXPIRED.value
    resolved.expires_at = invalidated_at
    metadata["last_revalidation_error"] = reason
    resolved.metadata_json = metadata
    await session.commit()


def _is_transient_revalidation_failure(
    result: ValidationResult,
    reason: str,
) -> bool:
    """Considera recuperables la evidencia solo atestiguada, falta de respuesta o verificación,
    HTTP 408/425/429 y errores HTTP desde 500.

    Args:
        result: Resultado técnico de validar el candidato.
        reason: Código del motivo que impide aceptar la resolución.

    Returns:
        True si procede conservar la resolución para reintentar.
    """
    if result.ok and result.confidence == ValidationConfidence.ATTESTED:
        return True
    if reason in {"no_response", "source_not_verified"}:
        return True
    if not reason.startswith("http_"):
        return False
    try:
        status_code = int(reason.removeprefix("http_"))
    except ValueError:
        return False
    return status_code in {408, 425, 429} or status_code >= 500
