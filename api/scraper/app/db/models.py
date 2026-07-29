import uuid
from datetime import datetime
from typing import Literal

from sqlalchemy import (
    JSON,
    BigInteger,
    Boolean,
    CheckConstraint,
    Computed,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, query_expression, relationship

from app.core.time import utc_now
from app.db.base import Base
from app.db.enums import (
    AppStatus,
    LongDescriptionStatus,
    ResolutionStatus,
    ScrapeRunStatus,
    ValidationStatus,
)
from app.db.types import GUID, uuid_pk


class TimestampMixin:
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=utc_now, onupdate=utc_now, nullable=False
    )


class SoftwareApp(Base, TimestampMixin):
    __tablename__ = "software_apps"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    winstall_id: Mapped[str] = mapped_column(String(180), unique=True, nullable=False)
    slug: Mapped[str] = mapped_column(String(180), unique=True, nullable=False)
    name: Mapped[str] = mapped_column(String(180), nullable=False)
    normalized_name: Mapped[str] = mapped_column(String(180), index=True, nullable=False)
    description: Mapped[str | None] = mapped_column(Text)
    long_description: Mapped[str | None] = mapped_column(Text)
    long_description_language: Mapped[str | None] = mapped_column(String(16))
    long_description_status: Mapped[str] = mapped_column(
        String(32),
        default=LongDescriptionStatus.PENDING.value,
        index=True,
        nullable=False,
    )
    long_description_source: Mapped[str | None] = mapped_column(String(50))
    long_description_model: Mapped[str | None] = mapped_column(String(120))
    long_description_generated_at: Mapped[datetime | None] = mapped_column(DateTime)
    long_description_input_hash: Mapped[str | None] = mapped_column(String(64), index=True)
    long_description_error: Mapped[str | None] = mapped_column(String(1000))
    publisher: Mapped[str | None] = mapped_column(String(180))
    icon_url: Mapped[str | None] = mapped_column(String(2048))
    official_url: Mapped[str | None] = mapped_column(String(2048))
    latest_version: Mapped[str | None] = mapped_column(String(100))
    app_status: Mapped[str] = mapped_column(
        String(32), default=AppStatus.ACTIVE.value, index=True, nullable=False
    )
    metadata_json: Mapped[dict | None] = mapped_column(JSON)
    operating_systems: Mapped[list[str]] = mapped_column(
        "operating_systems_json",
        JSON,
        default=list,
        nullable=False,
    )
    operating_systems_updated_at: Mapped[datetime | None] = mapped_column(DateTime)
    version: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    catalog_available_source_count: Mapped[int] = mapped_column(
        Integer,
        default=0,
        server_default="0",
        nullable=False,
    )
    catalog_review_source_count: Mapped[int] = mapped_column(
        Integer,
        default=0,
        server_default="0",
        nullable=False,
    )
    catalog_status: Mapped[Literal["available", "review", "missing"] | None] = mapped_column(
        String(16),
        Computed(
            "CASE "
            "WHEN app_status <> 'active' THEN NULL "
            "WHEN catalog_available_source_count > 0 THEN 'available' "
            "WHEN catalog_review_source_count > 0 THEN 'review' "
            "ELSE 'missing' END",
            persisted=True,
        ),
    )

    sources: Mapped[list[DownloadSource]] = relationship(
        back_populates="software_app", cascade="all, delete-orphan"
    )
    tags: Mapped[list[SoftwareAppTag]] = relationship(
        back_populates="software_app", cascade="all, delete-orphan"
    )

    __table_args__ = (Index("ix_software_apps_status_name", "app_status", "normalized_name"),)


