"""Declara tablas del catálogo, evidencias, colas y seguimiento del scraper sobre la misma base
SQLAlchemy.
Los instantes se persisten como UTC sin tzinfo; cada repositorio conserva la sesión y el
límite transaccional de su llamador.

See Also:
    app.db.base.Base: Registro declarativo compartido.
    app.repositories.catalog.CatalogRepository: Compone las operaciones del catálogo.
    app.repositories.pipeline.PipelineRepository: Reserva y finaliza trabajo persistente.
"""
import hashlib
import json
import uuid
from datetime import datetime
from typing import Any, Literal

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
    """Añade fechas de creación y última modificación calculadas en UTC por SQLAlchemy.

    Attributes:
        created_at: Instante de inserción.
        updated_at: Instante de inserción o de la última actualización ORM.
    """
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)

    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=utc_now, onupdate=utc_now, nullable=False
    )


def _fallback_artifact_fingerprint(context: Any) -> str:
    """Calcula una huella estable cuando un consumidor ORM antiguo no la proporciona.

    Las rutas de resolución actuales calculan la huella completa antes de persistirla. Este
    valor de respaldo solo evita insertar una fila nula durante la transición; usa los campos
    persistidos que definen la identidad del artefacto y excluye la URL cifrada, que puede
    cambiar al renovar credenciales.
    """
    params = context.get_current_parameters()
    metadata = params.get("metadata_json")
    if not isinstance(metadata, dict):
        metadata = {}
    payload = {
        "source": str(params.get("download_source_id") or ""),
        "domain": str(params.get("final_domain") or "").lower(),
        "filename": str(params.get("filename") or "").lower(),
        "extension": str(params.get("extension") or "").lower(),
        "size": params.get("size_bytes"),
        "version": params.get("version"),
        "sha256": metadata.get("sha256") or metadata.get("expected_sha256"),
        "operating_system": metadata.get("operating_system"),
        "architecture": metadata.get("architecture"),
    }
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()



class SoftwareApp(Base, TimestampMixin):
    """Mantiene identidad, metadatos editoriales y proyección de disponibilidad de una
    aplicación.
    Las fuentes y etiquetas pertenecen a la aplicación y se eliminan como huérfanos al
    retirarlas de sus relaciones.

    Attributes:
        id, winstall_id, slug: UUID interno e identidades únicas del proveedor y de ruta
            pública.
        name, normalized_name, publisher: Nombre de presentación, forma normalizada para
            búsqueda y editor.
        description, long_description: Texto breve de catálogo y descripción ampliada.
        long_description_language, long_description_status: Idioma de la descripción ampliada
            y estado de su enriquecimiento.
        long_description_source, long_description_model, long_description_generated_at:
            Procedencia, modelo e instante de generación.
        long_description_input_hash, long_description_error: Huella de entradas para detectar
            cambios y último fallo resumido.
        icon_url, official_url, latest_version: Icono, página oficial y versión reciente
            publicada.
        winstall_latest_version, winstall_updated_at: Versión y fecha comunicadas por
            Winstall.
        winstall_summary_fingerprint, winstall_detail_fingerprint: Huellas que detectan
            cambios del resumen y detalle del proveedor.
        app_status, metadata_json: Estado administrativo y evidencia adicional del origen o
            edición manual.
        operating_systems, operating_systems_updated_at: Plataformas detectadas y última
            comprobación de estas.
        version: Contador para impedir aplicar actualizaciones sobre una edición concurrente.
        catalog_available_source_count, catalog_review_source_count: Número de fuentes
            disponibles y pendientes de revisión.
        catalog_status, catalog_review_priority: Columnas calculadas de disponibilidad y
            prioridad de revisión; una aplicación inactiva no tiene estado público.
        sources, tags: Fuentes y etiquetas asociadas, cargadas según las necesidades de la
            consulta.
    """
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

    catalog_review_priority: Mapped[bool] = mapped_column(
        Boolean,
        Computed(
            "CASE WHEN catalog_status = 'review' THEN 1 ELSE 0 END",
            persisted=True,
        ),
    )
    """Prioridad persistente que permite relegar revisión sin un `filesort` global.
    """

    sources: Mapped[list[DownloadSource]] = relationship(
        back_populates="software_app", cascade="all, delete-orphan"
    )

    tags: Mapped[list[SoftwareAppTag]] = relationship(
        back_populates="software_app", cascade="all, delete-orphan"
    )


    __table_args__ = (
        Index("ix_software_apps_status_name", "app_status", "normalized_name"),
        Index(
            "ix_software_apps_os_refresh",
            "app_status",
            "operating_systems_updated_at",
            "id",
        ),
    )



