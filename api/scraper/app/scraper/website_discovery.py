from __future__ import annotations

import asyncio
import hashlib
import hmac
import uuid
from dataclasses import dataclass
from urllib.parse import urlparse, urlunparse

from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.config import Settings
from app.core.json_safe import json_safe
from app.core.logging import get_logger
from app.core.time import utc_after, utc_now
from app.core.url_protector import UrlProtector
from app.db.enums import (
    AppStatus,
    ResolutionStatus,
    ValidationStatus,
)
from app.db.models import (
    DownloadSource,
    SoftwareApp,
    WebsiteAppDiscovery,
    WebsiteAppDiscoveryInstaller,
)
from app.db.session import AsyncSessionLocal
from app.repositories.catalog import CatalogRepository, ResolvedSourceCreate
from app.repositories.pipeline import (
    QUEUE_WEBSITE_APP_DISCOVERY,
    PipelineRepository,
)
from app.schemas.internal import WebsiteAppDiscoveryApplyRequest
from app.scraper.candidates import (
    InstallerCandidate,
    extract_candidates,
    infer_architecture,
    registered_domain,
)
from app.scraper.catalog_fetcher import (
    ValidInstaller,
    dedupe_candidates,
    dedupe_valid_installers,
    infer_validated_operating_system,
    is_download_landing_page,
    prepare_scored_candidates,
    rank_installers,
    resolved_metadata,
    validated_installer_version,
)
from app.scraper.description_enricher import AppDescriptionLLMClient
from app.scraper.github import GitHubReleaseResolver, parse_github_repo
from app.scraper.llm import LLMGenerationError
from app.scraper.manual_installer import (
    append_warning,
    clean_optional,
    description_provenance,
    field_suggestion,
    parse_page_evidence,
    reviewed_field_sources,
    validate_icon,
)
from app.scraper.safe_http import (
    SafeHttpError,
    SafeHttpResponse,
    fetch_public_resource,
    has_sensitive_query,
    validate_public_https_url,
)
from app.scraper.text import normalize_text, slugify
from app.scraper.validator import (
    DownloadValidator,
    ValidationConfidence,
)

logger = get_logger(__name__)

DISCOVERY_VISIBLE_STATUSES = {
    "queued",
    "running",
    "ready",
    "failed",
    "applied",
    "expired",
}
DISCOVERY_REUSABLE_STATUSES = {"queued", "running", "ready"}
MAX_DISCOVERED_CANDIDATES = 32
MAX_VALID_INSTALLERS = 8
MAX_LANDING_PAGES = 4
INSTALLER_PLATFORMS = ("windows", "macos", "linux")
INSTALLER_URL_COLUMNS = {
    "windows": "windows_installer_url_encrypted",
    "macos": "macos_installer_url_encrypted",
    "linux": "linux_installer_url_encrypted",
}
QUERY_FALLBACK_ERROR_CODES = {"http_401", "http_403"}


class WebsiteDiscoveryError(Exception):
    def __init__(self, code: str, status_code: int = 422) -> None:
        super().__init__(code)
        self.code = code
        self.status_code = status_code


