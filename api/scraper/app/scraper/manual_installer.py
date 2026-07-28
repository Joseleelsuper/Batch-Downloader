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
    ResolutionStatus,
    ValidationStatus,
)
from app.db.models import (
    DownloadSource,
    ManualInstallerInspection,
    SoftwareApp,
)
from app.db.session import AsyncSessionLocal
from app.repositories.catalog import CatalogRepository, ResolvedSourceCreate
from app.repositories.pipeline import (
    QUEUE_MANUAL_INSTALLER_ENRICHMENT,
    PipelineRepository,
)
from app.schemas.internal import ManualInstallerApplyRequest
from app.scraper.artifacts import (
    DEFAULT_ARTIFACT_FORMAT_REGISTRY,
    ArtifactArchitecture,
)
from app.scraper.candidates import InstallerCandidate, extract_version, registered_domain
from app.scraper.description_enricher import AppDescriptionLLMClient
from app.scraper.llm import LLMGenerationError
from app.scraper.safe_http import (
    SafeHttpError,
    fetch_public_resource,
    has_sensitive_query,
    validate_public_https_syntax,
    validate_public_https_url,
)
from app.scraper.text import normalize_text
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
    def __init__(self, code: str, status_code: int) -> None:
        super().__init__(code)
        self.code = code
        self.status_code = status_code