class DownloadSource(Base, TimestampMixin):
    """Agrupa la configuración de resolución y los artefactos comprobados de una aplicación para
    una plataforma y arquitectura.

    Attributes:
        id, software_app_id: Identidad de fuente y aplicación propietaria.
        operating_system, architecture: Contexto de los instaladores de esta fuente.
        initial_url, resolver_type, resolver_config: Punto de partida y estrategia con sus
            parámetros de resolución.
        resolution_status, validation_status, version: Resultados actuales de resolución y
            validación y contador de modificaciones.
        catalog_downloadable_count, catalog_available: Cantidad de artefactos descargables y
            disponibilidad calculada de la fuente.
        software_app, resolved_sources: Aplicación propietaria y resoluciones concretas,
            eliminadas como huérfanos cuando corresponde.
    """
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
        String(32),
        default=ResolutionStatus.REQUIRES_MANUAL_REVIEW.value,
        index=True,
        nullable=False,
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
    """Asocia una etiqueta normalizada con su aplicación sin permitir duplicados por aplicación y
    forma normalizada.

    Attributes:
        id, software_app_id: Identidades de asociación y aplicación.
        tag, normalized_tag, source: Texto mostrado, clave normalizada y proveedor de la
            etiqueta.
        created_at, software_app: Fecha de alta y relación con la aplicación.
    """
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
    """Mantiene una única fila de totales del catálogo y exige que disponibles, revisión y
    ausentes formen una partición del total.

    Attributes:
        id: Clave singleton, siempre uno.
        total_count, available_count, review_count, missing_count: Recuentos materializados de
            las aplicaciones públicas.
        version, updated_at: Revisión e instante de actualización de los contadores.
    """

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
    """Identifica un instalador concreto y conserva su URL protegida, metadatos, evidencia
    técnica y vigencia.
    Su UUID es la referencia exacta que debe respetarse durante una descarga.

    Attributes:
        id, download_source_id, source: Identidad del artefacto y fuente propietaria.
        resolved_url_encrypted, final_domain: URL Fernet privada y dominio final publicable.
        filename, extension, content_type, size_bytes: Nombre, formato, MIME y tamaño
            observado en bytes; None expresa dato desconocido.
        version, release_rank, is_latest, version_status: Versión de software y posición
            respecto de las publicaciones recientes.
        score, status, validation_status: Preferencia y resultados de resolución y
            comprobación.
        checked_at, expires_at: Última comprobación y fin de vigencia.
        metadata_json, artifact_fingerprint: Evidencias de validación y huella para reconocer
            el mismo artefacto.
        catalog_downloadable: Expresión cargada por consultas de catálogo; no es una columna
            ORM ordinaria.
        install_profile: Receta Linux vinculada, cargada junto a la resolución y eliminada con
            ella.

    See Also:
        app.core.url_protector.UrlProtector: Protege y recupera la URL privada.
        app.application.source_resolution.resolve_source: Revalida la referencia exacta para
            el worker.
    """
    __tablename__ = "resolved_sources"
    install_profile: Mapped[LinuxInstallProfileRow | None] = relationship(
        lazy="joined", uselist=False, cascade="all, delete-orphan"
    )


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

    artifact_fingerprint: Mapped[str] = mapped_column(
        String(64), index=True, nullable=False, default=_fallback_artifact_fingerprint
    )
    """Huella estable y obligatoria del artefacto para evitar duplicados entre revalidaciones."""
    # Alembic 0010 es responsable de la columna física generada. Mapearla únicamente
    # como expresión de consulta mantiene intacto el esquema de pruebas de SQLite.
    catalog_downloadable: Mapped[bool | None] = query_expression()


    source: Mapped[DownloadSource] = relationship(back_populates="resolved_sources")


    __table_args__ = (
        Index("ix_resolved_sources_source_expiry", "download_source_id", "expires_at"),
        Index("ix_resolved_sources_status_expiry", "status", "expires_at"),
        UniqueConstraint(
            "download_source_id",
            "artifact_fingerprint",
            name="uq_resolved_sources_source_fingerprint",
        ),
    )



class LinuxInstallProfileRow(Base):
    """Guarda una receta Linux por resolución exacta con estado de revisión y control de versión.

    Attributes:
        source_ref: UUID de resolución y clave primaria; su eliminación retira la receta.
        version, status, profile_json: Revisión, estado draft/approved y perfil declarativo
            serializado.
    """

    __tablename__ = "linux_install_profiles"
    source_ref: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("resolved_sources.id", ondelete="CASCADE"), primary_key=True
    )
    version: Mapped[int] = mapped_column(BigInteger, default=1, nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False)
    profile_json: Mapped[dict] = mapped_column(JSON, nullable=False)


