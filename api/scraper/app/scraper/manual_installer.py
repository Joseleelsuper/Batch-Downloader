"""Gestiona inspecciones manuales de instaladores: reserva transaccional, validación HTTPS,
lectura segura de la web oficial y sugerencias de catálogo.

See Also:
    app.scraper.inspection_lifecycle: Coordina fases, leases y estados de la inspección.
    app.scraper.validator: Comprueba el binario y su firma antes de crear sugerencias.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import re
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from urllib.parse import urljoin, urlparse

import httpx
from selectolax.parser import HTMLParser
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.json_safe import json_safe
from app.core.logging import get_logger
from app.core.time import utc_after, utc_now
from app.core.url_protector import UrlProtector
from app.db.enums import (
    AppStatus,
    LongDescriptionStatus,
)
from app.db.models import (
    ManualInstallerInspection,
    SoftwareApp,
)
from app.db.session import AsyncSessionLocal
from app.repositories.pipeline import (
    QUEUE_MANUAL_INSTALLER_ENRICHMENT,
    PipelineRepository,
)
from app.scraper.artifacts import (
    DEFAULT_ARTIFACT_FORMAT_REGISTRY,
    ArtifactArchitecture,
)
from app.scraper.candidates import InstallerCandidate, extract_version, registered_domain
from app.scraper.description_enricher import AppDescriptionLLMClient
from app.scraper.inspection_lifecycle import InspectionProgress, load_inspection
from app.scraper.llm import LLMGenerationError
from app.scraper.safe_http import (
    SafeHttpError,
    fetch_public_resource,
    has_sensitive_query,
    validate_public_https_syntax,
    validate_public_https_url,
)
from app.scraper.validator import (
    DownloadValidator,
    ValidationConfidence,
    ValidationResult,
)

INSPECTION_ACTIVE_STATUSES = ("queued", "running", "ready")

INSPECTION_VISIBLE_STATUSES = ("queued", "running", "ready", "failed")

MANUAL_INSTALLER_PLATFORMS = ("windows", "macos", "linux")

MANUAL_INSTALLER_URL_COLUMNS = {
    "windows": "windows_installer_url_encrypted",
    "macos": "macos_installer_url_encrypted",
    "linux": "linux_installer_url_encrypted",
}

TRANSIENT_VALIDATION_REASONS = {
    "no_response",
    "source_not_verified",
    "timeout",
}

SAFE_PAGE_FIELDS = {
    "name",
    "publisher",
    "version",
    "description",
    "canonical",
    "icon",
}

PhaseCallback = Callable[[str], Awaitable[None]]

logger = get_logger(__name__)



class ManualInstallerError(Exception):
    """Fallo permanente de una inspección manual que puede exponerse como código HTTP estable.

    Attributes:
        code: Código de dominio que explica el rechazo.
        status_code: Estado HTTP asociado al rechazo.
    """

    def __init__(self, code: str, status_code: int) -> None:
        """Conserva el código y estado HTTP que la API debe comunicar al administrador.

        Args:
            code: Código estable que identifica el fallo al consumidor de la API.
            status_code: Código HTTP que debe devolver la ruta ante el fallo.
        """
        super().__init__(code)
        self.code = code

        self.status_code = status_code



class ManualInstallerTransientError(Exception):
    """Fallo recuperable de una inspección manual; el worker debe reencolarla sin marcarla como
    inválida.
    """

    def __init__(self, code: str) -> None:
        """Crea un fallo recuperable con su código de reintento.

        Args:
            code: Código estable que identifica el fallo al consumidor de la API.
        """
        super().__init__(code)
        self.code = code



@dataclass(frozen=True)
class ValidatedManualInstaller:
    """Reúne la evidencia técnica aceptada y los metadatos derivados de un instalador introducido
    manualmente.

    Attributes:
        result: Resultado de DownloadValidator con confianza VALIDATED.
        final_url: Destino HTTPS después de redirecciones.
        version: Versión extraída del nombre o URL.
        operating_system: Plataforma única del formato o la esperada por el administrador.
        architecture: Arquitectura inferida por el registro de formatos.
    """

    result: ValidationResult

    final_url: str

    version: str | None

    operating_system: str | None

    architecture: str



class ManualInstallerInspectionRepository:
    """Reserva, reutiliza, caduca y bloquea inspecciones manuales manteniendo la aplicación y sus
    URLs protegidas en la misma sesión.
    """

    def __init__(
        self,
        session: AsyncSession,
        protector: UrlProtector,
        settings: Settings,
    ) -> None:
        """Inyecta la sesión, protección de URLs y configuración necesarias para persistir una
        inspección.

        Args:
            session: Sesión SQLAlchemy de la operación actual.
            protector: Protector usado para cifrar y revelar URLs persistidas.
            settings: Configuración de límites, secretos y endpoints.
        """
        self.session = session

        self.protector = protector

        self.settings = settings


    async def create_or_reuse(
        self,
        app_id: uuid.UUID,
        installer_url: str | None,
        source_page_url: str,
        installer_urls: dict[str, str | None] | None = None,
    ) -> tuple[ManualInstallerInspection, bool]:
        """Valida URLs públicas, bloquea la aplicación, reutiliza una inspección idéntica o crea
        una nueva y encola su procesamiento con una instantánea de versión.

        Args:
            app_id: UUID de la aplicación cuya inspección se reserva.
            installer_url: URL general del instalador proporcionada por administración.
            source_page_url: Página oficial que aporta el contexto de la inspección.
            installer_urls: URLs opcionales separadas por plataforma.

        Returns:
            inspección y True cuando se creó una reserva nueva; False cuando se reutilizó.

        Raises:
            ManualInstallerError: Si faltan URLs, la aplicación no es inspeccionable o ya
                existe otra entrada activa incompatible.
        """
        installer_url = clean_optional(installer_url)
        if installer_url:
            installer_url = await validate_public_https_url(installer_url)
        safe_installer_urls = await validate_manual_installer_urls(installer_urls or {})
        if not installer_url and not safe_installer_urls:
            raise ManualInstallerError(
                "at_least_one_installer_url_required",
                422,
            )
        source_page_url = await validate_public_https_url(source_page_url)
        if has_sensitive_query(source_page_url):
            raise ManualInstallerError("source_page_query_credentials_forbidden", 422)

        app = await self._lock_inspectable_app(app_id)

        await self._expire_stale(app_id)
        input_hash = inspection_input_hash(
            installer_url,
            source_page_url,
            self.settings.url_protection_secret,
            safe_installer_urls,
        )
        active = await self.session.scalar(
            select(ManualInstallerInspection)
            .where(ManualInstallerInspection.software_app_id == app_id)
            .where(ManualInstallerInspection.status.in_(INSPECTION_ACTIVE_STATUSES))
            .order_by(ManualInstallerInspection.created_at.desc())
            .limit(1)
            .with_for_update()
        )
        if active is not None:
            if active.captured_app_version != app.version:
                active.status = "expired"
                active.phase = "expired"
                active.error_code = "app_changed_reinspect_required"
                active.updated_at = utc_now()
            elif active.input_hash == input_hash:
                return active, False
            else:
                raise ManualInstallerError("inspection_already_active", 409)

        existing = await self.session.scalar(
            select(ManualInstallerInspection)
            .where(ManualInstallerInspection.software_app_id == app_id)
            .where(ManualInstallerInspection.input_hash == input_hash)
            .where(ManualInstallerInspection.captured_app_version == app.version)
            .where(ManualInstallerInspection.status == "failed")
            .order_by(ManualInstallerInspection.created_at.desc())
            .limit(1)
        )
        if existing is not None:
            return existing, False

        inspection = ManualInstallerInspection(
            software_app_id=app.id,
            status="queued",
            phase="queued",
            captured_app_version=app.version,
            input_hash=input_hash,
            installer_url_encrypted=protect_optional_url(
                self.protector,
                installer_url,
            ),
            windows_installer_url_encrypted=protect_optional_url(
                self.protector,
                safe_installer_urls.get("windows"),
            ),
            macos_installer_url_encrypted=protect_optional_url(
                self.protector,
                safe_installer_urls.get("macos"),
            ),
            linux_installer_url_encrypted=protect_optional_url(
                self.protector,
                safe_installer_urls.get("linux"),
            ),
            source_page_url_encrypted=self.protector.protect(source_page_url),
            warnings_json=[],
            expires_at=utc_after(hours=self.settings.manual_inspection_ttl_hours),
        )
        self.session.add(inspection)
        await self.session.flush()
        pipeline = PipelineRepository(self.session)
        await pipeline.enqueue(
            QUEUE_MANUAL_INSTALLER_ENRICHMENT,
            str(inspection.id),
            app.name,
            {"inspection_id": str(inspection.id)},
            None,
            priority=200,
        )
        return inspection, True

    async def current(self, app_id: uuid.UUID) -> ManualInstallerInspection | None:
        """Caduca entradas vencidas o asociadas a una versión antigua y devuelve la inspección
        visible más reciente.

        Args:
            app_id: UUID de la aplicación cuya inspección se reserva.
        """
        await self._expire_stale(app_id)
        inspection = await self.session.scalar(
            select(ManualInstallerInspection)
            .where(ManualInstallerInspection.software_app_id == app_id)
            .where(ManualInstallerInspection.status.in_(INSPECTION_VISIBLE_STATUSES))
            .order_by(ManualInstallerInspection.created_at.desc())
            .limit(1)
        )
        await self._expire_if_app_changed(inspection)
        return inspection

    async def get(
        self,
        app_id: uuid.UUID,
        inspection_id: uuid.UUID,
        *,
        for_update: bool = False,
    ) -> ManualInstallerInspection | None:
        """Busca una inspección por aplicación e ID, opcionalmente bloquea su fila y actualiza
        expiración antes de devolverla.

        Args:
            app_id: UUID de la aplicación cuya inspección se reserva.
            inspection_id: UUID de la inspección solicitada.
            for_update: Indica si la lectura debe bloquear la fila para modificarla.

        Raises:
            ManualInstallerError: No se propaga; la ausencia se expresa como None.
        """
        statement = (
            select(ManualInstallerInspection)
            .where(ManualInstallerInspection.id == inspection_id)
            .where(ManualInstallerInspection.software_app_id == app_id)
            .limit(1)
        )
        if for_update:
            statement = statement.with_for_update()
        inspection = await self.session.scalar(statement)
        if (
            inspection is not None
            and inspection.status not in {"applied", "expired"}
            and inspection.expires_at <= utc_now()
        ):
            inspection.status = "expired"
            inspection.phase = "expired"
            inspection.error_code = "inspection_expired"
            inspection.updated_at = utc_now()
        await self._expire_if_app_changed(inspection)
        return inspection

    async def _expire_stale(self, app_id: uuid.UUID) -> None:
        """Marca como expired las inspecciones visibles cuyo TTL ya terminó.

        Args:
            app_id: UUID de la aplicación cuya inspección se reserva.
        """
        inspections = await self.session.scalars(
            select(ManualInstallerInspection)
            .where(ManualInstallerInspection.software_app_id == app_id)
            .where(ManualInstallerInspection.status.in_(INSPECTION_VISIBLE_STATUSES))
            .where(ManualInstallerInspection.expires_at <= utc_now())
        )
        for inspection in inspections:
            inspection.status = "expired"
            inspection.phase = "expired"
            inspection.error_code = "inspection_expired"
            inspection.updated_at = utc_now()

    async def _expire_if_app_changed(
        self,
        inspection: ManualInstallerInspection | None,
    ) -> None:
        """Invalida una inspección cuando la aplicación cambia de versión, estado activo o estado
        de catálogo.

        Args:
            inspection: Inspección persistida que se consulta o actualiza.
        """
        if inspection is None or inspection.status in {"applied", "expired"}:
            return
        app = await self.session.get(SoftwareApp, inspection.software_app_id)
        if (
            app is not None
            and app.version == inspection.captured_app_version
            and app.app_status == AppStatus.ACTIVE.value
            and app.catalog_status in {"review", "missing"}
        ):
            return
        inspection.status = "expired"
        inspection.phase = "expired"
        inspection.error_code = "app_changed_reinspect_required"
        inspection.updated_at = utc_now()

    async def _lock_inspectable_app(self, app_id: uuid.UUID) -> SoftwareApp:
        """Bloquea la aplicación y comprueba que permanece activa y pendiente de resolución
        manual.

        Args:
            app_id: UUID de la aplicación cuya inspección se reserva.

        Returns:
            aplicación bloqueada para la reserva.

        Raises:
            ManualInstallerError: Cuando no existe, no está activa o ya no necesita
                inspección.
        """
        app = await self.session.scalar(
            select(SoftwareApp).where(SoftwareApp.id == app_id).with_for_update()
        )
        if app is None:
            raise ManualInstallerError("app_not_found", 404)
        if app.app_status != AppStatus.ACTIVE.value:
            raise ManualInstallerError("app_not_active", 409)
        if app.catalog_status not in {"review", "missing"}:
            raise ManualInstallerError("app_no_longer_unresolved", 409)
        return app


class ManualInstallerInspector:
    """Valida instaladores manuales y combina su evidencia técnica con metadatos de la página y,
    si está configurada, una descripción de IA.
    """

    def __init__(self, settings: Settings) -> None:
        """Prepara el validador binario y el cliente de descripción usando la configuración
        actual.

        Args:
            settings: Configuración de límites, secretos y endpoints.
        """
        self.settings = settings

        self.validator = DownloadValidator(settings)

        self.llm = AppDescriptionLLMClient(settings)


    async def validate_installer(
        self,
        installer_url: str,
        source_page_url: str,
        expected_operating_system: str | None = None,
    ) -> ValidatedManualInstaller:
        """Valida un binario con firma obligatoria, exige HTTPS y formato conocido y comprueba la
        plataforma esperada.

        Args:
            installer_url: URL general del instalador proporcionada por administración.
            source_page_url: Página oficial que aporta el contexto de la inspección.
            expected_operating_system: Plataforma que el administrador asoció a la URL.

        Returns:
            instalador validado con URL final, versión, sistema y arquitectura.

        Raises:
            ManualInstallerError: Si el binario no es seguro, compatible o soportado.
            ManualInstallerTransientError: Si la red o el proveedor permiten reintentar.
        """
        candidate = InstallerCandidate(
            url=installer_url,
            source="admin_manual",
            label="installer setup download",
            referer=source_page_url,
            asset_kind="manual_installer",
        )
        try:
            result = await self.validator.validate(candidate, require_signature=True)
        except httpx.RequestError as exc:
            raise ManualInstallerTransientError("installer_network_error") from exc
        if not result.ok or result.confidence != ValidationConfidence.VALIDATED:
            reason = result.reason or "installer_not_validated"
            if validation_failure_is_transient(reason):
                raise ManualInstallerTransientError(reason)
            raise ManualInstallerError(reason, 422)
        final_url = result.final_url or installer_url
        if urlparse(final_url).scheme != "https":
            raise ManualInstallerError("installer_not_https", 422)
        extension = result.extension
        artifact_format = DEFAULT_ARTIFACT_FORMAT_REGISTRY.get(extension)
        if artifact_format is None:
            raise ManualInstallerError("unsupported_installer_format", 422)
        inferred_operating_system = (
            artifact_format.platforms[0].value if len(artifact_format.platforms) == 1 else None
        )
        if (
            inferred_operating_system
            and expected_operating_system
            and inferred_operating_system != expected_operating_system
        ):
            raise ManualInstallerError(
                "installer_operating_system_mismatch",
                422,
            )
        operating_system = inferred_operating_system or expected_operating_system
        evidence_candidate = InstallerCandidate(
            url=installer_url,
            source="admin_manual",
            label=result.filename,
            context=result.filename,
        )
        architecture = DEFAULT_ARTIFACT_FORMAT_REGISTRY.infer_architecture(
            f"{installer_url} {result.filename or ''}",
            default=ArtifactArchitecture.UNKNOWN,
        ).value
        return ValidatedManualInstaller(
            result=result,
            final_url=final_url,
            version=extract_version(evidence_candidate),
            operating_system=operating_system,
            architecture=architecture,
        )

    async def inspect(
        self,
        app: SoftwareApp,
        installer_inputs: list[tuple[str | None, str]],
        source_page_url: str,
        *,
        set_phase: PhaseCallback,
    ) -> tuple[dict, list[str]]:
        """Valida todos los instaladores, lee metadatos de la página oficial y genera sugerencias
        priorizando valores actuales; la IA solo completa una descripción ausente.

        Args:
            app: Aplicación que debe conservar la misma versión durante la inspección.
            installer_inputs: Pares plataforma-URL que se validarán.
            source_page_url: Página oficial que aporta el contexto de la inspección.
            set_phase: Callback asíncrono que registra la fase visible del procesamiento.

        Returns:
            JSON seguro de sugerencias e instaladores técnicos y lista de advertencias.

        Raises:
            ManualInstallerError: Si no se puede aceptar algún instalador.
            ManualInstallerTransientError: Si la validación debe reintentarse.
        """
        await set_phase("validating_installer")
        validated_installers = [
            await self.validate_installer(
                installer_url,
                source_page_url,
                expected_operating_system,
            )
            for expected_operating_system, installer_url in installer_inputs
        ]
        validated = validated_installers[0]
        detected_installer_versions = {
            item.version for item in validated_installers if item.version
        }
        deterministic_installer_version = (
            next(iter(detected_installer_versions))
            if len(detected_installer_versions) == 1
            else None
        )

        warnings: list[str] = []
        page_evidence: dict[str, str] = {}
        await set_phase("reading_source_page")
        try:
            page_evidence = await fetch_page_evidence(source_page_url, self.settings)
        except SafeHttpError as exc:
            warnings.append(f"source_page:{exc.code}")

        icon = None
        if not app.icon_url and page_evidence.get("icon"):
            await set_phase("validating_icon")
            icon, icon_warning = await validate_icon(page_evidence["icon"], self.settings)
            if icon_warning:
                warnings.append(icon_warning)

        version_value, version_source = suggested_version(
            app.latest_version,
            page_evidence.get("version"),
            deterministic_installer_version,
        )
        name_value, name_source = first_non_empty(
            (app.name, "current"),
            (page_evidence.get("name"), page_evidence.get("name_source")),
            (name_from_filename(validated.result.filename), "filename"),
        )
        publisher_value, publisher_source = first_non_empty(
            (app.publisher, "current"),
            (page_evidence.get("publisher"), "json_ld"),
        )
        description_value, description_source = first_non_empty(
            (app.description, "current"),
            (page_evidence.get("description"), page_evidence.get("description_source")),
        )
        official_value, official_source = first_non_empty(
            (app.official_url, "current"),
            (page_evidence.get("canonical"), "canonical"),
        )
        icon_value, icon_source = first_non_empty(
            (app.icon_url, "current"),
            (icon, page_evidence.get("icon_source")),
        )

        long_description = app.long_description
        long_description_source = "current" if long_description else "unavailable"
        ai_state: dict[str, str | None] = {
            "status": "unavailable",
            "provider": None,
            "model": None,
        }
        await set_phase("generating_description")
        if not long_description and self.llm.has_provider():
            try:
                generated = await self.llm.generate(
                    {
                        "name": name_value,
                        "publisher": publisher_value,
                        "short_description": description_value,
                        "latest_version": version_value,
                        "installers": [
                            {
                                "filename": item.result.filename,
                                "extension": item.result.extension,
                                "operating_system": item.operating_system,
                                "architecture": item.architecture,
                            }
                            for item in validated_installers
                        ],
                        "source_page_metadata": {
                            key: value
                            for key, value in page_evidence.items()
                            if key in SAFE_PAGE_FIELDS and key not in {"icon", "canonical"}
                        },
                    }
                )
                long_description = generated.description
                long_description_source = "generated_ai"
                ai_state = {
                    "status": "ready",
                    "provider": generated.provider,
                    "model": generated.model,
                }
            except LLMGenerationError as exc:
                warnings.append(f"ai:{exc.reason}")
                ai_state["status"] = "failed"
        elif not long_description:
            warnings.append("ai:provider_not_configured")

        technical_installers = [
            {
                "finalDomain": item.result.final_domain,
                "filename": item.result.filename,
                "extension": item.result.extension,
                "contentType": item.result.content_type,
                "sizeBytes": item.result.size_bytes,
                "version": item.version,
                "operatingSystem": item.operating_system,
                "architecture": item.architecture,
                "platformRequired": item.operating_system is None,
            }
            for item in validated_installers
        ]
        result = {
            "suggestions": {
                "name": field_suggestion(name_value, name_source),
                "publisher": field_suggestion(publisher_value, publisher_source),
                "officialUrl": field_suggestion(official_value, official_source),
                "latestVersion": field_suggestion(version_value, version_source),
                "description": field_suggestion(description_value, description_source),
                "longDescription": field_suggestion(
                    long_description,
                    long_description_source,
                ),
                "iconUrl": field_suggestion(icon_value, icon_source),
            },
            "installer": technical_installers[0],
            "installers": technical_installers,
            "ai": ai_state,
        }
        return json_safe(result), warnings


class ManualInstallerWorker:
    """Consume la cola de inspecciones manuales con sesiones independientes y confirma éxito,
    expiración, fallo o reintento mediante InspectionProgress.
    """

    def __init__(self, settings: Settings, worker_id: str = "manual-installer-1") -> None:
        """Configura límites y la identidad usada para reservar mensajes.

        Args:
            settings: Configuración de límites, secretos y endpoints.
            worker_id: Identidad del worker que reserva el trabajo.
        """
        self.settings = settings

        self.worker_id = worker_id


    async def process_one(self) -> bool:
        """Reserva una inspección, verifica que la aplicación y URLs siguen vigentes, ejecuta el
        inspector y confirma el resultado en una única sesión de trabajo.

        Returns:
            False si la cola está vacía; True si se procesó o descartó un mensaje.
        """
        async with AsyncSessionLocal() as session:
            pipeline = PipelineRepository(session)
            item = await pipeline.claim_next(
                QUEUE_MANUAL_INSTALLER_ENRICHMENT,
                self.worker_id,
                lease_seconds=max(60, int(self.settings.request_timeout_seconds * 8)),
            )
            if item is None:
                await session.rollback()
                return False
            await session.commit()
            inspection = await load_inspection(
                session, pipeline, item, ManualInstallerInspection, "inspection_id", "inspection"
            )
            if inspection is None:
                return True
            progress = InspectionProgress(
                session, pipeline, item, inspection, self.settings.manual_inspection_max_attempts
            )
            app = await session.get(SoftwareApp, inspection.software_app_id)
            protector = UrlProtector(self.settings.url_protection_secret)
            installer_inputs = reveal_manual_installer_inputs(inspection, protector)
            source_page_url = protector.reveal(inspection.source_page_url_encrypted)
            if app is None or not installer_inputs or not source_page_url:
                await progress.fail(
                    "app_not_found" if app is None else "inspection_url_unreadable", touch=False
                )
                return True
            if not inspection_app_is_current(app, inspection):
                await progress.expire("app_changed_reinspect_required")
                return True
            await progress.start()
            try:
                result, warnings = await ManualInstallerInspector(self.settings).inspect(
                    app,
                    installer_inputs,
                    source_page_url,
                    set_phase=progress.set_phase,
                )
            except ManualInstallerTransientError as exc:
                await progress.retry(exc.code)
                return True
            except ManualInstallerError as exc:
                await progress.fail(exc.code)
                return True
            except Exception as exc:  # noqa: BLE001 - confirma un código seguro para errores no clasificados
                logger.error(
                    "manual_installer_inspection_failed",
                    inspection_id=str(inspection.id),
                    error=exc.__class__.__name__,
                )
                await progress.fail("inspection_internal_error")
                return True
            await session.refresh(app, attribute_names=["version", "app_status", "catalog_status"])
            if not inspection_app_is_current(app, inspection):
                await progress.expire("app_changed_reinspect_required")
                return True
            await progress.complete(result, warnings)
            return True


def inspection_view(inspection: ManualInstallerInspection) -> dict:
    """Convierte una inspección persistida en el DTO visible para administración, incluyendo
    fases, sugerencias, advertencias, AI y expiración.

    Args:
        inspection: Inspección persistida que se consulta o actualiza.

    Returns:
        diccionario JSON compatible con la API administrativa.
    """
    result = inspection.result_json or {}
    installers = result.get("installers") or (
        [result["installer"]] if result.get("installer") else []
    )
    return {
        "id": str(inspection.id),
        "appId": str(inspection.software_app_id),
        "status": inspection.status,
        "phase": inspection.phase,
        "expectedAppVersion": inspection.captured_app_version,
        "warnings": list(inspection.warnings_json or []),
        "suggestions": result.get("suggestions"),
        "installer": installers[0] if installers else None,
        "installers": installers,
        "ai": result.get("ai"),
        "errorCode": inspection.error_code,
        "sourceRef": str(inspection.source_ref) if inspection.source_ref else None,
        "createdAt": inspection.created_at,
        "updatedAt": inspection.updated_at,
        "expiresAt": inspection.expires_at,
    }


async def fetch_page_evidence(source_page_url: str, settings: Settings) -> dict[str, str]:
    """Descarga una página pública limitada a HTML y transforma su contenido en evidencia
    estructurada.

    Args:
        source_page_url: Página oficial que aporta el contexto de la inspección.
        settings: Configuración de límites, secretos y endpoints.

    Returns:
        campos seguros de la página.

    Raises:
        SafeHttpError: Si el destino no es público, HTTPS o HTML.
    """
    response = await fetch_public_resource(
        source_page_url,
        timeout=settings.request_timeout_seconds,
        max_redirects=settings.max_redirects,
        max_bytes=settings.manual_page_max_bytes,
        accept="text/html,application/xhtml+xml;q=0.9",
    )
    if response.content_type and response.content_type not in {
        "text/html",
        "application/xhtml+xml",
    }:
        raise SafeHttpError("source_page_not_html")
    return parse_page_evidence(response.content, response.final_url)


def parse_page_evidence(content: bytes, page_url: str) -> dict[str, str]:
    """Extrae SoftwareApplication JSON-LD, metadatos Open Graph/Twitter, icono y canonical,
    manteniendo la procedencia de cada valor.

    Args:
        content: Bytes HTML de la página de procedencia.
        page_url: URL final de la página cuyo HTML se analiza.

    Returns:
        mapa de evidencia no vacío y limitado por campo.
    """
    html = content.decode("utf-8", errors="replace")
    parser = HTMLParser(html)
    evidence: dict[str, str] = {}
    _add_structured_evidence(parser, page_url, evidence)
    metadata = meta_values(parser)
    title = parser.css_first("title")
    fallback_fields = {
        "name": [
            (metadata.get("og:title"), "open_graph"),
            (metadata.get("twitter:title"), "twitter"),
            (safe_value(title.text() if title else None, 180), "source_page"),
        ],
        "publisher": [(metadata.get("og:site_name"), "open_graph")],
        "description": [
            (metadata.get("og:description"), "open_graph"),
            (metadata.get("twitter:description"), "twitter"),
            (metadata.get("description"), "open_graph"),
        ],
    }
    for field, choices in fallback_fields.items():
        if evidence.get(field):
            continue
        for value, source in choices:
            if value:
                evidence[field], evidence[f"{field}_source"] = value, source
                break
    _add_icon_evidence(parser, page_url, metadata, evidence)

    canonical = canonical_url(parser, page_url)
    if canonical:
        evidence["canonical"] = canonical
    return {key: value for key, value in evidence.items() if value}


def first_software_application(parser: HTMLParser) -> dict | None:
    """Busca en los primeros bloques JSON-LD la primera entidad SoftwareApplication válida.

    Args:
        parser: Árbol HTML ya construido por selectolax.

    Returns:
        entidad JSON-LD o None.
    """
    for node in parser.css('script[type="application/ld+json"]')[:20]:
        raw = (node.text() or "")[:100_000]
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError, TypeError:
            continue
        for item in json_ld_items(payload):
            item_type = item.get("@type")
            types = item_type if isinstance(item_type, list) else [item_type]
            if any(str(value).casefold() == "softwareapplication" for value in types):
                return item
    return None


def json_ld_items(payload: object) -> list[dict]:
    """Aplana objetos, listas y @graph de JSON-LD sin procesar más de cien elementos.

    Args:
        payload: Objeto JSON-LD o mapa recibido del documento.

    Returns:
        lista de mapas JSON-LD.
    """
    if isinstance(payload, list):
        return [item for value in payload for item in json_ld_items(value)]
    if not isinstance(payload, dict):
        return []
    items = [payload]
    graph = payload.get("@graph")
    if isinstance(graph, (list, dict)):
        items.extend(json_ld_items(graph))
    return items[:100]


def meta_values(parser: HTMLParser) -> dict[str, str]:
    """Lee una allowlist de meta tags y limita cada valor para evitar importar contenido
    arbitrario.

    Args:
        parser: Árbol HTML ya construido por selectolax.

    Returns:
        mapa de metadatos normalizados.
    """
    allowlist = {
        "description",
        "og:title",
        "og:description",
        "og:image",
        "og:site_name",
        "twitter:title",
        "twitter:description",
        "twitter:image",
    }
    values: dict[str, str] = {}
    for node in parser.css("meta")[:200]:
        key = node.attributes.get("property") or node.attributes.get("name")
        content = node.attributes.get("content")
        if not key or not content:
            continue
        normalized = key.casefold()
        if normalized in allowlist and normalized not in values:
            values[normalized] = safe_value(content, 4000)
    return values


def page_icon_url(parser: HTMLParser, page_url: str) -> str | None:
    """Encuentra un link icon o apple-touch-icon, lo resuelve contra la página y exige sintaxis
    HTTPS pública.

    Args:
        parser: Árbol HTML ya construido por selectolax.
        page_url: URL final de la página cuyo HTML se analiza.

    Returns:
        URL de icono segura o None.
    """
    for node in parser.css("link")[:100]:
        rel = (node.attributes.get("rel") or "").casefold().split()
        href = node.attributes.get("href")
        if not href or not ({"icon", "apple-touch-icon"} & set(rel)):
            continue
        candidate = safe_join(page_url, href)
        try:
            return validate_public_https_syntax(candidate)
        except SafeHttpError:
            continue
    return None


def canonical_url(parser: HTMLParser, page_url: str) -> str | None:
    """Obtiene canonical solo si conserva el dominio registrado, no lleva credenciales y pasa la
    validación HTTPS.

    Args:
        parser: Árbol HTML ya construido por selectolax.
        page_url: URL final de la página cuyo HTML se analiza.

    Returns:
        URL canónica segura o None.
    """
    page_domain = registered_domain(page_url)
    for node in parser.css("link")[:100]:
        rel = (node.attributes.get("rel") or "").casefold().split()
        href = node.attributes.get("href")
        if "canonical" not in rel or not href:
            continue
        candidate = safe_join(page_url, href)
        try:
            candidate = validate_public_https_syntax(candidate)
        except SafeHttpError:
            return None
        if has_sensitive_query(candidate):
            return None
        if registered_domain(candidate) != page_domain:
            return None
        return candidate
    return None


async def validate_icon(icon_url: str, settings: Settings) -> tuple[str | None, str | None]:
    """Descarga un icono público con límite de tamaño y acepta únicamente content types image/*
    sin credenciales en la URL final.

    Args:
        icon_url: URL candidata del icono de la aplicación.
        settings: Configuración de límites, secretos y endpoints.

    Returns:
        URL final y None, o None y advertencia.

    Raises:
        SafeHttpError: Se captura y se convierte en advertencia de campo.
    """
    try:
        icon_url = validate_public_https_syntax(icon_url)
        if has_sensitive_query(icon_url):
            raise SafeHttpError("icon_query_credentials_forbidden")
        response = await fetch_public_resource(
            icon_url,
            timeout=settings.request_timeout_seconds,
            max_redirects=settings.max_redirects,
            max_bytes=settings.icon_max_bytes,
            accept="image/png,image/jpeg,image/webp,image/svg+xml,image/x-icon",
        )
    except SafeHttpError as exc:
        return None, f"icon:{exc.code}"
    if not response.content_type or not response.content_type.startswith("image/"):
        return None, "icon:content_type_invalid"
    if has_sensitive_query(response.final_url):
        return None, "icon:query_credentials_forbidden"
    return response.final_url, None


def inspection_input_hash(
    installer_url: str | None,
    source_page_url: str,
    secret: str,
    installer_urls: dict[str, str] | None = None,
) -> str:
    """Calcula HMAC-SHA256 estable de la página y de las URLs generales y por plataforma para
    detectar reservas equivalentes.

    Args:
        installer_url: URL general del instalador proporcionada por administración.
        source_page_url: Página oficial que aporta el contexto de la inspección.
        secret: Secreto usado como clave HMAC de la huella de entrada.
        installer_urls: URLs opcionales separadas por plataforma.

    Returns:
        huella hexadecimal de la entrada.
    """
    raw = "\n".join(
        [
            installer_url or "",
            source_page_url,
            *[
                f"{operating_system}={(installer_urls or {}).get(operating_system, '')}"
                for operating_system in MANUAL_INSTALLER_PLATFORMS
            ],
        ]
    )
    return hmac.new(
        secret.encode("utf-8"),
        raw.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()


async def validate_manual_installer_urls(
    installer_urls: dict[str, str | None],
) -> dict[str, str]:
    """Limpia y valida de forma independiente las URLs por plataforma, conservando solo las
    presentes y públicas.

    Args:
        installer_urls: URLs opcionales separadas por plataforma.

    Returns:
        mapa de URLs HTTPS validadas.

    Raises:
        SafeHttpError: Si una URL no cumple la política pública.
    """
    validated: dict[str, str] = {}
    for operating_system in MANUAL_INSTALLER_PLATFORMS:
        value = clean_optional(installer_urls.get(operating_system))
        if value:
            validated[operating_system] = await validate_public_https_url(value)
    return validated


def protect_optional_url(
    protector: UrlProtector,
    value: str | None,
) -> str | None:
    """Protege una URL cuando existe y devuelve None para valores ausentes.

    Args:
        protector: Protector usado para cifrar y revelar URLs persistidas.
        value: Valor opcional que se limpia o protege.
    """
    return protector.protect(value) if value else None


def reveal_manual_installer_inputs(
    inspection: ManualInstallerInspection,
    protector: UrlProtector,
) -> list[tuple[str | None, str]] | None:
    """Revela la URL general y las URLs específicas por plataforma en el orden de persistencia.

    Args:
        inspection: Inspección persistida que se consulta o actualiza.
        protector: Protector usado para cifrar y revelar URLs persistidas.

    Returns:
        pares plataforma-URL o None si una URL cifrada no puede revelarse.
    """
    installer_inputs: list[tuple[str | None, str]] = []
    if inspection.installer_url_encrypted:
        installer_url = protector.reveal(inspection.installer_url_encrypted)
        if not installer_url:
            return None
        installer_inputs.append((None, installer_url))
    for operating_system, column_name in MANUAL_INSTALLER_URL_COLUMNS.items():
        protected_url = getattr(inspection, column_name)
        if not protected_url:
            continue
        installer_url = protector.reveal(protected_url)
        if not installer_url:
            return None
        installer_inputs.append((operating_system, installer_url))
    return installer_inputs


def validation_failure_is_transient(reason: str) -> bool:
    """Clasifica ausencia de respuesta, falta de verificación y HTTP 408/425/429/5xx como causas
    reintentables.

    Args:
        reason: Código de rechazo devuelto por la validación.

    Returns:
        True si el worker debe reencolar.
    """
    if reason in TRANSIENT_VALIDATION_REASONS:
        return True
    if not reason.startswith("http_"):
        return False
    try:
        status_code = int(reason.removeprefix("http_"))
    except ValueError:
        return False
    return status_code in {408, 425, 429} or status_code >= 500


def field_suggestion(value: str | None, source: str | None) -> dict[str, str | None]:
    """Empaqueta un valor limpio con una procedencia allowlisted, sustituyendo procedencias
    desconocidas por unavailable.

    Args:
        value: Valor opcional que se limpia o protege.
        source: Procedencia declarada de una sugerencia editorial.

    Returns:
        DTO value/source.
    """
    safe_source = (
        source
        if source
        in {
            "current",
            "json_ld",
            "open_graph",
            "twitter",
            "canonical",
            "filename",
            "generated_ai",
            "manual",
            "source_page",
        }
        else "unavailable"
    )
    return {"value": clean_optional(value), "source": safe_source}


def first_non_empty(*candidates: tuple[str | None, str | None]) -> tuple[str | None, str]:
    """Elige el primer valor no vacío y conserva la procedencia que lo produjo.

    Args:
        candidates: Pares valor-procedencia que se prueban en orden.

    Returns:
        valor y fuente, o None/unavailable.
    """
    for value, source in candidates:
        cleaned = clean_optional(value)
        if cleaned:
            return cleaned, source or "unavailable"
    return None, "unavailable"


def suggested_version(
    current: str | None,
    page: str | None,
    filename: str | None,
) -> tuple[str | None, str]:
    """Compara versión actual, JSON-LD y nombre de archivo y propone solo una versión observada
    que sea más nueva.

    Args:
        current: Valor actualmente almacenado en la aplicación.
        page: Versión observada en la página de origen.
        filename: Nombre de archivo del instalador validado.

    Returns:
        versión y procedencia.
    """
    current_clean = clean_optional(current)
    current_key = version_key(current_clean)
    for candidate, source in ((page, "json_ld"), (filename, "filename")):
        candidate_clean = clean_optional(candidate)
        candidate_key = version_key(candidate_clean)
        if not candidate_clean or candidate_key is None:
            continue
        if current_clean is None:
            return candidate_clean, source
        if current_key is not None and candidate_key > current_key:
            return candidate_clean, source
    return (current_clean, "current") if current_clean else (None, "unavailable")


def version_key(value: str | None) -> tuple[int, ...] | None:
    """Parsea hasta seis componentes numéricos con prefijo v opcional para compararlos de forma
    determinista.

    Args:
        value: Valor opcional que se limpia o protege.

    Returns:
        tupla de enteros o None.
    """
    if not value:
        return None
    match = re.fullmatch(r"\s*v?(\d+(?:\.\d+){0,5})(?:[-+][A-Za-z0-9.-]+)?\s*", value)
    if not match:
        return None
    return tuple(int(part) for part in match.group(1).split("."))


def same_installer_evidence(
    technical: dict,
    validated: ValidatedManualInstaller,
) -> bool:
    """Compara dominio, nombre, extensión y tamaño de un instalador revisado con la validación
    actual.

    Args:
        technical: Metadatos técnicos publicados previamente.
        validated: Instalador manual validado en la inspección actual.

    Returns:
        True si representan el mismo binario.
    """
    result = validated.result
    expected = (
        technical.get("finalDomain"),
        technical.get("filename"),
        technical.get("extension"),
        technical.get("sizeBytes"),
    )
    actual = (
        result.final_domain,
        result.filename,
        result.extension,
        result.size_bytes,
    )
    return expected == actual


def description_provenance(
    reviewed: str | None,
    generated: object,
    ai_state: dict,
) -> tuple[str, str | None, str | None]:
    """Determina si la descripción revisada sigue siendo generada por IA o fue modificada
    manualmente.

    Args:
        reviewed: Valor confirmado por la persona administradora.
        generated: Descripción generada anteriormente por IA.
        ai_state: Estado y proveedor de la generación automática.

    Returns:
        estado, proveedor y modelo de la descripción.
    """
    reviewed_clean = clean_optional(reviewed)
    if not reviewed_clean:
        return LongDescriptionStatus.PENDING.value, None, None
    if reviewed_clean == clean_optional(generated):
        return (
            LongDescriptionStatus.COMPLETED.value,
            clean_optional(ai_state.get("provider")) or "generated_ai",
            clean_optional(ai_state.get("model")),
        )
    return LongDescriptionStatus.COMPLETED.value, "admin_manual", None


def reviewed_field_sources(
    suggestions: dict,
    reviewed: dict[str, object],
) -> dict[str, str]:
    """Asigna procedencia automática a los valores revisados que coinciden con sugerencias y
    manual al resto.

    Args:
        suggestions: Mapa de sugerencias producidas durante la inspección.
        reviewed: Valor confirmado por la persona administradora.

    Returns:
        mapa campo-procedencia.
    """
    sources: dict[str, str] = {}
    for key, reviewed_value in reviewed.items():
        suggestion = suggestions.get(key)
        if not isinstance(suggestion, dict):
            sources[key] = "manual"
            continue
        sources[key] = (
            str(suggestion.get("source") or "unavailable")
            if clean_optional(suggestion.get("value")) == clean_optional(reviewed_value)
            else "manual"
        )
    return sources


def name_from_filename(filename: str | None) -> str | None:
    """Deriva un nombre legible eliminando extensión y sufijos setup/installer/install del nombre
    de archivo.

    Args:
        filename: Nombre de archivo del instalador validado.

    Returns:
        nombre acotado o None.
    """
    if not filename:
        return None
    name = filename
    for extension in sorted(
        DEFAULT_ARTIFACT_FORMAT_REGISTRY.extensions,
        key=len,
        reverse=True,
    ):
        if name.casefold().endswith(extension):
            name = name[: -len(extension)]
            break
    name = re.sub(r"(?i)(?:[-_. ]?(?:setup|installer|install))+$", "", name)
    name = re.sub(r"[-_.]+", " ", name)
    name = re.sub(r"\s+", " ", name).strip()
    return name[:180] or None


def nested_name(value: object) -> str | None:
    """Lee un nombre desde una cadena o desde la propiedad name de un objeto JSON-LD.

    Args:
        value: Valor opcional que se limpia o protege.

    Returns:
        nombre o None.
    """
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        nested = value.get("name")
        return nested if isinstance(nested, str) else None
    return None


def nested_url(value: object) -> str | None:
    """Busca URL o contentUrl de forma recursiva en cadenas, listas y objetos JSON-LD.

    Args:
        value: Valor opcional que se limpia o protege.

    Returns:
        URL o None.
    """
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        for item in value:
            candidate = nested_url(item)
            if candidate:
                return candidate
        return None
    if isinstance(value, dict):
        for key in ("url", "contentUrl"):
            nested = value.get(key)
            if isinstance(nested, str):
                return nested
    return None


def safe_join(base_url: str, value: str) -> str:
    """Resuelve una referencia relativa y limita el resultado a 2048 caracteres, devolviendo
    vacío ante sintaxis inválida.

    Args:
        base_url: URL base usada para resolver una referencia relativa.
        value: Valor opcional que se limpia o protege.

    Returns:
        URL resuelta o cadena vacía.
    """
    try:
        return urljoin(base_url, value.strip())[:2048]
    except ValueError:
        return ""


def safe_value(value: object, max_length: int) -> str:
    """Acepta solo texto, normaliza espacios y lo corta al máximo indicado.

    Args:
        value: Valor opcional que se limpia o protege.
        max_length: Límite de caracteres que se conservará.

    Returns:
        texto seguro o cadena vacía.
    """
    if not isinstance(value, str):
        return ""
    return re.sub(r"\s+", " ", value).strip()[:max_length]


def clean_optional(value: object) -> str | None:
    """Normaliza espacios de un texto opcional y convierte valores no textuales o vacíos en None.

    Args:
        value: Valor opcional que se limpia o protege.

    Returns:
        texto limpio o None.
    """
    if not isinstance(value, str):
        return None
    cleaned = re.sub(r"\s+", " ", value).strip()
    return cleaned or None


def inspection_app_is_current(app: SoftwareApp, inspection: ManualInstallerInspection) -> bool:
    """Comprueba que la inspección conserva la versión capturada y que la aplicación sigue activa
    y pendiente.

    Args:
        app: Aplicación que debe conservar la misma versión durante la inspección.
        inspection: Inspección persistida que se consulta o actualiza.

    Returns:
        True si aún se pueden aplicar sus sugerencias.
    """
    return (
        app.version == inspection.captured_app_version
        and app.app_status == AppStatus.ACTIVE.value
        and app.catalog_status in {"review", "missing"}
    )


def _add_structured_evidence(parser: HTMLParser, page_url: str, evidence: dict[str, str]) -> None:
    """Añade al mapa la entidad SoftwareApplication JSON-LD y sus campos de editor, versión,
    descripción e icono.

    Args:
        parser: Árbol HTML ya construido por selectolax.
        page_url: URL final de la página cuyo HTML se analiza.
        evidence: Mapa mutable donde se acumula evidencia segura.
    """
    json_ld = first_software_application(parser)
    if json_ld:
        evidence["name"] = safe_value(json_ld.get("name"), 180)
        if evidence["name"]:
            evidence["name_source"] = "json_ld"
        evidence["publisher"] = safe_value(
            nested_name(json_ld.get("publisher")) or nested_name(json_ld.get("author")),
            180,
        )
        if evidence["publisher"]:
            evidence["publisher_source"] = "json_ld"
        evidence["version"] = safe_value(
            json_ld.get("softwareVersion") or json_ld.get("version"),
            100,
        )
        evidence["description"] = safe_value(json_ld.get("description"), 4000)
        evidence["description_source"] = "json_ld"
        json_ld_icon = nested_url(json_ld.get("image")) or nested_url(json_ld.get("logo"))
        if json_ld_icon:
            evidence["icon"] = safe_join(page_url, json_ld_icon)
            evidence["icon_source"] = "json_ld"


def _add_icon_evidence(
    parser: HTMLParser, page_url: str, metadata: dict[str, str], evidence: dict[str, str]
) -> None:
    """Completa el icono ausente con Open Graph, Twitter o links icon de la página, en ese orden.

    Args:
        parser: Árbol HTML ya construido por selectolax.
        page_url: URL final de la página cuyo HTML se analiza.
        metadata: Metadatos de la página usados como fallback para el icono.
        evidence: Mapa mutable donde se acumula evidencia segura.
    """
    if not evidence.get("icon"):
        icon = metadata.get("og:image") or metadata.get("twitter:image")
        if icon:
            evidence["icon"] = safe_join(page_url, icon)
            evidence["icon_source"] = "open_graph" if metadata.get("og:image") else "twitter"
        else:
            linked_icon = page_icon_url(parser, page_url)
            if linked_icon:
                evidence["icon"] = linked_icon
                evidence["icon_source"] = "source_page"
