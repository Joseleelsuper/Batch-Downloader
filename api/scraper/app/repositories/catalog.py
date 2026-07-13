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
    release_rank: int | None = None
    is_latest: bool = False
    version_status: str | None = None
    metadata: dict | None = None


class CatalogRepository:
    def __init__(self, session: AsyncSession, url_protector: UrlProtector) -> None:
        self.session = session
        self.url_protector = url_protector

    async def should_scrape_winstall_package(self, package_id: str) -> bool:
        existing_id = await self.session.scalar(
            select(SoftwareApp.id).where(SoftwareApp.winstall_id == package_id).limit(1)
        )
        if existing_id is None:
            return True
        resolved_source = await self.session.scalar(
            select(DownloadSource.id)
            .where(DownloadSource.software_app_id == existing_id)
            .where(
                DownloadSource.resolution_status.in_(
                    [ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value]
                )
            )
            .where(DownloadSource.validation_status == ValidationStatus.VALID.value)
            .limit(1)
        )
        # Keep successful applications immutable during normal catalogue passes, while
        # allowing previous review/missing results to benefit from resolver fixes.
        return resolved_source is None

    async def repair_resolved_source_platforms(self) -> int:
        result = await self.session.scalars(
            select(ResolvedSource)
            .join(DownloadSource)
            .where(ResolvedSource.validation_status == ValidationStatus.VALID.value)
            .options(selectinload(ResolvedSource.source))
        )
        repaired = 0
        affected_sources: set[uuid.UUID] = set()
        for resolved in list(result.unique()):
            source = resolved.source
            target_os = inferred_platform_for_resolved_source(resolved)
            if not source or not target_os or target_os == source.operating_system:
                continue
            target_architecture = inferred_architecture_for_resolved_source(
                resolved,
                fallback=source.architecture,
            )
            target = await self._ensure_platform_source_from_existing(
                source,
                target_os,
                target_architecture,
                status=resolved.status,
                validation_status=resolved.validation_status,
            )
            if target.id == source.id:
                continue
            affected_sources.add(source.id)
            affected_sources.add(target.id)
            resolved.download_source_id = target.id
            metadata = dict(resolved.metadata_json or {})
            metadata["operating_system"] = target_os
            metadata["architecture"] = target_architecture
            metadata["platform_repaired"] = True
            resolved.metadata_json = json_safe(metadata)
            resolved.checked_at = utc_now()
            source.updated_at = utc_now()
            target.updated_at = utc_now()
            repaired += 1

        await self.session.flush()
        for source_id in affected_sources:
            await self._refresh_source_status_from_resolved(source_id)
        return repaired

    async def upsert_winstall_app(self, app: WinstallApp) -> SoftwareApp:
        existing = await self.session.scalar(
            select(SoftwareApp)
            .options(
                selectinload(SoftwareApp.tags),
                selectinload(SoftwareApp.sources),
            )
            .where(SoftwareApp.winstall_id == app.package_id)
        )
        if existing is not None:
            return existing

        slug = slugify(app.package_id)
        icon_url = app.icon_url
        if app.icon and not app.icon.startswith("http"):
            icon_key = app.icon.removesuffix(".png")
            icon_url = f"https://api.winstall.app/icons/next/{icon_key}.webp"

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
        await self.ensure_download_source(
            software_app_id=software_app.id,
            app=app,
            operating_system="windows",
            architecture="x86_64",
            initial_url=app.homepage,
        )

    async def ensure_download_source(
        self,
        software_app_id: uuid.UUID,
        app: WinstallApp,
        operating_system: str,
        architecture: str,
        initial_url: str | None,
    ) -> DownloadSource:
        source = await self.session.scalar(
            select(DownloadSource)
            .where(DownloadSource.software_app_id == software_app_id)
            .where(DownloadSource.operating_system == operating_system)
            .where(DownloadSource.architecture == architecture)
        )
        if source is None:
            source = DownloadSource(
                software_app_id=software_app_id,
                operating_system=operating_system,
                architecture=architecture,
                initial_url=initial_url,
                resolver_type="generic_http",
                resolver_config=json_safe({"winstall_id": app.package_id}),
                resolution_status=ResolutionStatus.MISSING.value,
                validation_status=ValidationStatus.UNCHECKED.value,
            )
            self.session.add(source)
            await self.session.flush()
        else:
            source.initial_url = initial_url or source.initial_url
            source.resolver_config = json_safe({"winstall_id": app.package_id})
            source.updated_at = utc_now()

        return source

    async def _ensure_platform_source_from_existing(
        self,
        source: DownloadSource,
        operating_system: str,
        architecture: str,
        *,
        status: str,
        validation_status: str,
    ) -> DownloadSource:
        target = await self.session.scalar(
            select(DownloadSource)
            .where(DownloadSource.software_app_id == source.software_app_id)
            .where(DownloadSource.operating_system == operating_system)
            .where(DownloadSource.architecture == architecture)
            .limit(1)
        )
        if target is None:
            target = DownloadSource(
                software_app_id=source.software_app_id,
                operating_system=operating_system,
                architecture=architecture,
                initial_url=source.initial_url,
                resolver_type=source.resolver_type,
                resolver_config=json_safe(source.resolver_config),
                resolution_status=status,
                validation_status=validation_status,
            )
            self.session.add(target)
            await self.session.flush()
        else:
            target.initial_url = target.initial_url or source.initial_url
            target.resolver_config = target.resolver_config or json_safe(source.resolver_config)
            target.updated_at = utc_now()

        return target

    async def _refresh_source_status_from_resolved(self, source_id: uuid.UUID) -> None:
        source = await self.session.get(DownloadSource, source_id)
        if not source:
            return
        resolved = await self.session.scalar(
            select(ResolvedSource)
            .where(ResolvedSource.download_source_id == source_id)
            .where(ResolvedSource.validation_status == ValidationStatus.VALID.value)
            .order_by(
                ResolvedSource.is_latest.desc(),
                ResolvedSource.release_rank.asc(),
                case((ResolvedSource.status == ResolutionStatus.DIRECT.value, 1), else_=0).desc(),
                ResolvedSource.score.desc(),
                ResolvedSource.checked_at.desc(),
            )
            .limit(1)
        )
        if resolved:
            source.resolution_status = resolved.status
            source.validation_status = resolved.validation_status
        elif source.validation_status == ValidationStatus.VALID.value:
            source.resolution_status = ResolutionStatus.REQUIRES_MANUAL_REVIEW.value
            source.validation_status = ValidationStatus.UNCHECKED.value
        source.updated_at = utc_now()

    async def refresh_source_statuses(self, source_ids: set[uuid.UUID]) -> None:
        await self.session.flush()
        for source_id in source_ids:
            await self._refresh_source_status_from_resolved(source_id)

    async def repair_source_statuses(self) -> int:
        source_ids = set(await self.session.scalars(select(DownloadSource.id)))
        await self.refresh_source_statuses(source_ids)
        return len(source_ids)

    async def source_for_platform(
        self,
        software_app_id: uuid.UUID,
        operating_system: str,
        architecture: str,
    ) -> DownloadSource | None:
        return await self.session.scalar(
            select(DownloadSource)
            .options(
                selectinload(DownloadSource.resolved_sources),
            )
            .where(DownloadSource.software_app_id == software_app_id)
            .where(DownloadSource.operating_system == operating_system)
            .where(DownloadSource.architecture == architecture)
            .limit(1)
        )

    async def default_source_for_app(self, software_app_id: uuid.UUID) -> DownloadSource | None:
        return await self.session.scalar(
            select(DownloadSource)
            .options(
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
            release_rank=item.release_rank,
            is_latest=item.is_latest,
            version_status=item.version_status,
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

    async def apps_for_description_enrichment(
        self,
        app_ids: list[uuid.UUID] | None = None,
        *,
        include_completed: bool = False,
    ) -> list[SoftwareApp]:
        missing_long_description = or_(
            SoftwareApp.long_description.is_(None),
            SoftwareApp.long_description == "",
        )
        needs_description = or_(
            missing_long_description,
            SoftwareApp.long_description_status.in_(
                [LongDescriptionStatus.PENDING.value, LongDescriptionStatus.FAILED.value]
            ),
        )
        stmt = select(SoftwareApp).where(SoftwareApp.app_status == AppStatus.ACTIVE.value).options(
            selectinload(SoftwareApp.tags),
            selectinload(SoftwareApp.sources).selectinload(DownloadSource.resolved_sources),
        )
        if app_ids is not None:
            if not app_ids:
                return []
            stmt = stmt.where(SoftwareApp.id.in_(app_ids))
        elif not include_completed:
            stmt = stmt.where(needs_description)
            stmt = stmt.order_by(
                case(
                    (missing_long_description, 0),
                    (
                        SoftwareApp.long_description_status
                        == LongDescriptionStatus.PENDING.value,
                        1,
                    ),
                    (
                        SoftwareApp.long_description_status
                        == LongDescriptionStatus.FAILED.value,
                        2,
                    ),
                    else_=3,
                ),
                SoftwareApp.updated_at.desc(),
            )

        result = await self.session.scalars(stmt)
        return list(result.unique())

    async def mark_long_description_pending(self, software_app_id: uuid.UUID) -> None:
        software_app = await self.session.get(SoftwareApp, software_app_id)
        if not software_app:
            return
        software_app.long_description_status = LongDescriptionStatus.PENDING.value
        software_app.long_description_error = None
        software_app.updated_at = utc_now()
        await self.session.flush()

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
                selectinload(SoftwareApp.tags),
            )
            .where(or_(*conditions))
        )
        return await self.session.scalar(stmt)

    async def get_resolved_source_by_ref(self, source_ref: str) -> ResolvedSource | None:
        try:
            resolved_source_id = uuid.UUID(source_ref)
        except (TypeError, ValueError):
            return None
        return await self.session.scalar(
            select(ResolvedSource)
            .options(selectinload(ResolvedSource.source))
            .where(ResolvedSource.id == resolved_source_id)
        )

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


