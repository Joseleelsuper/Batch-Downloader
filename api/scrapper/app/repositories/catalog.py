import uuid
from dataclasses import dataclass

import tldextract
from sqlalchemy import Select, and_, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.time import utc_after, utc_now
from app.core.url_protector import UrlProtector
from app.db.enums import AppStatus, ResolutionStatus, ValidationStatus
from app.db.models import (
    DownloadSource,
    ResolvedSource,
    SoftwareApp,
    SourceAllowedDomain,
)
from app.scraper.text import normalize_text, slugify
from app.scraper.winstall import WinstallApp


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

    async def upsert_winstall_app(self, app: WinstallApp) -> SoftwareApp:
        existing = await self.session.scalar(
            select(SoftwareApp)
            .options(selectinload(SoftwareApp.sources).selectinload(DownloadSource.allowed_domains))
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
                publisher=app.publisher,
                icon_url=icon_url,
                official_url=app.homepage,
                latest_version=app.latest_version,
                app_status=AppStatus.ACTIVE.value,
                metadata_json=app.raw,
            )
            self.session.add(existing)
            await self.session.flush()
        else:
            existing.name = app.name or existing.name
            existing.normalized_name = normalize_text(existing.name)
            existing.description = app.description
            existing.publisher = app.publisher
            existing.icon_url = icon_url
            existing.official_url = app.homepage
            existing.latest_version = app.latest_version
            existing.metadata_json = app.raw
            existing.updated_at = utc_now()
            existing.version += 1

        await self._ensure_default_source(existing, app)
        return existing

    async def _ensure_default_source(self, software_app: SoftwareApp, app: WinstallApp) -> None:
        source = next(
            (
                source
                for source in software_app.sources
                if source.operating_system == "windows" and source.architecture == "x86_64"
            ),
            None,
        )
        if source is None:
            source = DownloadSource(
                software_app_id=software_app.id,
                operating_system="windows",
                architecture="x86_64",
                initial_url=app.homepage,
                resolver_type="generic_http",
                resolver_config={"winstall_id": app.package_id},
                resolution_status=ResolutionStatus.MISSING.value,
                validation_status=ValidationStatus.UNCHECKED.value,
            )
            self.session.add(source)
            await self.session.flush()
            software_app.sources.append(source)
        else:
            source.initial_url = app.homepage or source.initial_url
            source.resolver_config = {"winstall_id": app.package_id}
            source.updated_at = utc_now()

        domains = allowed_domains_for(app.homepage, app.installer_urls)
        existing_domains = {domain.domain for domain in source.allowed_domains}
        for domain in domains - existing_domains:
            self.session.add(
                SourceAllowedDomain(source_id=source.id, domain=domain, include_subdomains=True)
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
            metadata_json=item.metadata,
        )
        self.session.add(resolved)

        source = await self.session.get(DownloadSource, item.source_id)
        if source:
            source.resolution_status = item.status.value
            source.validation_status = item.validation_status.value
            source.updated_at = utc_now()
            source.version += 1
        return resolved

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
    ) -> tuple[list[SoftwareApp], int]:
        stmt = self._base_app_query(query, status)
        count_stmt = select(func.count()).select_from(stmt.subquery())
        total = await self.session.scalar(count_stmt)
        result = await self.session.scalars(
            stmt.order_by(SoftwareApp.normalized_name)
            .offset((page - 1) * page_size)
            .limit(page_size)
            .options(
                selectinload(SoftwareApp.sources).selectinload(DownloadSource.resolved_sources),
                selectinload(SoftwareApp.sources).selectinload(DownloadSource.allowed_domains),
            )
        )
        return list(result.unique()), int(total or 0)

    async def get_app_by_public_id(self, public_id: str) -> SoftwareApp | None:
        stmt = (
            select(SoftwareApp)
            .options(
                selectinload(SoftwareApp.sources).selectinload(DownloadSource.resolved_sources),
                selectinload(SoftwareApp.sources).selectinload(DownloadSource.allowed_domains),
            )
            .where(or_(SoftwareApp.slug == public_id, SoftwareApp.winstall_id == public_id))
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
                    func.lower(SoftwareApp.winstall_id).like(raw_q),
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
                    DownloadSource.resolution_status == ResolutionStatus.REQUIRES_MANUAL_REVIEW.value
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


def registered_domain(url: str | None) -> str | None:
    if not url:
        return None
    extracted = tldextract.extract(url)
    if not extracted.domain or not extracted.suffix:
        return None
    return f"{extracted.domain}.{extracted.suffix}".lower()