class DownloadSource(Base, TimestampMixin):
    __tablename__ = "download_sources"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    software_app_id: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("software_apps.id"), nullable=False
    )
    operating_system: Mapped[str] = mapped_column(String(32), default="windows", nullable=False)
    architecture: Mapped[str] = mapped_column(String(32), default="UNKNOWN", nullable=False)
    initial_url: Mapped[str | None] = mapped_column(String(2048))
    resolver_type: Mapped[str] = mapped_column(String(50), default="generic_http", nullable=False)
    resolver_config: Mapped[dict | None] = mapped_column(JSON)
    resolution_status: Mapped[str] = mapped_column(
        String(32), default=ResolutionStatus.MISSING.value, index=True, nullable=False
    )
    validation_status: Mapped[str] = mapped_column(
        String(32), default=ValidationStatus.UNCHECKED.value, index=True, nullable=False
    )
    version: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    catalog_downloadable_count: Mapped[int] = mapped_column(
        Integer,
        default=0,
        server_default="0",
        nullable=False,
    )
    catalog_available: Mapped[bool] = mapped_column(
        Boolean,
        Computed(
            "CASE WHEN resolution_status IN ('direct', 'fallback') "
            "AND validation_status = 'valid' "
            "AND catalog_downloadable_count > 0 "
            "THEN 1 ELSE 0 END",
            persisted=True,
        ),
    )

    software_app: Mapped[SoftwareApp] = relationship(back_populates="sources")
    resolved_sources: Mapped[list[ResolvedSource]] = relationship(
        back_populates="source", cascade="all, delete-orphan"
    )

    __table_args__ = (
        Index(
            "ix_download_sources_app_platform",
            "software_app_id",
            "operating_system",
            "architecture",
            "resolution_status",
        ),
    )


class SoftwareAppTag(Base):
    __tablename__ = "software_app_tags"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    software_app_id: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("software_apps.id"), nullable=False
    )
    tag: Mapped[str] = mapped_column(String(120), nullable=False)
    normalized_tag: Mapped[str] = mapped_column(String(120), index=True, nullable=False)
    source: Mapped[str] = mapped_column(String(50), default="winstall", nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)

    software_app: Mapped[SoftwareApp] = relationship(back_populates="tags")

    __table_args__ = (
        UniqueConstraint("software_app_id", "normalized_tag", name="uq_software_app_tag"),
        Index("ix_software_app_tags_app", "software_app_id"),
    )


class CatalogCounter(Base):
    """Singleton read model maintained by the catalog projection triggers."""

    __tablename__ = "catalog_counters"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, default=1)
    total_count: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    available_count: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    review_count: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    missing_count: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    version: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)

    __table_args__ = (
        CheckConstraint("id = 1", name="ck_catalog_counters_singleton"),
        CheckConstraint(
            "total_count = available_count + review_count + missing_count",
            name="ck_catalog_counters_partition",
        ),
    )


class ResolvedSource(Base):
    __tablename__ = "resolved_sources"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    download_source_id: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("download_sources.id"), nullable=False
    )
    resolved_url_encrypted: Mapped[str] = mapped_column(Text, nullable=False)
    final_domain: Mapped[str] = mapped_column(String(253), nullable=False)
    filename: Mapped[str | None] = mapped_column(String(255))
    extension: Mapped[str | None] = mapped_column(String(20))
    content_type: Mapped[str | None] = mapped_column(String(180))
    size_bytes: Mapped[int | None] = mapped_column(BigInteger)
    version: Mapped[str | None] = mapped_column(String(100))
    release_rank: Mapped[int | None] = mapped_column(Integer)
    is_latest: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    version_status: Mapped[str | None] = mapped_column(String(32))
    score: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    status: Mapped[str] = mapped_column(String(32), index=True, nullable=False)
    validation_status: Mapped[str] = mapped_column(String(32), index=True, nullable=False)
    checked_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    metadata_json: Mapped[dict | None] = mapped_column(JSON)
    # Alembic 0010 owns the physical generated column. Mapping it only as a
    # query expression keeps SQLite's metadata-based test schema unchanged.
    catalog_downloadable: Mapped[bool | None] = query_expression()

    source: Mapped[DownloadSource] = relationship(back_populates="resolved_sources")

    __table_args__ = (
        Index("ix_resolved_sources_source_expiry", "download_source_id", "expires_at"),
        Index("ix_resolved_sources_status_expiry", "status", "expires_at"),
    )


class ResolverLog(Base):
    __tablename__ = "resolver_logs"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    download_source_id: Mapped[uuid.UUID | None] = mapped_column(
        GUID(), ForeignKey("download_sources.id")
    )
    phase: Mapped[str] = mapped_column(String(50), nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    message: Mapped[str | None] = mapped_column(String(2000))
    safe_metadata: Mapped[dict | None] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)


