import uuid
from dataclasses import dataclass
from datetime import datetime

import tldextract
from sqlalchemy import Select, case, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.json_safe import json_safe
from app.core.time import utc_after, utc_now
from app.core.url_protector import UrlProtector
from app.db.enums import AppStatus, LongDescriptionStatus, ResolutionStatus, ValidationStatus
from app.db.models import (
    DownloadSource,
    ResolvedSource,
    ScrapeRun,
    SoftwareApp,
    SoftwareAppTag,
    SourceAllowedDomain,
)
from app.scraper.text import normalize_text, slugify
from app.scraper.winstall import WinstallApp

AVAILABLE_RESOLUTION_STATUSES = {
    ResolutionStatus.DIRECT.value,
    ResolutionStatus.FALLBACK.value,
}


@dataclass(frozen=True)
class ResolvedSourceCreate:
    source_id: uuid.UUID
    url: str
    final_domain: str
    filename: str | None
    extension: str | None
    content_type: str | None
    size_bytes: int | None
    version: str | None
    score: int
    status: ResolutionStatus
    validation_status: ValidationStatus
    metadata: dict | None = None


class CatalogRepository:
    def __init__(self, session: AsyncSession, url_protector: UrlProtector) -> None:
        self.session = session
        self.url_protector = url_protector

    async def should_scrape_winstall_package(self, package_id: str) -> bool:
        app = await self.session.scalar(
            select(SoftwareApp)
            .options(
                selectinload(SoftwareApp.sources).selectinload(DownloadSource.resolved_sources),
            )
            .where(SoftwareApp.winstall_id == package_id)
        )
        if app is None:
            return True
        return not has_current_available_installer(app)

    async def upsert_winstall_app(self, app: WinstallApp) -> SoftwareApp:
        existing = await self.session.scalar(
            select(SoftwareApp)
            .options(
                selectinload(SoftwareApp.tags),
                selectinload(SoftwareApp.sources).selectinload(DownloadSource.allowed_domains),
            )
            .where(SoftwareApp.winstall_id == app.package_id)
        )
        slug = slugify(app.package_id)
        icon_url = app.icon_url
        if app.icon and not app.icon.startswith("http"):
            icon_key = app.icon.removesuffix(".png")
            icon_url = f"https://api.winstall.app/icons/next/{icon_key}.webp"

        if existing is None:
            existing = SoftwareApp(
                winstall_id=app.package_id,
                slug=slug,
                name=app.name or app.package_id,
                normalized_name=normalize_text(app.name or app.package_id),
                description=app.description,
                long_description_status=LongDescriptionStatus.PENDING.value,
                publisher=app.publisher,
                icon_url=icon_url,
                official_url=app.homepage,
                latest_version=app.latest_version,
                app_status=AppStatus.ACTIVE.value,
                metadata_json=json_safe(app.raw),
            )
            self.session.add(existing)
            await self.session.flush()
        else:
            existing.name = app.name or existing.name
            existing.normalized_name = normalize_text(existing.name)
            existing.description = app.description
            existing.publisher = app.publisher
            existing.icon_url = icon_url if has_icon_url(icon_url) else existing.icon_url
            existing.official_url = app.homepage
            existing.latest_version = app.latest_version
            existing.metadata_json = json_safe(app.raw)
            existing.updated_at = utc_now()
            existing.version += 1

        await self._sync_tags(existing, app.tags)
        await self._ensure_default_source(existing, app)
        return existing

    async def _sync_tags(self, software_app: SoftwareApp, raw_tags: list[str]) -> None:
        normalized_to_tag: dict[str, str] = {}
        for raw_tag in raw_tags:
            normalized = normalize_text(raw_tag).strip()
            tag = raw_tag.strip()
            if normalized and tag:
                normalized_to_tag[normalized[:120]] = tag[:120]

        result = await self.session.scalars(
            select(SoftwareAppTag)
            .where(SoftwareAppTag.software_app_id == software_app.id)
            .where(SoftwareAppTag.source == "winstall")
        )
        existing_tags = {tag.normalized_tag: tag for tag in result}
        changed = False
        for normalized, tag in normalized_to_tag.items():
            existing = existing_tags.get(normalized)
            if existing:
                if existing.tag != tag:
                    existing.tag = tag
                    changed = True
                continue
            self.session.add(
                SoftwareAppTag(
                    software_app_id=software_app.id,
                    tag=tag,
                    normalized_tag=normalized,
                    source="winstall",
                )
            )
            changed = True

        for normalized, tag in existing_tags.items():
            if normalized not in normalized_to_tag:
                await self.session.delete(tag)
                changed = True

        if changed:
            software_app.updated_at = utc_now()
            software_app.version += 1

    async def update_icon_url(self, software_app_id: uuid.UUID, icon_url: str) -> None:
        software_app = await self.session.get(SoftwareApp, software_app_id)
        if not software_app or has_icon_url(software_app.icon_url) or not has_icon_url(icon_url):
            return
        software_app.icon_url = icon_url
        software_app.updated_at = utc_now()
        software_app.version += 1

    async def _ensure_default_source(self, software_app: SoftwareApp, app: WinstallApp) -> None:
        source = await self.session.scalar(
            select(DownloadSource)
            .where(DownloadSource.software_app_id == software_app.id)
            .where(DownloadSource.operating_system == "windows")
            .where(DownloadSource.architecture == "x86_64")
        )
        is_new_source = source is None
        if source is None:
            source = DownloadSource(
                software_app_id=software_app.id,
                operating_system="windows",
                architecture="x86_64",
                initial_url=app.homepage,
                resolver_type="generic_http",
                resolver_config=json_safe({"winstall_id": app.package_id}),
                resolution_status=ResolutionStatus.MISSING.value,
                validation_status=ValidationStatus.UNCHECKED.value,
            )
            self.session.add(source)
            await self.session.flush()
        else:
            source.initial_url = app.homepage or source.initial_url
            source.resolver_config = json_safe({"winstall_id": app.package_id})
            source.updated_at = utc_now()

        domains = allowed_domains_for(app.homepage, app.installer_urls)
        if is_new_source:
            existing_domains = set()
        else:
            result = await self.session.scalars(
                select(SourceAllowedDomain.domain).where(SourceAllowedDomain.source_id == source.id)
            )
            existing_domains = set(result)
        for domain in domains - existing_domains:
            self.session.add(
                SourceAllowedDomain(source_id=source.id, domain=domain, include_subdomains=True)
            )

    async def default_source_for_app(self, software_app_id: uuid.UUID) -> DownloadSource | None:
        return await self.session.scalar(
            select(DownloadSource)
            .options(
                selectinload(DownloadSource.allowed_domains),
                selectinload(DownloadSource.resolved_sources),
            )
            .where(DownloadSource.software_app_id == software_app_id)
            .where(DownloadSource.operating_system == "windows")
            .where(DownloadSource.architecture == "x86_64")
            .limit(1)
        )

    async def save_resolved_source(self, item: ResolvedSourceCreate) -> ResolvedSource:
        encrypted_url = self.url_protector.protect(item.url)
        resolved = ResolvedSource(
            download_source_id=item.source_id,
            resolved_url_encrypted=encrypted_url,
            final_domain=item.final_domain,
            filename=item.filename,
            extension=item.extension,
            content_type=item.content_type,
            size_bytes=item.size_bytes,
            version=item.version,
            score=item.score,
            status=item.status.value,
            validation_status=item.validation_status.value,
            checked_at=utc_now(),
            expires_at=utc_after(hours=24),
            metadata_json=json_safe(item.metadata),
        )
        self.session.add(resolved)

        source = await self.session.get(DownloadSource, item.source_id)
        if source:
            source.resolution_status = item.status.value
            source.validation_status = item.validation_status.value
            source.updated_at = utc_now()
            source.version += 1
        return resolved

    async def apps_for_description_enrichment(self) -> list[SoftwareApp]:
        result = await self.session.scalars(
            select(SoftwareApp)
            .where(SoftwareApp.app_status == AppStatus.ACTIVE.value)
            .order_by(
                case(
                    (
                        SoftwareApp.long_description_status
                        == LongDescriptionStatus.PENDING.value,
                        0,
                    ),
                    (
                        SoftwareApp.long_description_status
                        == LongDescriptionStatus.FAILED.value,
                        1,
                    ),
                    (
                        SoftwareApp.long_description_status
                        == LongDescriptionStatus.COMPLETED.value,
                        2,
                    ),
                    else_=3,
                ),
                SoftwareApp.updated_at.desc(),
            )
            .options(
                selectinload(SoftwareApp.tags),
                selectinload(SoftwareApp.sources).selectinload(DownloadSource.resolved_sources),
            )
        )
        return list(result.unique())

    async def save_long_description(
        self,
        software_app_id: uuid.UUID,
        description: str,
        language: str,
        source: str,
        model: str,
        input_hash: str,
    ) -> None:
        software_app = await self.session.get(SoftwareApp, software_app_id)
        if not software_app:
            return
        software_app.long_description = description
        software_app.long_description_language = language
        software_app.long_description_status = LongDescriptionStatus.COMPLETED.value
        software_app.long_description_source = source
        software_app.long_description_model = model
        software_app.long_description_generated_at = utc_now()
        software_app.long_description_input_hash = input_hash
        software_app.long_description_error = None
        software_app.updated_at = utc_now()
        software_app.version += 1

    async def mark_long_description_failed(
        self,
        software_app_id: uuid.UUID,
        input_hash: str,
        error: str,
        source: str | None = None,
        model: str | None = None,
    ) -> None:
        software_app = await self.session.get(SoftwareApp, software_app_id)
        if not software_app:
            return
        software_app.long_description_status = LongDescriptionStatus.FAILED.value
        software_app.long_description_source = source
        software_app.long_description_model = model
        software_app.long_description_generated_at = utc_now()
        software_app.long_description_input_hash = input_hash
        software_app.long_description_error = error[:1000]
        software_app.updated_at = utc_now()
        software_app.version += 1

    async def expire_valid_resolved_sources(self, source_id: uuid.UUID) -> None:
        result = await self.session.scalars(
            select(ResolvedSource)
            .where(ResolvedSource.download_source_id == source_id)
            .where(ResolvedSource.validation_status == ValidationStatus.VALID.value)
        )
        now = utc_now()
        for resolved in result:
            resolved.validation_status = ValidationStatus.EXPIRED.value
            resolved.expires_at = now
            resolved.checked_at = now

    async def mark_source_status(
        self,
        source_id: uuid.UUID,
        resolution_status: ResolutionStatus,
        validation_status: ValidationStatus = ValidationStatus.UNCHECKED,
    ) -> None:
        source = await self.session.get(DownloadSource, source_id)
        if not source:
            return
        source.resolution_status = resolution_status.value
        source.validation_status = validation_status.value
        source.updated_at = utc_now()

    async def search_apps(
        self,
        query: str | None,
        status: str | None,
        page: int,
        page_size: int,
        sort: str = "name",
    ) -> tuple[list[SoftwareApp], int]:
        stmt = self._base_app_query(query, status)
        count_stmt = select(func.count()).select_from(stmt.subquery())
        total = await self.session.scalar(count_stmt)
        order_by = (
            SoftwareApp.updated_at.desc()
            if sort == "updated"
            else SoftwareApp.normalized_name.asc()
        )
        result = await self.session.scalars(
            stmt.order_by(order_by)
            .offset((page - 1) * page_size)
            .limit(page_size)
            .options(
                selectinload(SoftwareApp.tags),
                selectinload(SoftwareApp.sources).selectinload(DownloadSource.resolved_sources),
                selectinload(SoftwareApp.sources).selectinload(DownloadSource.allowed_domains),
            )
        )
        return list(result.unique()), int(total or 0)

    async def catalog_stats(self) -> dict:
        active = SoftwareApp.app_status == AppStatus.ACTIVE.value
        total = await self.session.scalar(select(func.count(SoftwareApp.id)).where(active))

        async def count_sources(statuses: list[str]) -> int:
            return int(
                await self.session.scalar(
                    select(func.count(func.distinct(DownloadSource.software_app_id)))
                    .join(SoftwareApp, SoftwareApp.id == DownloadSource.software_app_id)
                    .where(active)
                    .where(DownloadSource.resolution_status.in_(statuses))
                )
                or 0
            )

        available = await count_sources(
            [ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value]
        )
        review = await count_sources([ResolutionStatus.REQUIRES_MANUAL_REVIEW.value])
        missing_sources = await count_sources(
            [ResolutionStatus.MISSING.value, ResolutionStatus.BROKEN.value]
        )
        apps_with_sources = await self.session.scalar(
            select(func.count(func.distinct(DownloadSource.software_app_id)))
            .join(SoftwareApp, SoftwareApp.id == DownloadSource.software_app_id)
            .where(active)
        )
        missing = missing_sources + max(0, int(total or 0) - int(apps_with_sources or 0))

        latest_run = await self.session.scalar(
            select(ScrapeRun).order_by(ScrapeRun.started_at.desc()).limit(1)
        )
        return {
            "total": int(total or 0),
            "filters": {
                "all": int(total or 0),
                "available": available,
                "review": review,
                "missing": missing,
            },
            "last_run": latest_run,
        }

    async def get_app_by_public_id(self, public_id: str) -> SoftwareApp | None:
        conditions = [SoftwareApp.slug == public_id, SoftwareApp.winstall_id == public_id]
        try:
            conditions.append(SoftwareApp.id == uuid.UUID(public_id))
        except (TypeError, ValueError):
            pass
        stmt = (
            select(SoftwareApp)
            .options(
                selectinload(SoftwareApp.sources).selectinload(DownloadSource.resolved_sources),
                selectinload(SoftwareApp.sources).selectinload(DownloadSource.allowed_domains),
                selectinload(SoftwareApp.tags),
            )
            .where(or_(*conditions))
        )
        return await self.session.scalar(stmt)

    def reveal_url(self, resolved_source: ResolvedSource) -> str | None:
        return self.url_protector.reveal(resolved_source.resolved_url_encrypted)

    def _base_app_query(self, query: str | None, status: str | None) -> Select:
        stmt = select(SoftwareApp).where(SoftwareApp.app_status == AppStatus.ACTIVE.value)
        if query:
            q = f"%{normalize_text(query)}%"
            raw_q = f"%{query.strip().lower()}%"
            stmt = stmt.where(
                or_(
                    SoftwareApp.normalized_name.like(q),
                    func.lower(SoftwareApp.publisher).like(raw_q),
                    func.lower(SoftwareApp.description).like(raw_q),
                    func.lower(SoftwareApp.long_description).like(raw_q),
                    func.lower(SoftwareApp.winstall_id).like(raw_q),
                    SoftwareApp.tags.any(SoftwareAppTag.normalized_tag.like(q)),
                )
            )
        if status:
            stmt = stmt.join(DownloadSource)
            if status == "available":
                stmt = stmt.where(
                    DownloadSource.resolution_status.in_(
                        [ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value]
                    )
                )
            elif status == "review":
                stmt = stmt.where(
                    DownloadSource.resolution_status
                    == ResolutionStatus.REQUIRES_MANUAL_REVIEW.value
                )
            elif status == "missing":
                stmt = stmt.where(
                    DownloadSource.resolution_status.in_(
                        [ResolutionStatus.MISSING.value, ResolutionStatus.BROKEN.value]
                    )
                )
            else:
                stmt = stmt.where(DownloadSource.resolution_status == status)
            stmt = stmt.distinct()
        return stmt


def allowed_domains_for(homepage: str | None, installer_urls: list[str]) -> set[str]:
    domains: set[str] = set()
    for url in [homepage, *installer_urls]:
        domain = registered_domain(url)
        if domain:
            domains.add(domain)
    return domains


def has_icon_url(value: str | None) -> bool:
    return bool(value and value.strip() and value.strip() != "-")


def has_current_available_installer(
    app: SoftwareApp,
    now: datetime | None = None,
) -> bool:
    checked_at = now or utc_now()
    for source in app.sources:
        if source.resolution_status not in AVAILABLE_RESOLUTION_STATUSES:
            continue
        if source.validation_status != ValidationStatus.VALID.value:
            continue
        for resolved in source.resolved_sources:
            if resolved.status not in AVAILABLE_RESOLUTION_STATUSES:
                continue
            if resolved.validation_status != ValidationStatus.VALID.value:
                continue
            if resolved.expires_at > checked_at:
                return True
    return False


def registered_domain(url: str | None) -> str | None:
    if not url:
        return None
    extracted = tldextract.extract(url)
    if not extracted.domain or not extracted.suffix:
        return None
    return f"{extracted.domain}.{extracted.suffix}".lower()
