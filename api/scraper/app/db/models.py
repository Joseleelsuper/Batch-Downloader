"""Implementa las responsabilidades del módulo `models`.
"""
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
    AbsenceVerificationStatus,
    AppStatus,
    LongDescriptionStatus,
    ResolutionStatus,
    ScrapeRunStatus,
    ScrapeScope,
    ValidationStatus,
)
from app.db.types import GUID, uuid_pk


class TimestampMixin:
    """Representa el componente `TimestampMixin`.
    """
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    """Campo declarado `created_at` de `TimestampMixin`.
    """
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=utc_now, onupdate=utc_now, nullable=False
    )
    """Campo declarado `updated_at` de `TimestampMixin`.
    """


class SoftwareApp(Base, TimestampMixin):
    """Representa el componente `SoftwareApp`.
    """
    __tablename__ = "software_apps"
    """Campo declarado `__tablename__` de `SoftwareApp`.
    """

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    """Campo declarado `id` de `SoftwareApp`.
    """
    winstall_id: Mapped[str] = mapped_column(String(180), unique=True, nullable=False)
    """Campo declarado `winstall_id` de `SoftwareApp`.
    """
    slug: Mapped[str] = mapped_column(String(180), unique=True, nullable=False)
    """Campo declarado `slug` de `SoftwareApp`.
    """
    name: Mapped[str] = mapped_column(String(180), nullable=False)
    """Campo declarado `name` de `SoftwareApp`.
    """
    normalized_name: Mapped[str] = mapped_column(String(180), index=True, nullable=False)
    """Campo declarado `normalized_name` de `SoftwareApp`.
    """
    description: Mapped[str | None] = mapped_column(Text)
    """Campo declarado `description` de `SoftwareApp`.
    """
    long_description: Mapped[str | None] = mapped_column(Text)
    """Campo declarado `long_description` de `SoftwareApp`.
    """
    long_description_language: Mapped[str | None] = mapped_column(String(16))
    """Campo declarado `long_description_language` de `SoftwareApp`.
    """
    long_description_status: Mapped[str] = mapped_column(
        String(32),
        default=LongDescriptionStatus.PENDING.value,
        index=True,
        nullable=False,
    )
    """Campo declarado `long_description_status` de `SoftwareApp`.
    """
    long_description_source: Mapped[str | None] = mapped_column(String(50))
    """Campo declarado `long_description_source` de `SoftwareApp`.
    """
    long_description_model: Mapped[str | None] = mapped_column(String(120))
    """Campo declarado `long_description_model` de `SoftwareApp`.
    """
    long_description_generated_at: Mapped[datetime | None] = mapped_column(DateTime)
    """Campo declarado `long_description_generated_at` de `SoftwareApp`.
    """
    long_description_input_hash: Mapped[str | None] = mapped_column(String(64), index=True)
    """Campo declarado `long_description_input_hash` de `SoftwareApp`.
    """
    long_description_error: Mapped[str | None] = mapped_column(String(1000))
    """Campo declarado `long_description_error` de `SoftwareApp`.
    """
    publisher: Mapped[str | None] = mapped_column(String(180))
    """Campo declarado `publisher` de `SoftwareApp`.
    """
    icon_url: Mapped[str | None] = mapped_column(String(2048))
    """Campo declarado `icon_url` de `SoftwareApp`.
    """
    official_url: Mapped[str | None] = mapped_column(String(2048))
    """Campo declarado `official_url` de `SoftwareApp`.
    """
    latest_version: Mapped[str | None] = mapped_column(String(100))
    """Campo declarado `latest_version` de `SoftwareApp`.
    """
    winstall_latest_version: Mapped[str | None] = mapped_column(String(100))
    """Última versión anunciada por Winstall, separada de una corrección manual."""
    winstall_updated_at: Mapped[datetime | None] = mapped_column(DateTime)
    """Marca temporal publicada por Winstall cuando está disponible."""
    winstall_summary_fingerprint: Mapped[str | None] = mapped_column(String(64), index=True)
    """Huella de los campos ligeros utilizada por el scope incremental."""
    winstall_detail_fingerprint: Mapped[str | None] = mapped_column(String(64), index=True)
    """Huella del detalle autoritativo, incluidas sus listas de instaladores."""
    app_status: Mapped[str] = mapped_column(
        String(32), default=AppStatus.ACTIVE.value, index=True, nullable=False
    )
    """Campo declarado `app_status` de `SoftwareApp`.
    """
    metadata_json: Mapped[dict | None] = mapped_column(JSON)
    """Campo declarado `metadata_json` de `SoftwareApp`.
    """
    operating_systems: Mapped[list[str]] = mapped_column(
        "operating_systems_json",
        JSON,
        default=list,
        nullable=False,
    )
    """Campo declarado `operating_systems` de `SoftwareApp`.
    """
    operating_systems_updated_at: Mapped[datetime | None] = mapped_column(DateTime)
    """Campo declarado `operating_systems_updated_at` de `SoftwareApp`.
    """
    version: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    """Campo declarado `version` de `SoftwareApp`.
    """
    catalog_available_source_count: Mapped[int] = mapped_column(
        Integer,
        default=0,
        server_default="0",
        nullable=False,
    )
    """Campo declarado `catalog_available_source_count` de `SoftwareApp`.
    """
    catalog_review_source_count: Mapped[int] = mapped_column(
        Integer,
        default=0,
        server_default="0",
        nullable=False,
    )
    """Campo declarado `catalog_review_source_count` de `SoftwareApp`.
    """
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
    """Campo declarado `catalog_status` de `SoftwareApp`.
    """

    sources: Mapped[list[DownloadSource]] = relationship(
        back_populates="software_app", cascade="all, delete-orphan"
    )
    """Campo declarado `sources` de `SoftwareApp`.
    """
    tags: Mapped[list[SoftwareAppTag]] = relationship(
        back_populates="software_app", cascade="all, delete-orphan"
    )
    """Campo declarado `tags` de `SoftwareApp`.
    """

    __table_args__ = (Index("ix_software_apps_status_name", "app_status", "normalized_name"),)
    """Campo declarado `__table_args__` de `SoftwareApp`.
    """