def inferred_platform_for_resolved_source(resolved: ResolvedSource) -> str | None:
    metadata = resolved.metadata_json or {}
    platform_text = " ".join(
        str(value).lower()
        for value in (
            resolved.filename,
            metadata.get("candidate_label"),
        )
        if value
    )
    if any(token in platform_text for token in ("macos", "mac-os", "darwin", "apple-silicon")):
        return "macos"
    if any(token in platform_text for token in ("windows", "win32", "win64", "win-x64")):
        return "windows"
    if any(token in platform_text for token in ("linux", "ubuntu", "debian", "appimage")):
        return "linux"

    stored_platform = metadata.get("operating_system")
    if stored_platform in {"windows", "macos", "linux"}:
        return str(stored_platform)

    extension = normalized_extension(resolved.extension)
    if extension in {".exe", ".msi", ".msix", ".appx"}:
        return "windows"
    if extension in {".dmg", ".pkg"}:
        return "macos"
    if extension in {".deb", ".rpm", ".appimage", ".tar.gz", ".jar"}:
        return "linux"

    filename = (resolved.filename or "").lower()
    if filename.endswith((".exe", ".msi", ".msix", ".appx")):
        return "windows"
    if filename.endswith((".dmg", ".pkg")):
        return "macos"
    if filename.endswith((".deb", ".rpm", ".appimage", ".tar.gz", ".jar")):
        return "linux"
    return None


def inferred_architecture_for_resolved_source(
    resolved: ResolvedSource,
    fallback: str,
) -> str:
    metadata = resolved.metadata_json or {}
    text = " ".join(
        str(value)
        for value in (
            resolved.filename,
            resolved.extension,
            metadata.get("candidate_label"),
            metadata.get("candidate_source"),
        )
        if value
    ).lower()
    if any(token in text for token in ("aarch64", "arm64", "apple silicon", "m1", "m2", "m3")):
        return "aarch64"
    if any(token in text for token in ("i386", "i686", "win32", "32-bit", "32bit")):
        return "x86"
    if any(token in text for token in ("x86_64", "amd64", "x64", "win64", "64-bit", "64bit")):
        return "x86_64"
    return fallback or "x86_64"


def normalized_extension(value: str | None) -> str | None:
    if not value:
        return None
    extension = value.lower().strip()
    return extension if extension.startswith(".") else f".{extension}"