class ManualInstallerTransientError(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class ValidatedManualInstaller:
    result: ValidationResult
    final_url: str
    version: str | None
    operating_system: str | None
    architecture: str


class ManualInstallerInspectionRepository:
    def __init__(
        self,
        session: AsyncSession,
        protector: UrlProtector,
        settings: Settings,
    ) -> None:
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
        installer_url = clean_optional(installer_url)
        if installer_url:
            installer_url = await validate_public_https_url(installer_url)
        safe_installer_urls = await validate_manual_installer_urls(
            installer_urls or {}
        )
        if not installer_url and not safe_installer_urls:
            raise ManualInstallerError(
                "at_least_one_installer_url_required",
                422,
            )
        source_page_url = await validate_public_https_url(source_page_url)
        if has_sensitive_query(source_page_url):
            raise ManualInstallerError("source_page_query_credentials_forbidden", 422)

        app = await self.session.scalar(
            select(SoftwareApp)
            .where(SoftwareApp.id == app_id)
            .with_for_update()
        )
        if app is None:
            raise ManualInstallerError("app_not_found", 404)
        if app.app_status != AppStatus.ACTIVE.value:
            raise ManualInstallerError("app_not_active", 409)
        if app.catalog_status not in {"review", "missing"}:
            raise ManualInstallerError("app_no_longer_unresolved", 409)

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
            .where(
                ManualInstallerInspection.captured_app_version == app.version
            )
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


class ManualInstallerInspector:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.validator = DownloadValidator(settings)
        self.llm = AppDescriptionLLMClient(settings)

    async def validate_installer(
        self,
        installer_url: str,
        source_page_url: str,
        expected_operating_system: str | None = None,
    ) -> ValidatedManualInstaller:
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
            artifact_format.platforms[0].value
            if len(artifact_format.platforms) == 1
            else None
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
            item.version
            for item in validated_installers
            if item.version
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
    """Single-consumer worker for persistent manual inspection previews."""

    def __init__(self, settings: Settings, worker_id: str = "manual-installer-1") -> None:
        self.settings = settings
        self.worker_id = worker_id

    async def process_one(self) -> bool:
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

            try:
                inspection_id = uuid.UUID(str((item.payload_json or {}).get("inspection_id")))
            except (ValueError, TypeError, AttributeError):
                await pipeline.fail(item, "invalid_inspection_id")
                await session.commit()
                return True

            inspection = await session.get(ManualInstallerInspection, inspection_id)
            if inspection is None:
                await pipeline.discard(item, "inspection_not_found")
                await session.commit()
                return True
            if inspection.status in {"applied", "expired"}:
                await pipeline.discard(item, f"inspection_{inspection.status}")
                await session.commit()
                return True
            if inspection.expires_at <= utc_now():
                inspection.status = "expired"
                inspection.phase = "expired"
                inspection.error_code = "inspection_expired"
                await pipeline.discard(item, "inspection_expired")
                await session.commit()
                return True

            app = await session.get(SoftwareApp, inspection.software_app_id)
            protector = UrlProtector(self.settings.url_protection_secret)
            installer_inputs = reveal_manual_installer_inputs(
                inspection,
                protector,
            )
            source_page_url = protector.reveal(inspection.source_page_url_encrypted)
            if app is None or not installer_inputs or not source_page_url:
                inspection.status = "failed"
                inspection.phase = "failed"
                inspection.error_code = (
                    "app_not_found" if app is None else "inspection_url_unreadable"
                )
                await pipeline.fail(item, inspection.error_code)
                await session.commit()
                return True
            if (
                app.version != inspection.captured_app_version
                or app.app_status != AppStatus.ACTIVE.value
                or app.catalog_status not in {"review", "missing"}
            ):
                inspection.status = "expired"
                inspection.phase = "expired"
                inspection.error_code = "app_changed_reinspect_required"
                inspection.updated_at = utc_now()
                await pipeline.discard(item, inspection.error_code)
                await session.commit()
                return True

            inspection.status = "running"
            inspection.phase = "starting"
            inspection.error_code = None
            inspection.updated_at = utc_now()
            await session.commit()

            async def set_phase(phase: str) -> None:
                inspection.phase = phase
                inspection.updated_at = utc_now()
                await session.commit()

            inspector = ManualInstallerInspector(self.settings)
            try:
                result, warnings = await inspector.inspect(
                    app,
                    installer_inputs,
                    source_page_url,
                    set_phase=set_phase,
                )
            except ManualInstallerTransientError as exc:
                if item.attempts < self.settings.manual_inspection_max_attempts:
                    inspection.status = "queued"
                    inspection.phase = "retry_wait"
                    inspection.error_code = None
                    inspection.warnings_json = append_warning(
                        inspection.warnings_json,
                        f"retry:{exc.code}",
                    )
                    await pipeline.requeue(
                        item,
                        exc.code,
                        delay_seconds=min(60, 2 ** max(1, item.attempts)),
                    )
                else:
                    inspection.status = "failed"
                    inspection.phase = "failed"
                    inspection.error_code = exc.code
                    await pipeline.fail(item, exc.code)
                inspection.updated_at = utc_now()
                await session.commit()
                return True
            except ManualInstallerError as exc:
                inspection.status = "failed"
                inspection.phase = "failed"
                inspection.error_code = exc.code
                inspection.updated_at = utc_now()
                await pipeline.fail(item, exc.code)
                await session.commit()
                return True
            except Exception as exc:  # noqa: BLE001 - persist a typed safe failure
                logger.error(
                    "manual_installer_inspection_failed",
                    inspection_id=str(inspection.id),
                    error=exc.__class__.__name__,
                )
                inspection.status = "failed"
                inspection.phase = "failed"
                inspection.error_code = "inspection_internal_error"
                inspection.updated_at = utc_now()
                await pipeline.fail(item, "inspection_internal_error")
                await session.commit()
                return True

            await session.refresh(
                app,
                attribute_names=["version", "app_status", "catalog_status"],
            )
            if (
                app.version != inspection.captured_app_version
                or app.app_status != AppStatus.ACTIVE.value
                or app.catalog_status not in {"review", "missing"}
            ):
                inspection.status = "expired"
                inspection.phase = "expired"
                inspection.error_code = "app_changed_reinspect_required"
                inspection.updated_at = utc_now()
                await pipeline.discard(item, inspection.error_code)
                await session.commit()
                return True

            inspection.result_json = result
            inspection.warnings_json = append_warning(
                inspection.warnings_json,
                *warnings,
            )
            inspection.status = "ready"
            inspection.phase = "ready"
            inspection.error_code = None
            inspection.updated_at = utc_now()
            await pipeline.complete(item)
            await session.commit()
            return True


async def apply_manual_installer(
    session: AsyncSession,
    settings: Settings,
    app_id: uuid.UUID,
    inspection_id: uuid.UUID,
    request: ManualInstallerApplyRequest,
) -> tuple[SoftwareApp, list[uuid.UUID], list[str]]:
    protector = UrlProtector(settings.url_protection_secret)
    repository = ManualInstallerInspectionRepository(session, protector, settings)
    inspection = await repository.get(app_id, inspection_id, for_update=True)
    if inspection is None:
        raise ManualInstallerError("inspection_not_found", 404)
    if inspection.status == "applied" and inspection.source_ref is not None:
        app = await session.get(SoftwareApp, app_id)
        if app is None:
            raise ManualInstallerError("app_not_found", 404)
        if (
            inspection.applied_app_version is None
            or app.version != inspection.applied_app_version
            or app.app_status != AppStatus.ACTIVE.value
            or app.catalog_status != "available"
        ):
            raise ManualInstallerError("app_changed_reinspect_required", 409)
        applied_source_refs = [
            uuid.UUID(value)
            for value in (inspection.result_json or {}).get(
                "appliedSourceRefs",
                [str(inspection.source_ref)],
            )
        ]
        return app, applied_source_refs, list(inspection.warnings_json or [])
    if inspection.status == "expired":
        raise ManualInstallerError("inspection_expired", 409)
    if inspection.status != "ready" or not inspection.result_json:
        raise ManualInstallerError("inspection_not_ready", 409)

    installer_inputs = reveal_manual_installer_inputs(
        inspection,
        protector,
    )
    source_page_url = protector.reveal(inspection.source_page_url_encrypted)
    if not installer_inputs or not source_page_url:
        raise ManualInstallerError("inspection_url_unreadable", 409)

    if not request.name.strip():
        raise ManualInstallerError("name_required", 422)

    app_snapshot = await session.get(SoftwareApp, app_id)
    if app_snapshot is None:
        raise ManualInstallerError("app_not_found", 404)

    official_url = clean_optional(request.official_url)
    official_warning: str | None = None
    if official_url and official_url != clean_optional(app_snapshot.official_url):
        try:
            official_url = await validate_public_https_url(official_url)
            if has_sensitive_query(official_url):
                raise SafeHttpError("query_credentials_forbidden")
        except SafeHttpError as exc:
            official_warning = f"official_url:{exc.code}"
            official_url = clean_optional(app_snapshot.official_url)

    reviewed_icon_url = clean_optional(request.icon_url)
    validated_icon_url: str | None = None
    icon_warning: str | None = None
    if reviewed_icon_url:
        validated_icon_url, icon_warning = await validate_icon(
            reviewed_icon_url,
            settings,
        )

    inspector = ManualInstallerInspector(settings)
    validated_installers = [
        await inspector.validate_installer(
            installer_url,
            source_page_url,
            expected_operating_system,
        )
        for expected_operating_system, installer_url in installer_inputs
    ]
    technical_installers = inspection.result_json.get("installers") or [
        inspection.result_json.get("installer") or {}
    ]
    if len(technical_installers) != len(validated_installers) or any(
        not same_installer_evidence(technical, validated)
        for technical, validated in zip(
            technical_installers,
            validated_installers,
            strict=True,
        )
    ):
        raise ManualInstallerError("installer_changed_reinspect_required", 409)

    app = await session.scalar(
        select(SoftwareApp)
        .where(SoftwareApp.id == app_id)
        .with_for_update()
    )
    if app is None:
        raise ManualInstallerError("app_not_found", 404)
    if (
        app.version != request.expected_app_version
        or app.version != inspection.captured_app_version
    ):
        raise ManualInstallerError("app_version_conflict", 409)
    if app.app_status != AppStatus.ACTIVE.value or app.catalog_status not in {
        "review",
        "missing",
    }:
        raise ManualInstallerError("app_no_longer_unresolved", 409)

    resolved_platform_installers: list[
        tuple[ValidatedManualInstaller, str, str]
    ] = []
    for validated in validated_installers:
        operating_system = validated.operating_system
        if operating_system is None and len(validated_installers) == 1:
            operating_system = request.operating_system
        if operating_system is None:
            raise ManualInstallerError("operating_system_required", 422)
        architecture = (
            "UNKNOWN"
            if validated.architecture == ArtifactArchitecture.UNKNOWN.value
            else validated.architecture
        )
        resolved_platform_installers.append(
            (validated, operating_system, architecture)
        )

    app.name = request.name.strip()
    app.normalized_name = normalize_text(app.name)
    app.publisher = clean_optional(request.publisher)
    app.official_url = official_url
    app.latest_version = clean_optional(request.latest_version)
    app.description = clean_optional(request.description)
    if validated_icon_url:
        app.icon_url = validated_icon_url
    elif reviewed_icon_url is None:
        app.icon_url = None
    if icon_warning:
        inspection.warnings_json = append_warning(
            inspection.warnings_json,
            icon_warning,
        )
    if official_warning:
        inspection.warnings_json = append_warning(
            inspection.warnings_json,
            official_warning,
        )
    app.updated_at = utc_now()
    app.version += 1

    reviewed_long_description = clean_optional(request.long_description)
    long_description_suggestion = (
        (inspection.result_json.get("suggestions") or {}).get("longDescription") or {}
    )
    suggested_long_description = clean_optional(long_description_suggestion.get("value"))
    suggestion_source = long_description_suggestion.get("source")
    ai_state = inspection.result_json.get("ai") or {}
    previous_long_description = clean_optional(app.long_description)
    preserve_current_description = (
        suggestion_source == "current"
        and reviewed_long_description == previous_long_description
    )
    if not preserve_current_description:
        app.long_description = reviewed_long_description
        app.long_description_language = "es" if reviewed_long_description else None
        app.long_description_error = None
        app.long_description_generated_at = utc_now() if reviewed_long_description else None
        (
            app.long_description_status,
            app.long_description_source,
            app.long_description_model,
        ) = description_provenance(
            reviewed_long_description,
            (
                suggested_long_description
                if suggestion_source == "generated_ai"
                else None
            ),
            ai_state,
        )
        app.long_description_input_hash = None
    metadata = dict(app.metadata_json or {})
    metadata["manual_installer"] = {
        "inspection_id": str(inspection.id),
        "applied_at": utc_now().isoformat(),
        "field_sources": reviewed_field_sources(
            inspection.result_json.get("suggestions") or {},
            {
                "name": app.name,
                "publisher": app.publisher,
                "officialUrl": app.official_url,
                "latestVersion": app.latest_version,
                "description": app.description,
                "longDescription": app.long_description,
                "iconUrl": app.icon_url,
            },
        ),
    }
    app.metadata_json = json_safe(metadata)

    catalog = CatalogRepository(session, protector)
    source_ids: set[uuid.UUID] = set()
    resolved_ids: list[uuid.UUID] = []
    for index, (
        validated,
        operating_system,
        architecture,
    ) in enumerate(resolved_platform_installers):
        source = await catalog.source_for_platform(
            app.id,
            operating_system,
            architecture,
        )
        if source is None:
            source = DownloadSource(
                software_app_id=app.id,
                operating_system=operating_system,
                architecture=architecture,
                initial_url=source_page_url,
                resolver_type="manual_http",
                resolver_config={"source": "admin_manual"},
                resolution_status=ResolutionStatus.MISSING.value,
                validation_status=ValidationStatus.UNCHECKED.value,
            )
            session.add(source)
            await session.flush()
        else:
            source.initial_url = source_page_url
            source.resolver_type = "manual_http"
            source.resolver_config = {"source": "admin_manual"}
            source.updated_at = utc_now()

        source_ids.add(source.id)
        await catalog.expire_valid_resolved_sources(source.id)
        resolved = await catalog.save_resolved_source(
            ResolvedSourceCreate(
                source_id=source.id,
                url=validated.final_url,
                final_domain=validated.result.final_domain
                or registered_domain(validated.final_url)
                or urlparse(validated.final_url).hostname
                or "unknown",
                filename=validated.result.filename,
                extension=validated.result.extension,
                content_type=validated.result.content_type,
                size_bytes=validated.result.size_bytes,
                version=validated.version or app.latest_version,
                release_rank=0,
                is_latest=True,
                version_status="latest",
                score=100,
                status=ResolutionStatus.DIRECT,
                validation_status=ValidationStatus.VALID,
                metadata={
                    "candidate_source": "admin_manual",
                    "inspection_id": str(inspection.id),
                    "validation_confidence": (
                        ValidationConfidence.VALIDATED.value
                    ),
                    "operating_system": operating_system,
                    "architecture": architecture,
                    "is_primary": index == 0,
                    "is_latest": True,
                },
            )
        )
        await session.flush()
        resolved_ids.append(resolved.id)

    await catalog.refresh_source_statuses(source_ids)
    await catalog.refresh_operating_systems(app.id)
    await session.refresh(
        app,
        attribute_names=["version", "app_status", "catalog_status"],
    )

    inspection.status = "applied"
    inspection.phase = "applied"
    inspection.applied_at = utc_now()
    inspection.applied_app_version = app.version
    inspection.source_ref = resolved_ids[0]
    inspection.result_json = {
        **inspection.result_json,
        "appliedSourceRefs": [str(source_ref) for source_ref in resolved_ids],
    }
    inspection.updated_at = utc_now()
    await session.flush()
    return app, resolved_ids, list(inspection.warnings_json or [])


def inspection_view(inspection: ManualInstallerInspection) -> dict:
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
    html = content.decode("utf-8", errors="replace")
    parser = HTMLParser(html)
    evidence: dict[str, str] = {}
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

    metadata = meta_values(parser)
    if not evidence.get("name"):
        if metadata.get("og:title"):
            evidence["name"] = metadata["og:title"]
            evidence["name_source"] = "open_graph"
        elif metadata.get("twitter:title"):
            evidence["name"] = metadata["twitter:title"]
            evidence["name_source"] = "twitter"
        else:
            title = parser.css_first("title")
            document_title = safe_value(title.text() if title else None, 180)
            if document_title:
                evidence["name"] = document_title
                evidence["name_source"] = "source_page"
    if not evidence.get("publisher") and metadata.get("og:site_name"):
        evidence["publisher"] = metadata["og:site_name"]
        evidence["publisher_source"] = "open_graph"
    if not evidence.get("description"):
        if metadata.get("og:description"):
            evidence["description"] = metadata["og:description"]
            evidence["description_source"] = "open_graph"
        elif metadata.get("twitter:description"):
            evidence["description"] = metadata["twitter:description"]
            evidence["description_source"] = "twitter"
        elif metadata.get("description"):
            evidence["description"] = metadata["description"]
            evidence["description_source"] = "open_graph"
    if not evidence.get("icon"):
        icon = metadata.get("og:image") or metadata.get("twitter:image")
        if icon:
            evidence["icon"] = safe_join(page_url, icon)
            evidence["icon_source"] = (
                "open_graph" if metadata.get("og:image") else "twitter"
            )
        else:
            linked_icon = page_icon_url(parser, page_url)
            if linked_icon:
                evidence["icon"] = linked_icon
                evidence["icon_source"] = "source_page"

    canonical = canonical_url(parser, page_url)
    if canonical:
        evidence["canonical"] = canonical
    return {key: value for key, value in evidence.items() if value}


def first_software_application(parser: HTMLParser) -> dict | None:
    for node in parser.css('script[type="application/ld+json"]')[:20]:
        raw = (node.text() or "")[:100_000]
        try:
            payload = json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            continue
        for item in json_ld_items(payload):
            item_type = item.get("@type")
            types = item_type if isinstance(item_type, list) else [item_type]
            if any(str(value).casefold() == "softwareapplication" for value in types):
                return item
    return None


def json_ld_items(payload: object) -> list[dict]:
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
    return protector.protect(value) if value else None


def reveal_manual_installer_inputs(
    inspection: ManualInstallerInspection,
    protector: UrlProtector,
) -> list[tuple[str | None, str]] | None:
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
    safe_source = source if source in {
        "current",
        "json_ld",
        "open_graph",
        "twitter",
        "canonical",
        "filename",
        "generated_ai",
        "manual",
        "source_page",
    } else "unavailable"
    return {"value": clean_optional(value), "source": safe_source}


def first_non_empty(*candidates: tuple[str | None, str | None]) -> tuple[str | None, str]:
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
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        nested = value.get("name")
        return nested if isinstance(nested, str) else None
    return None


def nested_url(value: object) -> str | None:
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
    try:
        return urljoin(base_url, value.strip())[:2048]
    except ValueError:
        return ""


def safe_value(value: object, max_length: int) -> str:
    if not isinstance(value, str):
        return ""
    return re.sub(r"\s+", " ", value).strip()[:max_length]


def clean_optional(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    cleaned = re.sub(r"\s+", " ", value).strip()
    return cleaned or None


def append_warning(
    existing: list[str] | None,
    *warnings: str,
) -> list[str]:
    return list(dict.fromkeys([*(existing or []), *(warning for warning in warnings if warning)]))