class WebsiteDiscoveryTransientError(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


async def fetch_official_page(
    official_url: str,
    settings: Settings,
) -> tuple[SafeHttpResponse, str | None]:
    fetch_options = {
        "timeout": settings.request_timeout_seconds,
        "max_redirects": settings.max_redirects,
        "max_bytes": settings.manual_page_max_bytes,
        "accept": "text/html,application/xhtml+xml;q=0.9",
    }
    try:
        return (
            await fetch_public_resource(official_url, **fetch_options),
            None,
        )
    except SafeHttpError as exc:
        parsed = urlparse(official_url)
        if (
            exc.code not in QUERY_FALLBACK_ERROR_CODES
            or not parsed.query
            or has_sensitive_query(official_url)
        ):
            raise
        queryless_url = urlunparse(parsed._replace(query=""))
        return (
            await fetch_public_resource(queryless_url, **fetch_options),
            f"official_url:query_removed_after_{exc.code}",
        )


async def validate_installer_urls(
    installer_urls: dict[str, str | None],
) -> dict[str, str]:
    validated: dict[str, str] = {}
    for operating_system in INSTALLER_PLATFORMS:
        value = clean_optional(installer_urls.get(operating_system))
        if value:
            validated[operating_system] = await validate_public_https_url(value)
    return validated


def protect_optional_url(
    protector: UrlProtector,
    value: str | None,
) -> str | None:
    return protector.protect(value) if value else None


def expected_operating_system(candidate: InstallerCandidate) -> str | None:
    prefix = "admin_website_discovery_input:"
    if not candidate.source.startswith(prefix):
        return None
    operating_system = candidate.source.removeprefix(prefix)
    return operating_system if operating_system in INSTALLER_PLATFORMS else None


@dataclass(frozen=True)
class DiscoveredInstaller:
    url: str
    final_domain: str | None
    filename: str | None
    extension: str | None
    content_type: str | None
    size_bytes: int | None
    version: str | None
    operating_system: str
    architecture: str
    score: int


class WebsiteAppDiscoveryRepository:
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
        official_url: str,
        installer_urls: dict[str, str | None] | None = None,
    ) -> tuple[WebsiteAppDiscovery, bool]:
        official_url = await validate_public_https_url(official_url)
        if has_sensitive_query(official_url):
            raise WebsiteDiscoveryError(
                "official_url_query_credentials_forbidden",
                422,
            )
        safe_installer_urls = await validate_installer_urls(installer_urls or {})
        input_hash = website_discovery_input_hash(
            official_url,
            self.settings.url_protection_secret,
            safe_installer_urls,
        )
        await self._expire_stale(input_hash)
        existing = await self.session.scalar(
            select(WebsiteAppDiscovery)
            .options(selectinload(WebsiteAppDiscovery.installers))
            .where(WebsiteAppDiscovery.input_hash == input_hash)
            .where(WebsiteAppDiscovery.status.in_(DISCOVERY_REUSABLE_STATUSES))
            .order_by(WebsiteAppDiscovery.created_at.desc())
            .limit(1)
            .with_for_update()
        )
        if existing is not None:
            return existing, False

        discovery = WebsiteAppDiscovery(
            status="queued",
            phase="queued",
            input_hash=input_hash,
            official_url_encrypted=self.protector.protect(official_url),
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
            warnings_json=[],
            expires_at=utc_after(hours=self.settings.manual_inspection_ttl_hours),
            installers=[],
        )
        self.session.add(discovery)
        await self.session.flush()
        pipeline = PipelineRepository(self.session)
        await pipeline.enqueue(
            QUEUE_WEBSITE_APP_DISCOVERY,
            str(discovery.id),
            None,
            {"discovery_id": str(discovery.id)},
            None,
            priority=210,
        )
        return discovery, True

    async def get(
        self,
        discovery_id: uuid.UUID,
        *,
        for_update: bool = False,
    ) -> WebsiteAppDiscovery | None:
        statement = (
            select(WebsiteAppDiscovery)
            .options(selectinload(WebsiteAppDiscovery.installers))
            .where(WebsiteAppDiscovery.id == discovery_id)
        )
        if for_update:
            statement = statement.with_for_update()
        discovery = await self.session.scalar(statement)
        if (
            discovery is not None
            and discovery.status not in {"applied", "expired"}
            and discovery.expires_at <= utc_now()
        ):
            discovery.status = "expired"
            discovery.phase = "expired"
            discovery.error_code = "website_discovery_expired"
            discovery.updated_at = utc_now()
        return discovery

    async def _expire_stale(self, input_hash: str) -> None:
        discoveries = list(
            await self.session.scalars(
                select(WebsiteAppDiscovery)
                .where(WebsiteAppDiscovery.input_hash == input_hash)
                .where(WebsiteAppDiscovery.status.in_(DISCOVERY_REUSABLE_STATUSES))
                .where(WebsiteAppDiscovery.expires_at <= utc_now())
                .with_for_update()
            )
        )
        for discovery in discoveries:
            discovery.status = "expired"
            discovery.phase = "expired"
            discovery.error_code = "website_discovery_expired"
            discovery.updated_at = utc_now()


