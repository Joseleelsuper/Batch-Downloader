"""Publica resoluciones protegidas y mantiene coherencia de plataformas, estados y actas de
ausencia en la transacción del llamador.
"""

import uuid

from sqlalchemy import case, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.json_safe import json_safe
from app.core.time import utc_after, utc_now
from app.core.url_protector import UrlProtector
from app.db.enums import (
    AbsenceVerificationStatus,
    ResolutionStatus,
    ValidationStatus,
)
from app.db.models import (
    DownloadSource,
    InstallerAbsenceVerification,
    ResolvedSource,
    SoftwareApp,
)
from app.repositories.catalog_rules import (
    AVAILABLE_RESOLUTION_STATUSES,
    ResolvedSourceCreate,
    artifact_fingerprint,
    has_verified_binary_history,
    inferred_architecture_for_resolved_source,
    inferred_platform_for_resolved_source,
    is_manual_download_source,
)
from app.scraper.winstall import (
    WinstallApp,
)


class CatalogSourceStore:
    """Mantiene fuentes y artefactos concretos con una sesión cedida, sin confirmar sus cambios
    automáticamente.

    See Also:
        app.repositories.catalog.CatalogRepository: Comparte esta instancia con consultas y
            sincronización del catálogo.
    """

    def __init__(self, session: AsyncSession, url_protector: UrlProtector) -> None:
        """Conserva la sesión y el protector de URL que utilizará la publicación de artefactos.

        Args:
            session: Sesión asíncrona del llamador; se comparte por composición, nunca entre
                workers concurrentes.
            url_protector: Cifrador del despliegue para URL privadas de las resoluciones.
        """
        self.session = session
        self.url_protector = url_protector

    async def repair_resolved_source_platforms(self) -> int:
        """Reubica resoluciones válidas cuya evidencia contradice la plataforma de su fuente,
        conserva su identidad y recalcula los estados afectados.

        Returns:
            número de resoluciones trasladadas; los cambios quedan pendientes de commit.
        """
        result = await self.session.scalars(
            select(ResolvedSource)
            .join(DownloadSource)
            .where(ResolvedSource.validation_status == ValidationStatus.VALID.value)
            .options(selectinload(ResolvedSource.source))
        )
        repaired = 0
        affected_sources: set[uuid.UUID] = set()
        for resolved in result.unique():
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

    async def refresh_operating_systems(self, software_app_id: uuid.UUID) -> list[str] | None:
        """Deriva plataformas del historial binario verificado y las guarda en orden Windows,
        Linux, macOS; actualiza versión solo si cambia la lista.

        Args:
            software_app_id: UUID de la aplicación propietaria.

        Returns:
            plataformas detectadas o None si falta la aplicación.
        """
        software_app = await self.session.get(SoftwareApp, software_app_id)
        if software_app is None:
            return None

        rows = await self.session.execute(
            select(
                DownloadSource.operating_system,
                ResolvedSource.validation_status,
                ResolvedSource.status,
                ResolvedSource.metadata_json,
            )
            .join(
                ResolvedSource,
                ResolvedSource.download_source_id == DownloadSource.id,
            )
            .where(DownloadSource.software_app_id == software_app_id)
            .where(DownloadSource.operating_system.in_(("windows", "linux", "macos")))
            .where(
                DownloadSource.resolution_status.in_(
                    (ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value)
                )
            )
            .where(DownloadSource.validation_status == ValidationStatus.VALID.value)
        )
        detected = {
            operating_system
            for (
                operating_system,
                validation_status,
                resolution_status,
                metadata,
            ) in rows
            if has_verified_binary_history(
                validation_status,
                resolution_status,
                metadata or {},
            )
        }
        systems = [
            operating_system
            for operating_system in ("windows", "linux", "macos")
            if operating_system in detected
        ]
        if list(software_app.operating_systems or []) != systems:
            software_app.operating_systems = systems
            software_app.updated_at = utc_now()
            software_app.version += 1
        software_app.operating_systems_updated_at = utc_now()
        await self.session.flush()
        return systems

    async def ensure_download_source(
        self,
        software_app_id: uuid.UUID,
        app: WinstallApp,
        operating_system: str,
        architecture: str,
        initial_url: str | None,
    ) -> DownloadSource:
        """Reutiliza una fuente automática de la misma plataforma y arquitectura o crea una
        pendiente de revisión; conserva las fuentes manuales existentes.

        Args:
            software_app_id: UUID de la aplicación propietaria.
            app: Datos de la aplicación recibidos de Winstall.
            operating_system: Plataforma de la fuente: windows, linux o macos.
            architecture: Arquitectura de la fuente, incluida UNKNOWN cuando no se conoce.
            initial_url: Página inicial del resolutor; None conserva la existente.

        Returns:
            fuente automática preparada con la identidad del proveedor.
        """
        sources = list(
            await self.session.scalars(
                select(DownloadSource)
                .where(DownloadSource.software_app_id == software_app_id)
                .where(DownloadSource.operating_system == operating_system)
                .where(DownloadSource.architecture == architecture)
            )
        )
        source = next(
            (candidate for candidate in sources if not is_manual_download_source(candidate)),
            None,
        )
        if source is None:
            source = DownloadSource(
                software_app_id=software_app_id,
                operating_system=operating_system,
                architecture=architecture,
                initial_url=initial_url,
                resolver_type="generic_http",
                resolver_config=json_safe({"winstall_id": app.package_id}),
                # La mera creación de una fila no demuestra que el instalador no
                # exista. Solo una verificación de ausencia activa puede promoverla
                # posteriormente a ``missing``.
                resolution_status=ResolutionStatus.REQUIRES_MANUAL_REVIEW.value,
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
        """Reutiliza o crea una fuente para el contexto corregido y conserva su configuración
        existente cuando ya está definida.

        Args:
            source: Fuente original de la que se copian página y configuración al crear el
                destino.
            operating_system: Plataforma de la fuente: windows, linux o macos.
            architecture: Arquitectura de la fuente, incluida UNKNOWN cuando no se conoce.
            status: Estado de resolución inicial del destino cuando se crea.
            validation_status: Estado de validación que se asigna a la fuente.

        Returns:
            fuente que recibirá la resolución trasladada.
        """
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
        """Adopta el estado de la mejor resolución válida; si una fuente antes válida ya no tiene
        ninguna, la deja en revisión y sin comprobación.

        Args:
            source_id: UUID de la fuente cuyos artefactos se consultan o actualizan.
        """
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
        """Hace visibles los cambios pendientes mediante flush y recalcula el estado de cada
        fuente indicada sin confirmar la transacción.

        Args:
            source_ids: UUID de fuentes que deben recalcular su estado.
        """
        await self.session.flush()
        for source_id in source_ids:
            await self._refresh_source_status_from_resolved(source_id)

    async def repair_source_statuses(self) -> int:
        """Recorre todas las fuentes y recalcula su estado a partir de sus resoluciones válidas.

        Returns:
            número de fuentes revisadas, aunque alguna ya estuviera correcta.
        """
        source_ids = set(await self.session.scalars(select(DownloadSource.id)))
        await self.refresh_source_statuses(source_ids)
        return len(source_ids)

    async def source_for_platform(
        self,
        software_app_id: uuid.UUID,
        operating_system: str,
        architecture: str,
    ) -> DownloadSource | None:
        """Busca una fuente de aplicación, plataforma y arquitectura exactas y precarga sus
        resoluciones.

        Args:
            software_app_id: UUID de la aplicación propietaria.
            operating_system: Plataforma de la fuente: windows, linux o macos.
            architecture: Arquitectura de la fuente, incluida UNKNOWN cuando no se conoce.

        Returns:
            primera coincidencia o None.
        """
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
        """Busca la fuente Windows predeterminada y prefiere arquitectura UNKNOWN sobre x86_64.

        Args:
            software_app_id: UUID de la aplicación propietaria.

        Returns:
            fuente compatible con sus resoluciones cargadas o None.
        """
        return await self.session.scalar(
            select(DownloadSource)
            .options(
                selectinload(DownloadSource.resolved_sources),
            )
            .where(DownloadSource.software_app_id == software_app_id)
            .where(DownloadSource.operating_system == "windows")
            # Las filas creadas antes de introducir UNKNOWN todavía pueden reanudarse
            # con seguridad; las filas nuevas nunca infieren x86_64 sin evidencias.
            .where(DownloadSource.architecture.in_(["UNKNOWN", "x86_64"]))
            .order_by(case((DownloadSource.architecture == "UNKNOWN", 0), else_=1))
            .limit(1)
        )

    async def save_resolved_source(self, item: ResolvedSourceCreate) -> ResolvedSource:
        """Cifra la URL y crea o actualiza la resolución de la misma fuente y huella, conservando
        su UUID cuando ya existe.
        Renueva la vigencia 24 horas, actualiza la fuente e invalida actas de ausencia; la
        transacción sigue siendo del llamador.

        Args:
            item: Datos completos del instalador que se crea o actualiza por huella.

        Returns:
            entidad de resolución creada o reutilizada.
        """
        encrypted_url = self.url_protector.protect(item.url)
        fingerprint = item.artifact_fingerprint or artifact_fingerprint(item)
        resolved = await self.session.scalar(
            select(ResolvedSource)
            .where(ResolvedSource.download_source_id == item.source_id)
            .where(ResolvedSource.artifact_fingerprint == fingerprint)
            .limit(1)
        )
        if resolved is None:
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
                artifact_fingerprint=fingerprint,
            )
            self.session.add(resolved)
        else:
            resolved.resolved_url_encrypted = encrypted_url
            resolved.final_domain = item.final_domain
            resolved.filename = item.filename
            resolved.extension = item.extension
            resolved.content_type = item.content_type
            resolved.size_bytes = item.size_bytes
            resolved.version = item.version
            resolved.release_rank = item.release_rank
            resolved.is_latest = item.is_latest
            resolved.version_status = item.version_status
            resolved.score = item.score
            resolved.status = item.status.value
            resolved.validation_status = item.validation_status.value
            resolved.checked_at = utc_now()
            resolved.expires_at = utc_after(hours=24)
            resolved.metadata_json = json_safe(item.metadata)

        source = await self.session.get(DownloadSource, item.source_id)
        if source:
            source.resolution_status = item.status.value
            source.validation_status = item.validation_status.value
            source.updated_at = utc_now()
            source.version += 1
            await self.invalidate_absence_verifications(
                source.software_app_id,
                "validated_installer_appeared",
            )
        return resolved

    async def active_absence_verification(
        self,
        software_app_id: uuid.UUID,
    ) -> InstallerAbsenceVerification | None:
        """Selecciona el acta activa más reciente de la aplicación.

        Args:
            software_app_id: UUID de la aplicación propietaria.

        Returns:
            acta vigente o None.
        """
        return await self.session.scalar(
            select(InstallerAbsenceVerification)
            .where(InstallerAbsenceVerification.software_app_id == software_app_id)
            .where(InstallerAbsenceVerification.status == AbsenceVerificationStatus.ACTIVE.value)
            .order_by(InstallerAbsenceVerification.verified_at.desc())
            .limit(1)
        )

    async def invalidate_absence_verifications(
        self,
        software_app_id: uuid.UUID,
        reason: str,
    ) -> int:
        """Invalida todas las actas activas con el mismo motivo e instante y devuelve la fuente
        predeterminada a revisión si ninguna fuente es válida.

        Args:
            software_app_id: UUID de la aplicación propietaria.
            reason: Motivo de invalidación de las actas, limitado a 180 caracteres.

        Returns:
            número de actas retiradas.
        """
        rows = list(
            await self.session.scalars(
                select(InstallerAbsenceVerification)
                .where(InstallerAbsenceVerification.software_app_id == software_app_id)
                .where(
                    InstallerAbsenceVerification.status == AbsenceVerificationStatus.ACTIVE.value
                )
            )
        )
        if not rows:
            return 0
        now = utc_now()
        for verification in rows:
            verification.status = AbsenceVerificationStatus.INVALIDATED.value
            verification.invalidated_at = now
            verification.invalidation_reason = reason[:180]
            verification.updated_at = now

        has_available = await self.session.scalar(
            select(DownloadSource.id)
            .where(DownloadSource.software_app_id == software_app_id)
            .where(DownloadSource.resolution_status.in_(AVAILABLE_RESOLUTION_STATUSES))
            .where(DownloadSource.validation_status == ValidationStatus.VALID.value)
            .limit(1)
        )
        if has_available is None:
            source = await self.default_source_for_app(software_app_id)
            if source:
                source.resolution_status = ResolutionStatus.REQUIRES_MANUAL_REVIEW.value
                source.validation_status = ValidationStatus.UNCHECKED.value
                source.updated_at = now
        return len(rows)

    async def expire_valid_resolved_sources(self, source_id: uuid.UUID) -> None:
        """Marca expired todas las resoluciones actualmente válidas de una fuente y fija su
        caducidad al instante actual.

        Args:
            source_id: UUID de la fuente cuyos artefactos se consultan o actualizan.
        """
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

    async def valid_resolved_sources_for_app(
        self,
        software_app_id: uuid.UUID,
    ) -> list[ResolvedSource]:
        """Obtiene resoluciones directas o fallback con estado válido de la aplicación, sin
        filtrar por fecha de caducidad.

        Args:
            software_app_id: UUID de la aplicación propietaria.

        Returns:
            resoluciones con historial válido para evaluar el refresco.
        """
        rows = await self.session.scalars(
            select(ResolvedSource)
            .join(DownloadSource)
            .where(DownloadSource.software_app_id == software_app_id)
            .where(ResolvedSource.validation_status == ValidationStatus.VALID.value)
            .where(ResolvedSource.status.in_(AVAILABLE_RESOLUTION_STATUSES))
        )
        return list(rows)

    async def expire_resolved_sources(
        self,
        resolved_sources: list[ResolvedSource],
    ) -> set[uuid.UUID]:
        """Retira la validación de las resoluciones válidas recibidas y recalcula los estados de
        sus fuentes.

        Args:
            resolved_sources: Resoluciones cargadas cuya validación puede retirarse.

        Returns:
            UUID de las fuentes afectadas.
        """
        now = utc_now()
        source_ids: set[uuid.UUID] = set()
        for resolved in resolved_sources:
            if resolved.validation_status != ValidationStatus.VALID.value:
                continue
            resolved.validation_status = ValidationStatus.EXPIRED.value
            resolved.expires_at = now
            resolved.checked_at = now
            source_ids.add(resolved.download_source_id)
        if source_ids:
            await self.refresh_source_statuses(source_ids)
        return source_ids

    async def mark_source_status(
        self,
        source_id: uuid.UUID,
        resolution_status: ResolutionStatus,
        validation_status: ValidationStatus = ValidationStatus.UNCHECKED,
    ) -> None:
        """Asigna estados de resolución y validación a una fuente y actualiza su fecha; no actúa
        si la fuente ya no existe.

        Args:
            source_id: UUID de la fuente cuyos artefactos se consultan o actualizan.
            resolution_status: Resultado de resolución que se asigna a la fuente.
            validation_status: Estado de validación que se asigna a la fuente.
        """
        source = await self.session.get(DownloadSource, source_id)
        if not source:
            return
        source.resolution_status = resolution_status.value
        source.validation_status = validation_status.value
        source.updated_at = utc_now()