class SoftwareAppDependencyVersion(Base):
    """Versiona el conjunto de dependencias de una aplicación para detectar sustituciones
    concurrentes.

    Attributes:
        app_id: Aplicación propietaria del conjunto.
        version: Contador de revisión, inicialmente cero.
    """
    __tablename__ = "software_app_dependency_versions"
    app_id: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("software_apps.id", ondelete="CASCADE"), primary_key=True
    )
    version: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)


class SoftwareAppDependency(Base):
    """Representa una arista dirigida del grafo de aplicaciones necesarias para instalar otra
    aplicación.

    Attributes:
        app_id: Aplicación que requiere una dependencia.
        dependency_app_id: Aplicación requerida; el par de UUID forma la clave primaria.
    """
    __tablename__ = "software_app_dependencies"
    app_id: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("software_apps.id", ondelete="CASCADE"), primary_key=True
    )
    dependency_app_id: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("software_apps.id", ondelete="CASCADE"), primary_key=True
    )


class ResolverLog(Base):
    """Registra una fase y resultado de resolución con metadatos destinados a diagnóstico seguro.

    Attributes:
        id, download_source_id: Identidad del registro y fuente opcional.
        phase, status, message: Fase, resultado y resumen de hasta 2000 caracteres.
        safe_metadata, created_at: Contexto serializable e instante del evento.
    """
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
    """Conserva el alcance, reserva exclusiva, progreso y resultado de una ejecución coordinada
    del scraper.

    Attributes:
        id, request_id: Identidades de ejecución y solicitud administrativa, cuando existe.
        active_lock: Clave única de exclusión de la ejecución activa; None libera la reserva.
        status, scope: Estado de ejecución y criterio de selección fijado al solicitarla.
        target_count, target_app_ids_json, target_winstall_ids_json: Cantidad e identidades
            del conjunto objetivo capturado.
        started_at, heartbeat_at, finished_at: Inicio, último latido y fin opcional de la
            ejecución.
        apps_discovered, apps_resolved, apps_failed, apps_skipped: Contadores generales de
            progreso.
        apps_confirmed_missing, apps_needs_review, apps_transient_failed,
            apps_skipped_unchanged: Desglose que evita confundir ausencia verificada con fallo
            temporal.
        worker_id, error_summary: Proceso propietario y resumen final de error.
        current_package_id, current_app_name, current_phase: Aplicación y fase publicadas como
            progreso reciente.
        stop_requested, paused_at: Señal de parada cooperativa e instante de pausa.
    """
    __tablename__ = "scrape_runs"


    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)

    active_lock: Mapped[int | None] = mapped_column(Integer, unique=True)

    status: Mapped[str] = mapped_column(
        String(32), default=ScrapeRunStatus.RUNNING.value, index=True, nullable=False
    )

    scope: Mapped[str] = mapped_column(
        String(32), default=ScrapeScope.INCREMENTAL.value, index=True, nullable=False
    )
    """Scope solicitado para esta ejecución."""
    request_id: Mapped[uuid.UUID | None] = mapped_column(
        GUID(),
        ForeignKey("scraper_commands.id", ondelete="SET NULL"),
        unique=True,
    )
    """Solicitud durable que originó la ejecución."""
    target_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Número de aplicaciones de la instantánea objetivo."""
    target_app_ids_json: Mapped[list[str] | None] = mapped_column(JSON)
    """Manifest inmutable de UUID locales cuando el scope parte del catálogo."""
    target_winstall_ids_json: Mapped[list[str] | None] = mapped_column(JSON)
    """Manifest inmutable de identificadores Winstall procesables."""
    started_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)

    heartbeat_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)

    finished_at: Mapped[datetime | None] = mapped_column(DateTime)

    apps_discovered: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    apps_resolved: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    apps_failed: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    apps_skipped: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    apps_confirmed_missing: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Ausencias respaldadas por una verificación activa."""
    apps_needs_review: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Casos que requieren comprobación humana o evidencia adicional."""
    apps_transient_failed: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Fallos temporales que conservaron el estado publicado anterior."""
    apps_skipped_unchanged: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Aplicaciones omitidas porque su huella no cambió."""
    worker_id: Mapped[str] = mapped_column(String(120), nullable=False)

    error_summary: Mapped[str | None] = mapped_column(String(1000))

    current_package_id: Mapped[str | None] = mapped_column(String(180))

    current_app_name: Mapped[str | None] = mapped_column(String(180))

    current_phase: Mapped[str | None] = mapped_column(String(80))

    stop_requested: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    paused_at: Mapped[datetime | None] = mapped_column(DateTime)


    __table_args__ = (
        Index("ix_scrape_runs_started_at", "started_at"),
        Index("ix_scrape_runs_status_started_at", "status", "started_at"),
    )
    """Los historiales recientes se resuelven por índice sin filesort."""


class ScraperCommand(Base):
    """Persiste una petición administrativa hasta que el scheduler la consume y asocia a su
    ejecución.

    Attributes:
        id, command, scope: Identidad, acción solicitada y alcance opcional.
        app_ids_json: Aplicaciones elegidas para la ejecución solicitada.
        status, message: Estado del comando y explicación de su resultado.
        created_by, created_at, consumed_at, started_at: Actor, creación, consumo e inicio
            opcionales.
    """
    __tablename__ = "scraper_commands"


    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)

    command: Mapped[str] = mapped_column(String(32), nullable=False)

    scope: Mapped[str | None] = mapped_column(String(32), index=True)
    """Scope de una solicitud ``run_once``; nulo para controles históricos."""
    app_ids_json: Mapped[list[str] | None] = mapped_column(JSON)
    """Selección explícita, limitada y validada por la API administrativa."""
    status: Mapped[str] = mapped_column(String(32), default="pending", index=True, nullable=False)

    message: Mapped[str | None] = mapped_column(String(1000))

    created_by: Mapped[str] = mapped_column(String(180), nullable=False)

    created_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)

    consumed_at: Mapped[datetime | None] = mapped_column(DateTime)

    started_at: Mapped[datetime | None] = mapped_column(DateTime)
    """Instante en que el scheduler reclamó la solicitud."""

    __table_args__ = (Index("ix_scraper_commands_status_created", "status", "created_at"),)



class InstallerAbsenceVerification(Base, TimestampMixin):
    """Conserva el acta de ausencia de instaladores junto a las evidencias y versiones que
    permiten invalidarla cuando cambian.

    Attributes:
        id, software_app_id, status: Identidad de acta, aplicación y vigencia.
        reason_code, notes, checked_urls_json, evidence_json: Motivo, observaciones, páginas
            comprobadas y evidencia estructurada.
        verified_by, verified_at, app_version: Actor, instante y versión de aplicación
            comprobada.
        winstall_latest_version, winstall_summary_fingerprint, winstall_detail_fingerprint,
            official_url_fingerprint: Instantánea del proveedor y página oficial contra la que
            se comprueba vigencia.
        invalidated_at, invalidation_reason: Momento y motivo de retirada del acta.
    """

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
    """Conserva la inspección recuperable de una aplicación existente hasta que se aplica o
    caduca, con las entradas privadas cifradas.

    Attributes:
        id, software_app_id: Identidades de operación y aplicación.
        status, phase, input_hash: Estado, progreso e identidad estable de las entradas.
        captured_app_version: Versión que debe coincidir al aplicar el resultado.
        installer_url_encrypted, windows_installer_url_encrypted,
            macos_installer_url_encrypted, linux_installer_url_encrypted: Entrada individual
            compatible y entradas opcionales por plataforma, protegidas con Fernet.
        source_page_url_encrypted: Página de origen protegida.
        result_json, warnings_json, error_code: Evidencias listas para revisión, avisos y
            fallo clasificado.
        expires_at, applied_at, applied_app_version, source_ref: Caducidad y datos de la
            publicación realizada.
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
    """Conserva el descubrimiento recuperable de una web oficial antes de crear una aplicación y
    publicar sus instaladores.

    Attributes:
        id, status, phase, input_hash: Identidad, estado, progreso y huella de entradas para
            reutilización.
        official_url_encrypted, windows_installer_url_encrypted,
            macos_installer_url_encrypted, linux_installer_url_encrypted: Página oficial y URL
            aportadas por plataforma, protegidas con Fernet.
        result_json, warnings_json, error_code: Propuestas, avisos y fallo clasificado.
        expires_at, applied_at, applied_app_id: Vigencia e identidad de la aplicación creada,
            cuando se aplica.
        installers: Candidatos técnicos pertenecientes a este descubrimiento.
    """

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
    """Guarda un candidato de instalador validado dentro de un descubrimiento, todavía separado
    de las fuentes del catálogo.

    Attributes:
        id, discovery_id, discovery: Identidad del candidato y operación propietaria.
        installer_url_encrypted: Destino privado protegido con Fernet.
        final_domain, filename, extension, content_type, size_bytes: Evidencias técnicas con
            tamaño en bytes y None si no se conoce.
        version, operating_system, architecture, score: Versión, contexto de plataforma y
            puntuación de preferencia.
    """

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
    """Representa trabajo persistente y reintentable, único por cola y paquete, con reserva
    temporal para un consumidor.

    Attributes:
        id, run_id, queue, package_id: Identidad de tarea, ejecución opcional, etapa y
            paquete.
        status, app_name, payload_json, priority: Estado, nombre, datos de entrada y prioridad
            de planificación.
        attempts, lease_owner, lease_expires_at: Intentos realizados, propietario y
            vencimiento de reserva.
        available_at, last_error: Primer instante en que puede reclamarse y último error
            resumido.
    """
    __tablename__ = "scraper_work_items"


    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid_pk)

    run_id: Mapped[uuid.UUID | None] = mapped_column(GUID(), ForeignKey("scrape_runs.id"))

    queue: Mapped[str] = mapped_column(String(32), nullable=False)

    status: Mapped[str] = mapped_column(String(32), default="queued", nullable=False)

    package_id: Mapped[str] = mapped_column(String(180), nullable=False)

    app_name: Mapped[str | None] = mapped_column(String(180))

    payload_json: Mapped[dict | None] = mapped_column(JSON(none_as_null=True))

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