class WebsiteAppDiscoverer:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.validator = DownloadValidator(settings)
        self.github = GitHubReleaseResolver(settings)
        self.llm = AppDescriptionLLMClient(settings)

    async def inspect(
        self,
        official_url: str,
        installer_urls: dict[str, str] | None = None,
        *,
        set_phase,
    ) -> tuple[dict, list[DiscoveredInstaller], list[str]]:
        await set_phase("validating_website")
        warnings: list[str] = []
        try:
            page, page_warning = await fetch_official_page(
                official_url,
                self.settings,
            )
            if page_warning:
                warnings.append(page_warning)
        except SafeHttpError as exc:
            if exc.transient:
                raise WebsiteDiscoveryTransientError(exc.code) from exc
            raise WebsiteDiscoveryError(exc.code) from exc
        if page.content_type and page.content_type not in {
            "text/html",
            "application/xhtml+xml",
        }:
            raise WebsiteDiscoveryError("official_website_not_html")

        await set_phase("reading_website_metadata")
        evidence = parse_page_evidence(page.content, page.final_url)
        name = clean_optional(evidence.get("name")) or domain_name(page.final_url)
        name_source = evidence.get("name_source") or "source_page"
        publisher = clean_optional(evidence.get("publisher"))
        publisher_source = evidence.get("publisher_source") or (
            "json_ld" if publisher else "unavailable"
        )
        description = clean_optional(evidence.get("description"))
        description_source = evidence.get("description_source") or "unavailable"
        icon_url: str | None = None
        if evidence.get("icon"):
            icon_url, icon_warning = await validate_icon(
                evidence["icon"],
                self.settings,
            )
            if icon_warning:
                warnings.append(icon_warning)

        await set_phase("searching_installers")
        candidates = await self._collect_candidates(
            page.content,
            page.final_url,
            clean_optional(evidence.get("version")),
            installer_urls or {},
        )
        installers, validation_warning = await self._validate_candidates(
            candidates,
            name,
            publisher,
            clean_optional(evidence.get("version")),
        )
        if validation_warning:
            warnings.append(validation_warning)
        if not installers:
            warnings.append("installers:not_found")

        installer_version = best_installer_version(installers)
        latest_version = clean_optional(evidence.get("version")) or installer_version
        version_source = (
            "json_ld"
            if clean_optional(evidence.get("version"))
            else ("filename" if installer_version else "unavailable")
        )

        long_description: str | None = None
        long_description_source = "unavailable"
        ai_state: dict[str, str | None] = {
            "status": "unavailable",
            "provider": None,
            "model": None,
        }
        await set_phase("generating_description")
        if self.llm.has_provider():
            try:
                generated = await self.llm.generate(
                    {
                        "name": name,
                        "publisher": publisher,
                        "short_description": description,
                        "latest_version": latest_version,
                        "installer_count": len(installers),
                        "installer_formats": sorted(
                            {
                                installer.extension
                                for installer in installers
                                if installer.extension
                            }
                        ),
                        "operating_systems": sorted(
                            {installer.operating_system for installer in installers}
                        ),
                        "source_page_metadata": {
                            key: value
                            for key, value in evidence.items()
                            if key
                            in {
                                "name",
                                "publisher",
                                "version",
                                "description",
                            }
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
        else:
            warnings.append("ai:provider_not_configured")

        result = {
            "suggestions": {
                "name": field_suggestion(name, name_source),
                "publisher": field_suggestion(publisher, publisher_source),
                "officialUrl": field_suggestion(page.final_url, "source_page"),
                "latestVersion": field_suggestion(latest_version, version_source),
                "description": field_suggestion(description, description_source),
                "longDescription": field_suggestion(
                    long_description,
                    long_description_source,
                ),
                "iconUrl": field_suggestion(
                    icon_url,
                    evidence.get("icon_source") if icon_url else "unavailable",
                ),
            },
            "ai": ai_state,
            "installerCount": len(installers),
        }
        return json_safe(result), installers, warnings

    async def _collect_candidates(
        self,
        page_content: bytes,
        official_url: str,
        latest_version: str | None,
        installer_urls: dict[str, str],
    ) -> list[InstallerCandidate]:
        html = page_content.decode("utf-8", errors="replace")
        candidates = [
            InstallerCandidate(
                url=url,
                source=f"admin_website_discovery_input:{operating_system}",
                label=f"{operating_system} installer supplied by administrator",
                context=f"{operating_system} installer",
                referer=official_url,
                asset_kind="installer",
            )
            for operating_system, url in installer_urls.items()
            if operating_system in INSTALLER_PLATFORMS and url
        ]
        candidates.extend(extract_candidates(html, official_url))
        candidates.extend(
            await self._collect_landing_page_candidates(
                official_url,
                candidates,
            )
        )

        github_urls = [
            candidate.url
            for candidate in candidates
            if parse_github_repo(candidate.url) is not None
        ]
        if parse_github_repo(official_url) is not None:
            github_urls.insert(0, official_url)
        for github_url in list(dict.fromkeys(github_urls))[:2]:
            try:
                candidates.extend(
                    await self.github.collect(github_url, latest_version)
                )
            except Exception:
                continue
        return dedupe_candidates(candidates)

    async def _collect_landing_page_candidates(
        self,
        official_url: str,
        candidates: list[InstallerCandidate],
    ) -> list[InstallerCandidate]:
        official_domain = registered_domain(official_url)
        landing_pages = [
            candidate
            for candidate in candidates
            if is_download_landing_page(
                candidate,
                official_url,
                official_domain,
            )
        ][:MAX_LANDING_PAGES]

        async def collect(landing: InstallerCandidate) -> list[InstallerCandidate]:
            try:
                response = await fetch_public_resource(
                    landing.url,
                    timeout=self.settings.request_timeout_seconds,
                    max_redirects=self.settings.max_redirects,
                    max_bytes=self.settings.manual_page_max_bytes,
                    accept="text/html,application/xhtml+xml;q=0.9",
                )
            except SafeHttpError:
                return []
            if response.content_type and response.content_type not in {
                "text/html",
                "application/xhtml+xml",
            }:
                return []
            html = response.content.decode("utf-8", errors="replace")
            return [
                InstallerCandidate(
                    url=candidate.url,
                    source="official_download_page",
                    label=candidate.label,
                    context=candidate.context,
                    referer=response.final_url,
                    asset_kind=candidate.asset_kind,
                )
                for candidate in extract_candidates(html, response.final_url)
            ]

        if not landing_pages:
            return []
        batches = await asyncio.gather(
            *(collect(landing) for landing in landing_pages),
            return_exceptions=True,
        )
        nested: list[InstallerCandidate] = []
        for batch in batches:
            if isinstance(batch, list):
                nested.extend(batch)
        return nested

    async def _validate_candidates(
        self,
        candidates: list[InstallerCandidate],
        name: str,
        publisher: str | None,
        latest_version: str | None,
    ) -> tuple[list[DiscoveredInstaller], str | None]:
        scored = prepare_scored_candidates(
            candidates,
            name,
            None,
            publisher,
            latest_version,
        )
        scored.sort(
            key=lambda candidate: (
                expected_operating_system(candidate) is not None,
                candidate.score,
            ),
            reverse=True,
        )
        scored = scored[:MAX_DISCOVERED_CANDIDATES]
        if not scored:
            return [], None

        async def validate(candidate: InstallerCandidate):
            try:
                async with asyncio.timeout(
                    min(10.0, self.settings.request_timeout_seconds + 2.0)
                ):
                    result = await self.validator.validate(
                        candidate,
                        require_signature=True,
                    )
                return candidate, result
            except Exception:
                return candidate, None

        valid: list[ValidInstaller] = []
        partial_failure = False
        for offset in range(0, len(scored), 4):
            batch = await asyncio.gather(
                *(validate(candidate) for candidate in scored[offset : offset + 4])
            )
            for candidate, result in batch:
                if (
                    result is None
                    or not result.ok
                    or result.confidence != ValidationConfidence.VALIDATED
                ):
                    partial_failure = True
                    continue
                inferred_operating_system = infer_validated_operating_system(
                    candidate,
                    result,
                )
                supplied_operating_system = expected_operating_system(candidate)
                if (
                    inferred_operating_system
                    and supplied_operating_system
                    and inferred_operating_system != supplied_operating_system
                ):
                    partial_failure = True
                    continue
                operating_system = (
                    inferred_operating_system or supplied_operating_system
                )
                if not operating_system:
                    partial_failure = True
                    continue
                valid.append(
                    ValidInstaller(
                        candidate=candidate,
                        result=result,
                        status=ResolutionStatus.DIRECT,
                        operating_system=operating_system,
                        architecture=infer_architecture(candidate),
                        version=validated_installer_version(candidate, result)
                        or latest_version,
                    )
                )
            if len(valid) >= MAX_VALID_INSTALLERS:
                break

        discovered = [
            DiscoveredInstaller(
                url=installer.result.final_url or installer.candidate.url,
                final_domain=installer.result.final_domain,
                filename=installer.result.filename,
                extension=installer.result.extension,
                content_type=installer.result.content_type,
                size_bytes=installer.result.size_bytes,
                version=installer.version,
                operating_system=installer.operating_system,
                architecture=installer.architecture,
                score=installer.candidate.score,
            )
            for installer in dedupe_valid_installers(valid)[:MAX_VALID_INSTALLERS]
        ]
        return (
            discovered,
            "installers:some_candidates_rejected" if partial_failure else None,
        )


class WebsiteAppDiscoveryWorker:
    def __init__(
        self,
        settings: Settings,
        worker_id: str = "website-discovery-1",
    ) -> None:
        self.settings = settings
        self.worker_id = worker_id

    async def process_one(self) -> bool:
        async with AsyncSessionLocal() as session:
            pipeline = PipelineRepository(session)
            item = await pipeline.claim_next(
                QUEUE_WEBSITE_APP_DISCOVERY,
                self.worker_id,
                lease_seconds=max(
                    90,
                    int(self.settings.request_timeout_seconds * 12),
                ),
            )
            if item is None:
                await session.rollback()
                return False
            await session.commit()

            try:
                discovery_id = uuid.UUID(
                    str((item.payload_json or {}).get("discovery_id"))
                )
            except (ValueError, TypeError, AttributeError):
                await pipeline.fail(item, "invalid_website_discovery_id")
                await session.commit()
                return True

            discovery = await session.get(WebsiteAppDiscovery, discovery_id)
            if discovery is None:
                await pipeline.discard(item, "website_discovery_not_found")
                await session.commit()
                return True
            if discovery.status in {"applied", "expired"}:
                await pipeline.discard(item, f"website_discovery_{discovery.status}")
                await session.commit()
                return True
            if discovery.expires_at <= utc_now():
                discovery.status = "expired"
                discovery.phase = "expired"
                discovery.error_code = "website_discovery_expired"
                await pipeline.discard(item, discovery.error_code)
                await session.commit()
                return True

            protector = UrlProtector(self.settings.url_protection_secret)
            official_url = protector.reveal(discovery.official_url_encrypted)
            if not official_url:
                discovery.status = "failed"
                discovery.phase = "failed"
                discovery.error_code = "website_discovery_url_unreadable"
                await pipeline.fail(item, discovery.error_code)
                await session.commit()
                return True
            installer_urls: dict[str, str] = {}
            for operating_system, column_name in INSTALLER_URL_COLUMNS.items():
                protected_url = getattr(discovery, column_name)
                if not protected_url:
                    continue
                installer_url = protector.reveal(protected_url)
                if not installer_url:
                    discovery.status = "failed"
                    discovery.phase = "failed"
                    discovery.error_code = "website_discovery_url_unreadable"
                    await pipeline.fail(item, discovery.error_code)
                    await session.commit()
                    return True
                installer_urls[operating_system] = installer_url

            discovery.status = "running"
            discovery.phase = "starting"
            discovery.error_code = None
            discovery.updated_at = utc_now()
            await session.commit()

            async def set_phase(phase: str) -> None:
                discovery.phase = phase
                discovery.updated_at = utc_now()
                await session.commit()

            try:
                result, installers, warnings = await WebsiteAppDiscoverer(
                    self.settings
                ).inspect(
                    official_url,
                    installer_urls,
                    set_phase=set_phase,
                )
            except WebsiteDiscoveryTransientError as exc:
                if item.attempts < self.settings.manual_inspection_max_attempts:
                    discovery.status = "queued"
                    discovery.phase = "retry_wait"
                    discovery.error_code = None
                    discovery.warnings_json = append_warning(
                        discovery.warnings_json,
                        f"retry:{exc.code}",
                    )
                    await pipeline.requeue(
                        item,
                        exc.code,
                        delay_seconds=min(60, 2 ** max(1, item.attempts)),
                    )
                else:
                    discovery.status = "failed"
                    discovery.phase = "failed"
                    discovery.error_code = exc.code
                    await pipeline.fail(item, exc.code)
                discovery.updated_at = utc_now()
                await session.commit()
                return True
            except WebsiteDiscoveryError as exc:
                discovery.status = "failed"
                discovery.phase = "failed"
                discovery.error_code = exc.code
                discovery.updated_at = utc_now()
                await pipeline.fail(item, exc.code)
                await session.commit()
                return True
            except Exception as exc:  # noqa: BLE001 - persist a safe failure code
                logger.error(
                    "website_app_discovery_failed",
                    discovery_id=str(discovery.id),
                    error=exc.__class__.__name__,
                )
                discovery.status = "failed"
                discovery.phase = "failed"
                discovery.error_code = "website_discovery_internal_error"
                discovery.updated_at = utc_now()
                await pipeline.fail(item, discovery.error_code)
                await session.commit()
                return True

            await session.execute(
                delete(WebsiteAppDiscoveryInstaller).where(
                    WebsiteAppDiscoveryInstaller.discovery_id == discovery.id
                )
            )
            for installer in installers:
                session.add(
                    WebsiteAppDiscoveryInstaller(
                        discovery_id=discovery.id,
                        installer_url_encrypted=protector.protect(installer.url),
                        final_domain=installer.final_domain,
                        filename=installer.filename,
                        extension=installer.extension,
                        content_type=installer.content_type,
                        size_bytes=installer.size_bytes,
                        version=installer.version,
                        operating_system=installer.operating_system,
                        architecture=installer.architecture,
                        score=installer.score,
                    )
                )
            discovery.result_json = result
            discovery.warnings_json = append_warning(
                discovery.warnings_json,
                *warnings,
            )
            discovery.status = "ready"
            discovery.phase = "ready"
            discovery.error_code = None
            discovery.updated_at = utc_now()
            await pipeline.complete(item)
            await session.commit()
            return True


async def apply_website_app_discovery(
    session: AsyncSession,
    settings: Settings,
    discovery_id: uuid.UUID,
    request: WebsiteAppDiscoveryApplyRequest,
) -> tuple[SoftwareApp, int, list[str]]:
    protector = UrlProtector(settings.url_protection_secret)
    repository = WebsiteAppDiscoveryRepository(session, protector, settings)
    discovery = await repository.get(discovery_id, for_update=True)
    if discovery is None:
        raise WebsiteDiscoveryError("website_discovery_not_found", 404)
    if discovery.status == "applied" and discovery.applied_app_id is not None:
        app = await session.get(SoftwareApp, discovery.applied_app_id)
        if app is None:
            raise WebsiteDiscoveryError("website_discovery_app_not_found", 409)
        installer_count = int(
            (discovery.result_json or {}).get(
                "appliedInstallerCount",
                len(discovery.installers),
            )
        )
        return app, installer_count, list(discovery.warnings_json or [])
    if discovery.status == "expired":
        raise WebsiteDiscoveryError("website_discovery_expired", 409)
    if discovery.status != "ready" or not discovery.result_json:
        raise WebsiteDiscoveryError("website_discovery_not_ready", 409)

    name = clean_optional(request.name)
    if not name:
        raise WebsiteDiscoveryError("name_required")
    official_url = await validate_public_https_url(request.official_url)
    if has_sensitive_query(official_url):
        raise WebsiteDiscoveryError(
            "official_url_query_credentials_forbidden",
        )
    expected_official_url = clean_optional(
        (discovery.result_json.get("suggestions") or {})
        .get("officialUrl", {})
        .get("value")
    )
    if (
        not expected_official_url
        or registered_domain(official_url)
        != registered_domain(expected_official_url)
    ):
        raise WebsiteDiscoveryError(
            "official_url_changed_rediscovery_required",
            409,
        )

    duplicate = await session.scalar(
        select(SoftwareApp.id)
        .where(SoftwareApp.app_status == AppStatus.ACTIVE.value)
        .where(func.lower(SoftwareApp.official_url) == official_url.casefold())
        .limit(1)
    )
    if duplicate is not None:
        raise WebsiteDiscoveryError("official_url_already_registered", 409)

    icon_url = clean_optional(request.icon_url)
    if icon_url:
        icon_url, icon_warning = await validate_icon(icon_url, settings)
        if icon_warning:
            discovery.warnings_json = append_warning(
                discovery.warnings_json,
                icon_warning,
            )

    slug_base = slugify(name)[:160]
    slug = f"{slug_base}-{str(discovery.id).split('-', 1)[0]}"
    long_description = clean_optional(request.long_description)
    suggestions = discovery.result_json.get("suggestions") or {}
    generated_long_description = (
        suggestions.get("longDescription", {}).get("value")
        if suggestions.get("longDescription", {}).get("source") == "generated_ai"
        else None
    )
    ai_state = discovery.result_json.get("ai") or {}
    (
        long_description_status,
        long_description_source,
        long_description_model,
    ) = description_provenance(
        long_description,
        generated_long_description,
        ai_state,
    )
    app = SoftwareApp(
        winstall_id=f"manual.{slug}",
        slug=slug,
        name=name,
        normalized_name=normalize_text(name),
        publisher=clean_optional(request.publisher),
        official_url=official_url,
        latest_version=clean_optional(request.latest_version),
        description=clean_optional(request.description),
        long_description=long_description,
        long_description_language="es" if long_description else None,
        long_description_status=long_description_status,
        long_description_source=long_description_source,
        long_description_model=long_description_model,
        long_description_generated_at=utc_now() if long_description else None,
        icon_url=icon_url,
        app_status=AppStatus.ACTIVE.value,
        metadata_json=json_safe(
            {
                "source": "admin_website_discovery",
                "discovery_id": str(discovery.id),
                "field_sources": reviewed_field_sources(
                    suggestions,
                    {
                        "name": name,
                        "publisher": request.publisher,
                        "officialUrl": official_url,
                        "latestVersion": request.latest_version,
                        "description": request.description,
                        "longDescription": long_description,
                        "iconUrl": icon_url,
                    },
                ),
            }
        ),
        operating_systems=[],
        version=0,
    )
    session.add(app)
    await session.flush()

    valid_installers, apply_warnings = await revalidate_discovered_installers(
        discovery.installers,
        official_url,
        protector,
        settings,
    )
    discovery.warnings_json = append_warning(
        discovery.warnings_json,
        *apply_warnings,
    )
    catalog = CatalogRepository(session, protector)
    source_ids: set[uuid.UUID] = set()
    for installer, release_rank, is_latest in rank_installers(valid_installers):
        source = await catalog.source_for_platform(
            app.id,
            installer.operating_system,
            installer.architecture,
        )
        if source is None:
            source = DownloadSource(
                software_app_id=app.id,
                operating_system=installer.operating_system,
                architecture=installer.architecture,
                initial_url=official_url,
                resolver_type="generic_http",
                resolver_config={
                    "source": "admin_website_discovery",
                    "discovery_id": str(discovery.id),
                },
                resolution_status=ResolutionStatus.MISSING.value,
                validation_status=ValidationStatus.UNCHECKED.value,
            )
            session.add(source)
            await session.flush()
        source_ids.add(source.id)
        metadata = resolved_metadata(installer, is_latest)
        metadata["discovery_id"] = str(discovery.id)
        await catalog.save_resolved_source(
            ResolvedSourceCreate(
                source_id=source.id,
                url=installer.result.final_url or installer.candidate.url,
                final_domain=installer.result.final_domain
                or registered_domain(
                    installer.result.final_url or installer.candidate.url
                )
                or "unknown",
                filename=installer.result.filename,
                extension=installer.result.extension,
                content_type=installer.result.content_type,
                size_bytes=installer.result.size_bytes,
                version=installer.version,
                score=installer.candidate.score,
                status=ResolutionStatus.DIRECT,
                validation_status=ValidationStatus.VALID,
                release_rank=release_rank,
                is_latest=is_latest,
                version_status="latest" if is_latest else "previous",
                metadata=metadata,
            )
        )

    if source_ids:
        await catalog.refresh_source_statuses(source_ids)
        await catalog.refresh_operating_systems(app.id)
    await session.flush()
    await session.refresh(
        app,
        attribute_names=["version", "app_status", "catalog_status"],
    )
    discovery.status = "applied"
    discovery.phase = "applied"
    discovery.result_json = {
        **(discovery.result_json or {}),
        "appliedInstallerCount": len(valid_installers),
    }
    discovery.applied_at = utc_now()
    discovery.applied_app_id = app.id
    discovery.updated_at = utc_now()
    return app, len(valid_installers), list(discovery.warnings_json or [])


async def revalidate_discovered_installers(
    rows: list[WebsiteAppDiscoveryInstaller],
    official_url: str,
    protector: UrlProtector,
    settings: Settings,
) -> tuple[list[ValidInstaller], list[str]]:
    validator = DownloadValidator(settings)

    async def validate(row: WebsiteAppDiscoveryInstaller):
        url = protector.reveal(row.installer_url_encrypted)
        if not url:
            return None
        candidate = InstallerCandidate(
            url=url,
            source="admin_website_discovery",
            label=row.filename,
            context=row.filename,
            referer=official_url,
            asset_kind="installer",
            score=row.score,
        )
        try:
            async with asyncio.timeout(
                min(10.0, settings.request_timeout_seconds + 2.0)
            ):
                result = await validator.validate(
                    candidate,
                    require_signature=True,
                )
        except Exception:
            return None
        if (
            not result.ok
            or result.confidence != ValidationConfidence.VALIDATED
        ):
            return None
        inferred_operating_system = infer_validated_operating_system(
            candidate,
            result,
        )
        if (
            inferred_operating_system
            and inferred_operating_system != row.operating_system
        ):
            return None
        operating_system = inferred_operating_system or row.operating_system
        return ValidInstaller(
            candidate=candidate,
            result=result,
            status=ResolutionStatus.DIRECT,
            operating_system=operating_system,
            architecture=infer_architecture(candidate),
            version=validated_installer_version(candidate, result) or row.version,
        )

    results = await asyncio.gather(*(validate(row) for row in rows))
    valid = dedupe_valid_installers(
        [result for result in results if result is not None]
    )
    warnings = (
        ["installers:changed_since_preview"]
        if len(valid) != len(rows)
        else []
    )
    if rows and not valid:
        warnings.append("installers:none_valid_on_apply")
    return valid, warnings


def website_discovery_view(discovery: WebsiteAppDiscovery) -> dict:
    result = discovery.result_json or {}
    return {
        "id": str(discovery.id),
        "status": discovery.status,
        "phase": discovery.phase,
        "warnings": list(discovery.warnings_json or []),
        "providedInstallerPlatforms": [
            operating_system
            for operating_system, column_name in INSTALLER_URL_COLUMNS.items()
            if getattr(discovery, column_name)
        ],
        "suggestions": result.get("suggestions"),
        "installers": [
            {
                "id": str(installer.id),
                "finalDomain": installer.final_domain,
                "filename": installer.filename,
                "extension": installer.extension,
                "contentType": installer.content_type,
                "sizeBytes": installer.size_bytes,
                "version": installer.version,
                "operatingSystem": installer.operating_system,
                "architecture": installer.architecture,
            }
            for installer in discovery.installers
        ],
        "ai": result.get("ai"),
        "errorCode": discovery.error_code,
        "appliedAppId": (
            str(discovery.applied_app_id)
            if discovery.applied_app_id
            else None
        ),
        "createdAt": discovery.created_at,
        "updatedAt": discovery.updated_at,
        "expiresAt": discovery.expires_at,
    }


def website_discovery_input_hash(
    official_url: str,
    secret: str,
    installer_urls: dict[str, str] | None = None,
) -> str:
    material = "\n".join(
        [
            official_url,
            *[
                f"{operating_system}={(installer_urls or {}).get(operating_system, '')}"
                for operating_system in INSTALLER_PLATFORMS
            ],
        ]
    )
    return hmac.new(
        secret.encode("utf-8"),
        material.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()


def domain_name(url: str) -> str:
    hostname = (urlparse(url).hostname or "application").removeprefix("www.")
    label = hostname.split(".", 1)[0].replace("-", " ").strip()
    return (label.title() or "Aplicación")[:180]


def best_installer_version(installers: list[DiscoveredInstaller]) -> str | None:
    versions = [
        clean_optional(installer.version)
        for installer in installers
        if clean_optional(installer.version)
    ]
    if not versions:
        return None

    def key(value: str) -> tuple[int, ...]:
        parts = []
        for token in value.removeprefix("v").split("."):
            if not token.isdigit():
                return ()
            parts.append(int(token))
        return tuple(parts)

    deterministic = [(key(version), version) for version in versions]
    deterministic = [item for item in deterministic if item[0]]
    return max(deterministic)[1] if deterministic else versions[0]