class DownloadSource(Base, TimestampMixin):
    """Representa el componente `DownloadSource`.
    """
    __tablename__ = "download_sources"
    """Campo declarado `__tablename__` de `DownloadSource`.
    """

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    """Campo declarado `id` de `DownloadSource`.
    """
    software_app_id: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("software_apps.id"), nullable=False
    )
    """Campo declarado `software_app_id` de `DownloadSource`.
    """
    operating_system: Mapped[str] = mapped_column(String(32), default="windows", nullable=False)
    """Campo declarado `operating_system` de `DownloadSource`.
    """
    architecture: Mapped[str] = mapped_column(String(32), default="UNKNOWN", nullable=False)
    """Campo declarado `architecture` de `DownloadSource`.
    """
    initial_url: Mapped[str | None] = mapped_column(String(2048))
    """Campo declarado `initial_url` de `DownloadSource`.
    """
    resolver_type: Mapped[str] = mapped_column(String(50), default="generic_http", nullable=False)
    """Campo declarado `resolver_type` de `DownloadSource`.
    """
    resolver_config: Mapped[dict | None] = mapped_column(JSON)
    """Campo declarado `resolver_config` de `DownloadSource`.
    """
    resolution_status: Mapped[str] = mapped_column(
        String(32), default=ResolutionStatus.MISSING.value, index=True, nullable=False
    )
    """Campo declarado `resolution_status` de `DownloadSource`.
    """
    validation_status: Mapped[str] = mapped_column(
        String(32), default=ValidationStatus.UNCHECKED.value, index=True, nullable=False
    )
    """Campo declarado `validation_status` de `DownloadSource`.
    """
    version: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    """Campo declarado `version` de `DownloadSource`.
    """
    catalog_downloadable_count: Mapped[int] = mapped_column(
        Integer,
        default=0,
        server_default="0",
        nullable=False,
    )
    """Campo declarado `catalog_downloadable_count` de `DownloadSource`.
    """
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
    """Campo declarado `catalog_available` de `DownloadSource`.
    """

    software_app: Mapped[SoftwareApp] = relationship(back_populates="sources")
    """Campo declarado `software_app` de `DownloadSource`.
    """
    resolved_sources: Mapped[list[ResolvedSource]] = relationship(
        back_populates="source", cascade="all, delete-orphan"
    )
    """Campo declarado `resolved_sources` de `DownloadSource`.
    """

    __table_args__ = (
        Index(
            "ix_download_sources_app_platform",
            "software_app_id",
            "operating_system",
            "architecture",
            "resolution_status",
        ),
    )
    """Campo declarado `__table_args__` de `DownloadSource`.
    """


class SoftwareAppTag(Base):
    """Representa el componente `SoftwareAppTag`.
    """
    __tablename__ = "software_app_tags"
    """Campo declarado `__tablename__` de `SoftwareAppTag`.
    """

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    """Campo declarado `id` de `SoftwareAppTag`.
    """
    software_app_id: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("software_apps.id"), nullable=False
    )
    """Campo declarado `software_app_id` de `SoftwareAppTag`.
    """
    tag: Mapped[str] = mapped_column(String(120), nullable=False)
    """Campo declarado `tag` de `SoftwareAppTag`.
    """
    normalized_tag: Mapped[str] = mapped_column(String(120), index=True, nullable=False)
    """Campo declarado `normalized_tag` de `SoftwareAppTag`.
    """
    source: Mapped[str] = mapped_column(String(50), default="winstall", nullable=False)
    """Campo declarado `source` de `SoftwareAppTag`.
    """
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    """Campo declarado `created_at` de `SoftwareAppTag`.
    """

    software_app: Mapped[SoftwareApp] = relationship(back_populates="tags")
    """Campo declarado `software_app` de `SoftwareAppTag`.
    """

    __table_args__ = (
        UniqueConstraint("software_app_id", "normalized_tag", name="uq_software_app_tag"),
        Index("ix_software_app_tags_app", "software_app_id"),
    )
    """Campo declarado `__table_args__` de `SoftwareAppTag`.
    """