class ScraperWorkerHeartbeat(Base):
    """Publica disponibilidad del proceso por rol, separada del progreso de una ejecución
    concreta.

    Attributes:
        role, instance_id: Rol único e identidad de la instancia que escribe el latido.
        started_at, heartbeat_at: Arranque del proceso y último pulso.
        last_success_at, last_error_at, last_error_code: Último éxito y fallo clasificado.
        consecutive_failures: Fallos acumulados desde el último éxito; nunca negativo.
    """

    __tablename__ = "scraper_worker_heartbeats"

    role: Mapped[str] = mapped_column(String(64), primary_key=True)
    """Rol estable del proceso observado."""
    instance_id: Mapped[uuid.UUID] = mapped_column(GUID(), nullable=False)
    """Identidad efímera que cambia tras cada reinicio."""
    started_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
    """Inicio de la instancia que publicó el último pulso."""
    heartbeat_at: Mapped[datetime] = mapped_column(
        DateTime, default=utc_now, nullable=False, index=True
    )
    """Último pulso independiente del resultado del trabajo."""
    last_success_at: Mapped[datetime | None] = mapped_column(DateTime)
    """Última iteración completada sin error."""
    last_error_at: Mapped[datetime | None] = mapped_column(DateTime)
    """Última iteración fallida."""
    last_error_code: Mapped[str | None] = mapped_column(String(128))
    """Clase segura del último error, sin su mensaje."""
    consecutive_failures: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    """Fallos consecutivos desde el último éxito."""

    __table_args__ = (
        CheckConstraint(
            "consecutive_failures >= 0",
            name="ck_scraper_worker_heartbeat_failures_nonnegative",
        ),
    )