class ScrapeRun(Base):
    __tablename__ = "scrape_runs"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    active_lock: Mapped[int | None] = mapped_column(Integer, unique=True)
    status: Mapped[str] = mapped_column(
        String(32), default=ScrapeRunStatus.RUNNING.value, index=True, nullable=False
    )
    started_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    heartbeat_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime)
    apps_discovered: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    apps_resolved: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    apps_failed: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    apps_skipped: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    worker_id: Mapped[str] = mapped_column(String(120), nullable=False)
    error_summary: Mapped[str | None] = mapped_column(String(1000))
    current_package_id: Mapped[str | None] = mapped_column(String(180))
    current_app_name: Mapped[str | None] = mapped_column(String(180))
    current_phase: Mapped[str | None] = mapped_column(String(80))
    stop_requested: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    paused_at: Mapped[datetime | None] = mapped_column(DateTime)


class ScraperCommand(Base):
    __tablename__ = "scraper_commands"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    command: Mapped[str] = mapped_column(String(32), nullable=False)
    status: Mapped[str] = mapped_column(String(32), default="pending", index=True, nullable=False)
    message: Mapped[str | None] = mapped_column(String(1000))
    created_by: Mapped[str] = mapped_column(String(180), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime)

    __table_args__ = (Index("ix_scraper_commands_status_created", "status", "created_at"),)


class ManualInstallerInspection(Base, TimestampMixin):
    """Persisted, recoverable preview for an administrator-supplied installer.

    Input URLs are deliberately kept outside ``result_json`` so API serializers,
    queue payloads, logs, and snapshots cannot expose them accidentally.
    """

    __tablename__ = "manual_installer_inspections"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    software_app_id: Mapped[uuid.UUID] = mapped_column(
        GUID(),
        ForeignKey("software_apps.id", ondelete="CASCADE"),
        nullable=False,
    )
    status: Mapped[str] = mapped_column(String(16), default="queued", nullable=False)
    phase: Mapped[str] = mapped_column(String(32), default="queued", nullable=False)
    captured_app_version: Mapped[int] = mapped_column(BigInteger, nullable=False)
    input_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    installer_url_encrypted: Mapped[str | None] = mapped_column(Text)
    windows_installer_url_encrypted: Mapped[str | None] = mapped_column(Text)
    macos_installer_url_encrypted: Mapped[str | None] = mapped_column(Text)
    linux_installer_url_encrypted: Mapped[str | None] = mapped_column(Text)
    source_page_url_encrypted: Mapped[str] = mapped_column(Text, nullable=False)
    result_json: Mapped[dict | None] = mapped_column(JSON)
    warnings_json: Mapped[list[str]] = mapped_column(JSON, default=list, nullable=False)
    error_code: Mapped[str | None] = mapped_column(String(120))
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    applied_at: Mapped[datetime | None] = mapped_column(DateTime)
    applied_app_version: Mapped[int | None] = mapped_column(BigInteger)
    source_ref: Mapped[uuid.UUID | None] = mapped_column(
        GUID(),
        ForeignKey("resolved_sources.id", ondelete="SET NULL"),
    )

    __table_args__ = (
        Index(
            "ix_manual_installer_inspections_app_status",
            "software_app_id",
            "status",
            "created_at",
        ),
        Index("ix_manual_installer_inspections_expires", "expires_at"),
    )


class WebsiteAppDiscovery(Base, TimestampMixin):
    """Recoverable metadata and installer discovery started from an official site."""

    __tablename__ = "website_app_discoveries"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    status: Mapped[str] = mapped_column(String(16), default="queued", nullable=False)
    phase: Mapped[str] = mapped_column(String(32), default="queued", nullable=False)
    input_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    official_url_encrypted: Mapped[str] = mapped_column(Text, nullable=False)
    windows_installer_url_encrypted: Mapped[str | None] = mapped_column(Text)
    macos_installer_url_encrypted: Mapped[str | None] = mapped_column(Text)
    linux_installer_url_encrypted: Mapped[str | None] = mapped_column(Text)
    result_json: Mapped[dict | None] = mapped_column(JSON)
    warnings_json: Mapped[list[str]] = mapped_column(JSON, default=list, nullable=False)
    error_code: Mapped[str | None] = mapped_column(String(120))
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    applied_at: Mapped[datetime | None] = mapped_column(DateTime)
    applied_app_id: Mapped[uuid.UUID | None] = mapped_column(
        GUID(),
        ForeignKey("software_apps.id", ondelete="SET NULL"),
    )

    installers: Mapped[list[WebsiteAppDiscoveryInstaller]] = relationship(
        back_populates="discovery",
        cascade="all, delete-orphan",
    )

    __table_args__ = (
        Index(
            "ix_website_app_discoveries_hash_status",
            "input_hash",
            "status",
            "created_at",
        ),
        Index("ix_website_app_discoveries_expires", "expires_at"),
    )