class CatalogCounter(Base):
    """Representa el componente `CatalogCounter`.
    """

    __tablename__ = "catalog_counters"
    """Campo declarado `__tablename__` de `CatalogCounter`.
    """

    id: Mapped[int] = mapped_column(Integer, primary_key=True, default=1)
    """Campo declarado `id` de `CatalogCounter`.
    """
    total_count: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    """Campo declarado `total_count` de `CatalogCounter`.
    """
    available_count: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    """Campo declarado `available_count` de `CatalogCounter`.
    """
    review_count: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    """Campo declarado `review_count` de `CatalogCounter`.
    """
    missing_count: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    """Campo declarado `missing_count` de `CatalogCounter`.
    """
    version: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    """Campo declarado `version` de `CatalogCounter`.
    """
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    """Campo declarado `updated_at` de `CatalogCounter`.
    """

    __table_args__ = (
        CheckConstraint("id = 1", name="ck_catalog_counters_singleton"),
        CheckConstraint(
            "total_count = available_count + review_count + missing_count",
            name="ck_catalog_counters_partition",
        ),
    )
    """Campo declarado `__table_args__` de `CatalogCounter`.
    """


class ResolvedSource(Base):
    """Representa el componente `ResolvedSource`.
    """
    __tablename__ = "resolved_sources"
    """Campo declarado `__tablename__` de `ResolvedSource`.
    """

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    """Campo declarado `id` de `ResolvedSource`.
    """
    download_source_id: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("download_sources.id"), nullable=False
    )
    """Campo declarado `download_source_id` de `ResolvedSource`.
    """
    resolved_url_encrypted: Mapped[str] = mapped_column(Text, nullable=False)
    """Campo declarado `resolved_url_encrypted` de `ResolvedSource`.
    """
    final_domain: Mapped[str] = mapped_column(String(253), nullable=False)
    """Campo declarado `final_domain` de `ResolvedSource`.
    """
    filename: Mapped[str | None] = mapped_column(String(255))
    """Campo declarado `filename` de `ResolvedSource`.
    """
    extension: Mapped[str | None] = mapped_column(String(20))
    """Campo declarado `extension` de `ResolvedSource`.
    """
    content_type: Mapped[str | None] = mapped_column(String(180))
    """Campo declarado `content_type` de `ResolvedSource`.
    """
    size_bytes: Mapped[int | None] = mapped_column(BigInteger)
    """Campo declarado `size_bytes` de `ResolvedSource`.
    """
    version: Mapped[str | None] = mapped_column(String(100))
    """Campo declarado `version` de `ResolvedSource`.
    """
    release_rank: Mapped[int | None] = mapped_column(Integer)
    """Campo declarado `release_rank` de `ResolvedSource`.
    """
    is_latest: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    """Campo declarado `is_latest` de `ResolvedSource`.
    """
    version_status: Mapped[str | None] = mapped_column(String(32))
    """Campo declarado `version_status` de `ResolvedSource`.
    """
    score: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Campo declarado `score` de `ResolvedSource`.
    """
    status: Mapped[str] = mapped_column(String(32), index=True, nullable=False)
    """Campo declarado `status` de `ResolvedSource`.
    """
    validation_status: Mapped[str] = mapped_column(String(32), index=True, nullable=False)
    """Campo declarado `validation_status` de `ResolvedSource`.
    """
    checked_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    """Campo declarado `checked_at` de `ResolvedSource`.
    """
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    """Campo declarado `expires_at` de `ResolvedSource`.
    """
    metadata_json: Mapped[dict | None] = mapped_column(JSON)
    """Campo declarado `metadata_json` de `ResolvedSource`.
    """
    artifact_fingerprint: Mapped[str | None] = mapped_column(String(64), index=True)
    """Huella estable del artefacto para evitar duplicados entre revalidaciones."""
    # Alembic 0010 es responsable de la columna física generada. Mapearla únicamente
    # como expresión de consulta mantiene intacto el esquema de pruebas de SQLite.
    catalog_downloadable: Mapped[bool | None] = query_expression()
    """Campo declarado `catalog_downloadable` de `ResolvedSource`.
    """

    source: Mapped[DownloadSource] = relationship(back_populates="resolved_sources")
    """Campo declarado `source` de `ResolvedSource`.
    """

    __table_args__ = (
        Index("ix_resolved_sources_source_expiry", "download_source_id", "expires_at"),
        Index("ix_resolved_sources_status_expiry", "status", "expires_at"),
    )
    """Campo declarado `__table_args__` de `ResolvedSource`.
    """


class ResolverLog(Base):
    """Representa el componente `ResolverLog`.
    """
    __tablename__ = "resolver_logs"
    """Campo declarado `__tablename__` de `ResolverLog`.
    """

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    """Campo declarado `id` de `ResolverLog`.
    """
    download_source_id: Mapped[uuid.UUID | None] = mapped_column(
        GUID(), ForeignKey("download_sources.id")
    )
    """Campo declarado `download_source_id` de `ResolverLog`.
    """
    phase: Mapped[str] = mapped_column(String(50), nullable=False)
    """Campo declarado `phase` de `ResolverLog`.
    """
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    """Campo declarado `status` de `ResolverLog`.
    """
    message: Mapped[str | None] = mapped_column(String(2000))
    """Campo declarado `message` de `ResolverLog`.
    """
    safe_metadata: Mapped[dict | None] = mapped_column(JSON)
    """Campo declarado `safe_metadata` de `ResolverLog`.
    """
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    """Campo declarado `created_at` de `ResolverLog`.
    """


class ScrapeRun(Base):
    """Representa el componente `ScrapeRun`.
    """
    __tablename__ = "scrape_runs"
    """Campo declarado `__tablename__` de `ScrapeRun`.
    """

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    """Campo declarado `id` de `ScrapeRun`.
    """
    active_lock: Mapped[int | None] = mapped_column(Integer, unique=True)
    """Campo declarado `active_lock` de `ScrapeRun`.
    """
    status: Mapped[str] = mapped_column(
        String(32), default=ScrapeRunStatus.RUNNING.value, index=True, nullable=False
    )
    """Campo declarado `status` de `ScrapeRun`.
    """
    scope: Mapped[str] = mapped_column(
        String(32), default=ScrapeScope.INCREMENTAL.value, index=True, nullable=False
    )
    """Scope solicitado para esta ejecución."""
    request_id: Mapped[uuid.UUID | None] = mapped_column(GUID(), index=True)
    """Solicitud durable que originó la ejecución."""
    target_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Número de aplicaciones de la instantánea objetivo."""
    target_app_ids_json: Mapped[list[str] | None] = mapped_column(JSON)
    """Manifest inmutable de UUID locales cuando el scope parte del catálogo."""
    target_winstall_ids_json: Mapped[list[str] | None] = mapped_column(JSON)
    """Manifest inmutable de identificadores Winstall procesables."""
    started_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    """Campo declarado `started_at` de `ScrapeRun`.
    """
    heartbeat_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    """Campo declarado `heartbeat_at` de `ScrapeRun`.
    """
    finished_at: Mapped[datetime | None] = mapped_column(DateTime)
    """Campo declarado `finished_at` de `ScrapeRun`.
    """
    apps_discovered: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Campo declarado `apps_discovered` de `ScrapeRun`.
    """
    apps_resolved: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Campo declarado `apps_resolved` de `ScrapeRun`.
    """
    apps_failed: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Campo declarado `apps_failed` de `ScrapeRun`.
    """
    apps_skipped: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Campo declarado `apps_skipped` de `ScrapeRun`.
    """
    apps_confirmed_missing: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Ausencias respaldadas por una verificación activa."""
    apps_needs_review: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Casos que requieren comprobación humana o evidencia adicional."""
    apps_transient_failed: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Fallos temporales que conservaron el estado publicado anterior."""
    apps_skipped_unchanged: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Aplicaciones omitidas porque su huella no cambió."""
    worker_id: Mapped[str] = mapped_column(String(120), nullable=False)
    """Campo declarado `worker_id` de `ScrapeRun`.
    """
    error_summary: Mapped[str | None] = mapped_column(String(1000))
    """Campo declarado `error_summary` de `ScrapeRun`.
    """
    current_package_id: Mapped[str | None] = mapped_column(String(180))
    """Campo declarado `current_package_id` de `ScrapeRun`.
    """
    current_app_name: Mapped[str | None] = mapped_column(String(180))
    """Campo declarado `current_app_name` de `ScrapeRun`.
    """
    current_phase: Mapped[str | None] = mapped_column(String(80))
    """Campo declarado `current_phase` de `ScrapeRun`.
    """
    stop_requested: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    """Campo declarado `stop_requested` de `ScrapeRun`.
    """
    paused_at: Mapped[datetime | None] = mapped_column(DateTime)
    """Campo declarado `paused_at` de `ScrapeRun`.
    """

    __table_args__ = (
        Index("ix_scrape_runs_started_at", "started_at"),
        Index("ix_scrape_runs_status_started_at", "status", "started_at"),
    )
    """Los historiales recientes se resuelven por índice sin filesort."""


