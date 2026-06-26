import uuid
from datetime import datetime

from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    JSON,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.time import utc_now
from app.db.base import Base
from app.db.enums import AppStatus, ResolutionStatus, ScrapeRunStatus, ValidationStatus
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
    publisher: Mapped[str | None] = mapped_column(String(180))
    icon_url: Mapped[str | None] = mapped_column(String(2048))
    official_url: Mapped[str | None] = mapped_column(String(2048))
    latest_version: Mapped[str | None] = mapped_column(String(100))
    app_status: Mapped[str] = mapped_column(
        String(32), default=AppStatus.ACTIVE.value, index=True, nullable=False
    )
    metadata_json: Mapped[dict | None] = mapped_column(JSON)
    version: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)

    sources: Mapped[list["DownloadSource"]] = relationship(
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
    architecture: Mapped[str] = mapped_column(String(32), default="x86_64", nullable=False)
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

    software_app: Mapped[SoftwareApp] = relationship(back_populates="sources")
    allowed_domains: Mapped[list["SourceAllowedDomain"]] = relationship(
        back_populates="source", cascade="all, delete-orphan"
    )
    resolved_sources: Mapped[list["ResolvedSource"]] = relationship(
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


class SourceAllowedDomain(Base):
    __tablename__ = "source_allowed_domains"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    source_id: Mapped[uuid.UUID] = mapped_column(GUID(), ForeignKey("download_sources.id"))
    domain: Mapped[str] = mapped_column(String(253), nullable=False)
    include_subdomains: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)

    source: Mapped[DownloadSource] = relationship(back_populates="allowed_domains")

    __table_args__ = (UniqueConstraint("source_id", "domain", name="uq_source_allowed_domain"),)


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
    score: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    status: Mapped[str] = mapped_column(String(32), index=True, nullable=False)
    validation_status: Mapped[str] = mapped_column(String(32), index=True, nullable=False)
    checked_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    metadata_json: Mapped[dict | None] = mapped_column(JSON)

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
    status: Mapped[str] = mapped_column(
        String(32), default=ScrapeRunStatus.RUNNING.value, index=True, nullable=False
    )
    started_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    heartbeat_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime)
    apps_discovered: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    apps_resolved: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    apps_failed: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    worker_id: Mapped[str] = mapped_column(String(120), nullable=False)
    error_summary: Mapped[str | None] = mapped_column(String(1000))