class ScraperWorkerSnapshot(Base):
    """Conserva temporalmente el estado visual o HTML de una etapa para inspección
    administrativa.

    Attributes:
        id, run_id, worker_id, stage: Identidad, ejecución, worker y etapa.
        package_id, app_name, url, html: Aplicación y contenido de diagnóstico capturado.
        captured_at, expires_at: Instante de captura y límite de retención.
    """
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
    """Registra una instantánea temporal de disponibilidad del catálogo y trabajo pendiente por
    etapa.

    Attributes:
        id, run_id, captured_at: Identidad, ejecución opcional e instante de captura.
        available, review, unavailable: Recuentos de catálogo observados.
        queued_searcher_filter, queued_filter_scraper, queued_scraper_so_filter,
            queued_so_filter_descriptor: Profundidad de las colas entre etapas del pipeline.
    """
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
    """Coordina entre procesos el próximo instante permitido de acceso a un proveedor.

    Attributes:
        key: Identidad del proveedor o cuota compartida.
        next_allowed_at: Fecha UTC hasta la que debe aplazarse una nueva solicitud.
        updated_at: Último cambio del límite persistido.
    """
    __tablename__ = "scraper_rate_limits"


    key: Mapped[str] = mapped_column(String(64), primary_key=True)

    next_allowed_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)

    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now, nullable=False)