class ScraperCommand(Base):
    """Representa el componente `ScraperCommand`.
    """
    __tablename__ = "scraper_commands"
    """Campo declarado `__tablename__` de `ScraperCommand`.
    """

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    """Campo declarado `id` de `ScraperCommand`.
    """
    command: Mapped[str] = mapped_column(String(32), nullable=False)
    """Campo declarado `command` de `ScraperCommand`.
    """
    scope: Mapped[str | None] = mapped_column(String(32), index=True)
    """Scope de una solicitud ``run_once``; nulo para controles históricos."""
    app_ids_json: Mapped[list[str] | None] = mapped_column(JSON)
    """Selección explícita, limitada y validada por la API administrativa."""
    run_id: Mapped[uuid.UUID | None] = mapped_column(GUID(), index=True)
    """Ejecución reclamada por el scheduler."""
    status: Mapped[str] = mapped_column(String(32), default="pending", index=True, nullable=False)
    """Campo declarado `status` de `ScraperCommand`.
    """
    message: Mapped[str | None] = mapped_column(String(1000))
    """Campo declarado `message` de `ScraperCommand`.
    """
    created_by: Mapped[str] = mapped_column(String(180), nullable=False)
    """Campo declarado `created_by` de `ScraperCommand`.
    """
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    """Campo declarado `created_at` de `ScraperCommand`.
    """
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime)
    """Campo declarado `consumed_at` de `ScraperCommand`.
    """
    started_at: Mapped[datetime | None] = mapped_column(DateTime)
    """Instante en que el scheduler reclamó la solicitud."""

    __table_args__ = (Index("ix_scraper_commands_status_created", "status", "created_at"),)
    """Campo declarado `__table_args__` de `ScraperCommand`.
    """