class WebsiteAppDiscoveryInstaller(Base, TimestampMixin):
    """Validated installer evidence whose executable URL remains encrypted."""

    __tablename__ = "website_app_discovery_installers"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    discovery_id: Mapped[uuid.UUID] = mapped_column(
        GUID(),
        ForeignKey("website_app_discoveries.id", ondelete="CASCADE"),
        nullable=False,
    )
    installer_url_encrypted: Mapped[str] = mapped_column(Text, nullable=False)
    final_domain: Mapped[str | None] = mapped_column(String(255))
    filename: Mapped[str | None] = mapped_column(String(255))
    extension: Mapped[str | None] = mapped_column(String(32))
    content_type: Mapped[str | None] = mapped_column(String(255))
    size_bytes: Mapped[int | None] = mapped_column(BigInteger)
    version: Mapped[str | None] = mapped_column(String(100))
    operating_system: Mapped[str] = mapped_column(String(32), nullable=False)
    architecture: Mapped[str] = mapped_column(String(32), nullable=False)
    score: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    discovery: Mapped[WebsiteAppDiscovery] = relationship(back_populates="installers")

    __table_args__ = (
        Index(
            "ix_website_app_discovery_installers_discovery",
            "discovery_id",
            "operating_system",
            "architecture",
        ),
    )


class ScraperWorkItem(Base, TimestampMixin):
    __tablename__ = "scraper_work_items"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    run_id: Mapped[uuid.UUID | None] = mapped_column(GUID(), ForeignKey("scrape_runs.id"))
    queue: Mapped[str] = mapped_column(String(32), nullable=False)
    status: Mapped[str] = mapped_column(String(32), default="queued", nullable=False)
    package_id: Mapped[str] = mapped_column(String(180), nullable=False)
    app_name: Mapped[str | None] = mapped_column(String(180))
    payload_json: Mapped[dict | None] = mapped_column(JSON)
    priority: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    attempts: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    lease_owner: Mapped[str | None] = mapped_column(String(120))
    lease_expires_at: Mapped[datetime | None] = mapped_column(DateTime)
    available_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    last_error: Mapped[str | None] = mapped_column(String(1000))

    __table_args__ = (
        UniqueConstraint("queue", "package_id", name="uq_scraper_work_queue_package"),
        Index("ix_scraper_work_queue_status_available", "queue", "status", "available_at"),
        Index("ix_scraper_work_lease", "status", "lease_expires_at"),
    )


class ScraperWorkerSnapshot(Base):
    __tablename__ = "scraper_worker_snapshots"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    run_id: Mapped[uuid.UUID | None] = mapped_column(GUID(), ForeignKey("scrape_runs.id"))
    worker_id: Mapped[str] = mapped_column(String(120), nullable=False)
    stage: Mapped[str] = mapped_column(String(32), nullable=False)
    package_id: Mapped[str | None] = mapped_column(String(180))
    app_name: Mapped[str | None] = mapped_column(String(180))
    url: Mapped[str | None] = mapped_column(String(2048))
    html: Mapped[str | None] = mapped_column(Text)
    captured_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)

    __table_args__ = (
        Index("ix_scraper_snapshots_stage_captured", "stage", "captured_at"),
        Index("ix_scraper_snapshots_expires", "expires_at"),
    )


class ScraperMetricSnapshot(Base):
    __tablename__ = "scraper_metric_snapshots"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    run_id: Mapped[uuid.UUID | None] = mapped_column(GUID(), ForeignKey("scrape_runs.id"))
    available: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    review: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    unavailable: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    queued_searcher_filter: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    queued_filter_scraper: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    queued_scraper_so_filter: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    queued_so_filter_descriptor: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    captured_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)

    __table_args__ = (Index("ix_scraper_metric_snapshots_captured", "captured_at"),)


class ScraperRateLimit(Base):
    __tablename__ = "scraper_rate_limits"

    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    next_allowed_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