class InstallerAbsenceVerification(Base, TimestampMixin):
    """Evidencia auditable de que una aplicación no ofrece un binario validable."""

    __tablename__ = "installer_absence_verifications"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    software_app_id: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("software_apps.id", ondelete="CASCADE"), nullable=False
    )
    status: Mapped[str] = mapped_column(
        String(32),
        default=AbsenceVerificationStatus.ACTIVE.value,
        index=True,
        nullable=False,
    )
    reason_code: Mapped[str] = mapped_column(String(80), nullable=False)
    notes: Mapped[str | None] = mapped_column(String(2000))
    checked_urls_json: Mapped[list[str]] = mapped_column(JSON, default=list, nullable=False)
    evidence_json: Mapped[dict | None] = mapped_column(JSON)
    verified_by: Mapped[str] = mapped_column(String(180), nullable=False)
    verified_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    app_version: Mapped[int] = mapped_column(BigInteger, nullable=False)
    winstall_latest_version: Mapped[str | None] = mapped_column(String(100))
    winstall_summary_fingerprint: Mapped[str | None] = mapped_column(String(64))
    winstall_detail_fingerprint: Mapped[str | None] = mapped_column(String(64))
    official_url_fingerprint: Mapped[str | None] = mapped_column(String(64))
    invalidated_at: Mapped[datetime | None] = mapped_column(DateTime)
    invalidation_reason: Mapped[str | None] = mapped_column(String(180))

    __table_args__ = (
        Index(
            "ix_installer_absence_verifications_app_status",
            "software_app_id",
            "status",
            "verified_at",
        ),
    )


class ManualInstallerInspection(Base, TimestampMixin):
    """Representa el componente `ManualInstallerInspection`.
    """

    __tablename__ = "manual_installer_inspections"
    """Campo declarado `__tablename__` de `ManualInstallerInspection`.
    """

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    """Campo declarado `id` de `ManualInstallerInspection`.
    """
    software_app_id: Mapped[uuid.UUID] = mapped_column(
        GUID(),
        ForeignKey("software_apps.id", ondelete="CASCADE"),
        nullable=False,
    )
    """Campo declarado `software_app_id` de `ManualInstallerInspection`.
    """
    status: Mapped[str] = mapped_column(String(16), default="queued", nullable=False)
    """Campo declarado `status` de `ManualInstallerInspection`.
    """
    phase: Mapped[str] = mapped_column(String(32), default="queued", nullable=False)
    """Campo declarado `phase` de `ManualInstallerInspection`.
    """
    captured_app_version: Mapped[int] = mapped_column(BigInteger, nullable=False)
    """Campo declarado `captured_app_version` de `ManualInstallerInspection`.
    """
    input_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    """Campo declarado `input_hash` de `ManualInstallerInspection`.
    """
    installer_url_encrypted: Mapped[str | None] = mapped_column(Text)
    """Campo declarado `installer_url_encrypted` de `ManualInstallerInspection`.
    """
    windows_installer_url_encrypted: Mapped[str | None] = mapped_column(Text)
    """Campo declarado `windows_installer_url_encrypted` de `ManualInstallerInspection`.
    """
    macos_installer_url_encrypted: Mapped[str | None] = mapped_column(Text)
    """Campo declarado `macos_installer_url_encrypted` de `ManualInstallerInspection`.
    """
    linux_installer_url_encrypted: Mapped[str | None] = mapped_column(Text)
    """Campo declarado `linux_installer_url_encrypted` de `ManualInstallerInspection`.
    """
    source_page_url_encrypted: Mapped[str] = mapped_column(Text, nullable=False)
    """Campo declarado `source_page_url_encrypted` de `ManualInstallerInspection`.
    """
    result_json: Mapped[dict | None] = mapped_column(JSON)
    """Campo declarado `result_json` de `ManualInstallerInspection`.
    """
    warnings_json: Mapped[list[str]] = mapped_column(JSON, default=list, nullable=False)
    """Campo declarado `warnings_json` de `ManualInstallerInspection`.
    """
    error_code: Mapped[str | None] = mapped_column(String(120))
    """Campo declarado `error_code` de `ManualInstallerInspection`.
    """
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    """Campo declarado `expires_at` de `ManualInstallerInspection`.
    """
    applied_at: Mapped[datetime | None] = mapped_column(DateTime)
    """Campo declarado `applied_at` de `ManualInstallerInspection`.
    """
    applied_app_version: Mapped[int | None] = mapped_column(BigInteger)
    """Campo declarado `applied_app_version` de `ManualInstallerInspection`.
    """
    source_ref: Mapped[uuid.UUID | None] = mapped_column(
        GUID(),
        ForeignKey("resolved_sources.id", ondelete="SET NULL"),
    )
    """Campo declarado `source_ref` de `ManualInstallerInspection`.
    """

    __table_args__ = (
        Index(
            "ix_manual_installer_inspections_app_status",
            "software_app_id",
            "status",
            "created_at",
        ),
        Index("ix_manual_installer_inspections_expires", "expires_at"),
    )
    """Campo declarado `__table_args__` de `ManualInstallerInspection`.
    """


class WebsiteAppDiscovery(Base, TimestampMixin):
    """Representa el componente `WebsiteAppDiscovery`.
    """

    __tablename__ = "website_app_discoveries"
    """Campo declarado `__tablename__` de `WebsiteAppDiscovery`.
    """

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    """Campo declarado `id` de `WebsiteAppDiscovery`.
    """
    status: Mapped[str] = mapped_column(String(16), default="queued", nullable=False)
    """Campo declarado `status` de `WebsiteAppDiscovery`.
    """
    phase: Mapped[str] = mapped_column(String(32), default="queued", nullable=False)
    """Campo declarado `phase` de `WebsiteAppDiscovery`.
    """
    input_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    """Campo declarado `input_hash` de `WebsiteAppDiscovery`.
    """
    official_url_encrypted: Mapped[str] = mapped_column(Text, nullable=False)
    """Campo declarado `official_url_encrypted` de `WebsiteAppDiscovery`.
    """
    windows_installer_url_encrypted: Mapped[str | None] = mapped_column(Text)
    """Campo declarado `windows_installer_url_encrypted` de `WebsiteAppDiscovery`.
    """
    macos_installer_url_encrypted: Mapped[str | None] = mapped_column(Text)
    """Campo declarado `macos_installer_url_encrypted` de `WebsiteAppDiscovery`.
    """
    linux_installer_url_encrypted: Mapped[str | None] = mapped_column(Text)
    """Campo declarado `linux_installer_url_encrypted` de `WebsiteAppDiscovery`.
    """
    result_json: Mapped[dict | None] = mapped_column(JSON)
    """Campo declarado `result_json` de `WebsiteAppDiscovery`.
    """
    warnings_json: Mapped[list[str]] = mapped_column(JSON, default=list, nullable=False)
    """Campo declarado `warnings_json` de `WebsiteAppDiscovery`.
    """
    error_code: Mapped[str | None] = mapped_column(String(120))
    """Campo declarado `error_code` de `WebsiteAppDiscovery`.
    """
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    """Campo declarado `expires_at` de `WebsiteAppDiscovery`.
    """
    applied_at: Mapped[datetime | None] = mapped_column(DateTime)
    """Campo declarado `applied_at` de `WebsiteAppDiscovery`.
    """
    applied_app_id: Mapped[uuid.UUID | None] = mapped_column(
        GUID(),
        ForeignKey("software_apps.id", ondelete="SET NULL"),
    )
    """Campo declarado `applied_app_id` de `WebsiteAppDiscovery`.
    """

    installers: Mapped[list[WebsiteAppDiscoveryInstaller]] = relationship(
        back_populates="discovery",
        cascade="all, delete-orphan",
    )
    """Campo declarado `installers` de `WebsiteAppDiscovery`.
    """

    __table_args__ = (
        Index(
            "ix_website_app_discoveries_hash_status",
            "input_hash",
            "status",
            "created_at",
        ),
        Index("ix_website_app_discoveries_expires", "expires_at"),
    )
    """Campo declarado `__table_args__` de `WebsiteAppDiscovery`.
    """


class WebsiteAppDiscoveryInstaller(Base, TimestampMixin):
    """Representa el componente `WebsiteAppDiscoveryInstaller`.
    """

    __tablename__ = "website_app_discovery_installers"
    """Campo declarado `__tablename__` de `WebsiteAppDiscoveryInstaller`.
    """

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    """Campo declarado `id` de `WebsiteAppDiscoveryInstaller`.
    """
    discovery_id: Mapped[uuid.UUID] = mapped_column(
        GUID(),
        ForeignKey("website_app_discoveries.id", ondelete="CASCADE"),
        nullable=False,
    )
    """Campo declarado `discovery_id` de `WebsiteAppDiscoveryInstaller`.
    """
    installer_url_encrypted: Mapped[str] = mapped_column(Text, nullable=False)
    """Campo declarado `installer_url_encrypted` de `WebsiteAppDiscoveryInstaller`.
    """
    final_domain: Mapped[str | None] = mapped_column(String(255))
    """Campo declarado `final_domain` de `WebsiteAppDiscoveryInstaller`.
    """
    filename: Mapped[str | None] = mapped_column(String(255))
    """Campo declarado `filename` de `WebsiteAppDiscoveryInstaller`.
    """
    extension: Mapped[str | None] = mapped_column(String(32))
    """Campo declarado `extension` de `WebsiteAppDiscoveryInstaller`.
    """
    content_type: Mapped[str | None] = mapped_column(String(255))
    """Campo declarado `content_type` de `WebsiteAppDiscoveryInstaller`.
    """
    size_bytes: Mapped[int | None] = mapped_column(BigInteger)
    """Campo declarado `size_bytes` de `WebsiteAppDiscoveryInstaller`.
    """
    version: Mapped[str | None] = mapped_column(String(100))
    """Campo declarado `version` de `WebsiteAppDiscoveryInstaller`.
    """
    operating_system: Mapped[str] = mapped_column(String(32), nullable=False)
    """Campo declarado `operating_system` de `WebsiteAppDiscoveryInstaller`.
    """
    architecture: Mapped[str] = mapped_column(String(32), nullable=False)
    """Campo declarado `architecture` de `WebsiteAppDiscoveryInstaller`.
    """
    score: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Campo declarado `score` de `WebsiteAppDiscoveryInstaller`.
    """

    discovery: Mapped[WebsiteAppDiscovery] = relationship(back_populates="installers")
    """Campo declarado `discovery` de `WebsiteAppDiscoveryInstaller`.
    """

    __table_args__ = (
        Index(
            "ix_website_app_discovery_installers_discovery",
            "discovery_id",
            "operating_system",
            "architecture",
        ),
    )
    """Campo declarado `__table_args__` de `WebsiteAppDiscoveryInstaller`.
    """


class ScraperWorkItem(Base, TimestampMixin):
    """Representa un elemento de `ScraperWork`.
    """
    __tablename__ = "scraper_work_items"
    """Campo declarado `__tablename__` de `ScraperWorkItem`.
    """

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    """Campo declarado `id` de `ScraperWorkItem`.
    """
    run_id: Mapped[uuid.UUID | None] = mapped_column(GUID(), ForeignKey("scrape_runs.id"))
    """Campo declarado `run_id` de `ScraperWorkItem`.
    """
    queue: Mapped[str] = mapped_column(String(32), nullable=False)
    """Campo declarado `queue` de `ScraperWorkItem`.
    """
    status: Mapped[str] = mapped_column(String(32), default="queued", nullable=False)
    """Campo declarado `status` de `ScraperWorkItem`.
    """
    package_id: Mapped[str] = mapped_column(String(180), nullable=False)
    """Campo declarado `package_id` de `ScraperWorkItem`.
    """
    app_name: Mapped[str | None] = mapped_column(String(180))
    """Campo declarado `app_name` de `ScraperWorkItem`.
    """
    payload_json: Mapped[dict | None] = mapped_column(JSON)
    """Campo declarado `payload_json` de `ScraperWorkItem`.
    """
    priority: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Campo declarado `priority` de `ScraperWorkItem`.
    """
    attempts: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Campo declarado `attempts` de `ScraperWorkItem`.
    """
    lease_owner: Mapped[str | None] = mapped_column(String(120))
    """Campo declarado `lease_owner` de `ScraperWorkItem`.
    """
    lease_expires_at: Mapped[datetime | None] = mapped_column(DateTime)
    """Campo declarado `lease_expires_at` de `ScraperWorkItem`.
    """
    available_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    """Campo declarado `available_at` de `ScraperWorkItem`.
    """
    last_error: Mapped[str | None] = mapped_column(String(1000))
    """Campo declarado `last_error` de `ScraperWorkItem`.
    """

    __table_args__ = (
        UniqueConstraint("queue", "package_id", name="uq_scraper_work_queue_package"),
        Index("ix_scraper_work_queue_status_available", "queue", "status", "available_at"),
        Index("ix_scraper_work_lease", "status", "lease_expires_at"),
    )
    """Campo declarado `__table_args__` de `ScraperWorkItem`.
    """


class ScraperWorkerSnapshot(Base):
    """Representa el componente `ScraperWorkerSnapshot`.
    """
    __tablename__ = "scraper_worker_snapshots"
    """Campo declarado `__tablename__` de `ScraperWorkerSnapshot`.
    """

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    """Campo declarado `id` de `ScraperWorkerSnapshot`.
    """
    run_id: Mapped[uuid.UUID | None] = mapped_column(GUID(), ForeignKey("scrape_runs.id"))
    """Campo declarado `run_id` de `ScraperWorkerSnapshot`.
    """
    worker_id: Mapped[str] = mapped_column(String(120), nullable=False)
    """Campo declarado `worker_id` de `ScraperWorkerSnapshot`.
    """
    stage: Mapped[str] = mapped_column(String(32), nullable=False)
    """Campo declarado `stage` de `ScraperWorkerSnapshot`.
    """
    package_id: Mapped[str | None] = mapped_column(String(180))
    """Campo declarado `package_id` de `ScraperWorkerSnapshot`.
    """
    app_name: Mapped[str | None] = mapped_column(String(180))
    """Campo declarado `app_name` de `ScraperWorkerSnapshot`.
    """
    url: Mapped[str | None] = mapped_column(String(2048))
    """Campo declarado `url` de `ScraperWorkerSnapshot`.
    """
    html: Mapped[str | None] = mapped_column(Text)
    """Campo declarado `html` de `ScraperWorkerSnapshot`.
    """
    captured_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    """Campo declarado `captured_at` de `ScraperWorkerSnapshot`.
    """
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    """Campo declarado `expires_at` de `ScraperWorkerSnapshot`.
    """

    __table_args__ = (
        Index("ix_scraper_snapshots_stage_captured", "stage", "captured_at"),
        Index("ix_scraper_snapshots_expires", "expires_at"),
    )
    """Campo declarado `__table_args__` de `ScraperWorkerSnapshot`.
    """


class ScraperMetricSnapshot(Base):
    """Representa el componente `ScraperMetricSnapshot`.
    """
    __tablename__ = "scraper_metric_snapshots"
    """Campo declarado `__tablename__` de `ScraperMetricSnapshot`.
    """

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)
    """Campo declarado `id` de `ScraperMetricSnapshot`.
    """
    run_id: Mapped[uuid.UUID | None] = mapped_column(GUID(), ForeignKey("scrape_runs.id"))
    """Campo declarado `run_id` de `ScraperMetricSnapshot`.
    """
    available: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Campo declarado `available` de `ScraperMetricSnapshot`.
    """
    review: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Campo declarado `review` de `ScraperMetricSnapshot`.
    """
    unavailable: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Campo declarado `unavailable` de `ScraperMetricSnapshot`.
    """
    queued_searcher_filter: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Campo declarado `queued_searcher_filter` de `ScraperMetricSnapshot`.
    """
    queued_filter_scraper: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Campo declarado `queued_filter_scraper` de `ScraperMetricSnapshot`.
    """
    queued_scraper_so_filter: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Campo declarado `queued_scraper_so_filter` de `ScraperMetricSnapshot`.
    """
    queued_so_filter_descriptor: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Campo declarado `queued_so_filter_descriptor` de `ScraperMetricSnapshot`.
    """
    captured_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    """Campo declarado `captured_at` de `ScraperMetricSnapshot`.
    """

    __table_args__ = (Index("ix_scraper_metric_snapshots_captured", "captured_at"),)
    """Campo declarado `__table_args__` de `ScraperMetricSnapshot`.
    """


class ScraperRateLimit(Base):
    """Representa el componente `ScraperRateLimit`.
    """
    __tablename__ = "scraper_rate_limits"
    """Campo declarado `__tablename__` de `ScraperRateLimit`.
    """

    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    """Campo declarado `key` de `ScraperRateLimit`.
    """
    next_allowed_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    """Campo declarado `next_allowed_at` de `ScraperRateLimit`.
    """
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    """Campo declarado `updated_at` de `ScraperRateLimit`.
    """
