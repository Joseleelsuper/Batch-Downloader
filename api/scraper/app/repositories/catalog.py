"""Implementa las responsabilidades del módulo `catalog`.
"""
import hashlib
import json
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from urllib.parse import urlparse

import tldextract
from sqlalchemy import Boolean, Select, case, func, literal_column, or_, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload, with_expression

from app.core.json_safe import json_safe
from app.core.time import utc_after, utc_now
from app.core.url_protector import UrlProtector
from app.db.enums import (
    AbsenceVerificationStatus,
    AppStatus,
    LongDescriptionStatus,
    ResolutionStatus,
    ValidationStatus,
)
from app.db.models import (
    CatalogCounter,
    DownloadSource,
    InstallerAbsenceVerification,
    ResolvedSource,
    ScrapeRun,
    SoftwareApp,
    SoftwareAppTag,
)
from app.scraper.text import normalize_text, slugify
from app.scraper.winstall import (
    WinstallApp,
    winstall_detail_fingerprint,
    winstall_summary_fingerprint,
)

AVAILABLE_RESOLUTION_STATUSES = {
    ResolutionStatus.DIRECT.value,
    ResolutionStatus.FALLBACK.value,
}
"""Constante que define `AVAILABLE_RESOLUTION_STATUSES`.
"""
CATALOG_DOWNLOADABLE_COLUMN = literal_column(
    "resolved_sources.catalog_downloadable",
    Boolean,
)
"""Constante que define `CATALOG_DOWNLOADABLE_COLUMN`.
"""


@dataclass(frozen=True)
class ResolvedSourceCreate:
    """Representa el componente `ResolvedSourceCreate`.
    """
    source_id: uuid.UUID
    """Atributo de clase `source_id` de `ResolvedSourceCreate`.
    """
    url: str
    """Atributo de clase `url` de `ResolvedSourceCreate`.
    """
    final_domain: str
    """Atributo de clase `final_domain` de `ResolvedSourceCreate`.
    """
    filename: str | None
    """Atributo de clase `filename` de `ResolvedSourceCreate`.
    """
    extension: str | None
    """Atributo de clase `extension` de `ResolvedSourceCreate`.
    """
    content_type: str | None
    """Atributo de clase `content_type` de `ResolvedSourceCreate`.
    """
    size_bytes: int | None
    """Atributo de clase `size_bytes` de `ResolvedSourceCreate`.
    """
    version: str | None
    """Atributo de clase `version` de `ResolvedSourceCreate`.
    """
    score: int
    """Atributo de clase `score` de `ResolvedSourceCreate`.
    """
    status: ResolutionStatus
    """Atributo de clase `status` de `ResolvedSourceCreate`.
    """
    validation_status: ValidationStatus
    """Atributo de clase `validation_status` de `ResolvedSourceCreate`.
    """
    release_rank: int | None = None
    """Atributo de clase `release_rank` de `ResolvedSourceCreate`.
    """
    is_latest: bool = False
    """Atributo de clase `is_latest` de `ResolvedSourceCreate`.
    """
    version_status: str | None = None
    """Atributo de clase `version_status` de `ResolvedSourceCreate`.
    """
    metadata: dict | None = None
    """Atributo de clase `metadata` de `ResolvedSourceCreate`.
    """
    artifact_fingerprint: str | None = None
    """Huella aportada por un resolver especializado; se calcula si se omite."""


class CatalogRepository:
    """Gestiona la persistencia y consulta de `Catalog`.
    """
    def __init__(self, session: AsyncSession, url_protector: UrlProtector) -> None:
        """Inicializa una instancia de `CatalogRepository`.

        Args:
            session (AsyncSession): Sesión de base de datos utilizada por la operación.
            url_protector (UrlProtector): Valor de `url_protector` utilizado por la operación.
        """
        self.session = session
        """Estado de instancia asociado a `session`.
        """
        self.url_protector = url_protector
        """Estado de instancia asociado a `url_protector`.
        """

    async def should_scrape_winstall_package(
        self,
        package_id: str,
        *,
        force_refresh: bool = False,
    ) -> bool:
        """Ejecuta `should_scrape_winstall_package` dentro de `CatalogRepository`.

        Args:
            package_id (str): Identificador de `package` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        if force_refresh:
            return True
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
        # Mantiene inmutables las aplicaciones correctas durante las pasadas normales del
        # catálogo y permite que resultados review o missing aprovechen mejoras del resolver.
        return resolved_source is None

    async def winstall_refresh_states(self) -> dict[str, tuple[uuid.UUID, str | None, str | None]]:
        """Devuelve estado y huella local para seleccionar el scope incremental."""
        rows = await self.session.execute(
            select(
                SoftwareApp.winstall_id,
                SoftwareApp.id,
                SoftwareApp.catalog_status,
                SoftwareApp.winstall_summary_fingerprint,
            ).where(SoftwareApp.app_status == AppStatus.ACTIVE.value)
        )
        return {
            winstall_id: (app_id, catalog_status, fingerprint)
            for winstall_id, app_id, catalog_status, fingerprint in rows
        }

    async def snapshot_refresh_targets(
        self,
        *,
        statuses: set[str] | None = None,
        app_ids: list[uuid.UUID] | None = None,
    ) -> list[SoftwareApp]:
        """Captura de forma ordenada el manifest local de una solicitud dirigida."""
        stmt = (
            select(SoftwareApp)
            .where(SoftwareApp.app_status == AppStatus.ACTIVE.value)
            .order_by(SoftwareApp.id)
        )
        if statuses is not None:
            stmt = stmt.where(SoftwareApp.catalog_status.in_(sorted(statuses)))
        if app_ids is not None:
            if not app_ids:
                return []
            stmt = stmt.where(SoftwareApp.id.in_(app_ids))
        return list(await self.session.scalars(stmt))

    async def repair_resolved_source_platforms(self) -> int:
        """Ejecuta `repair_resolved_source_platforms` dentro de `CatalogRepository`.

        Returns:
            int: Resultado producido por la operación.
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

    async def upsert_winstall_app(self, app: WinstallApp) -> SoftwareApp:
        """Ejecuta `upsert_winstall_app` dentro de `CatalogRepository`.

        Args:
            app (WinstallApp): Aplicación sobre la que se realiza la operación.

        Returns:
            SoftwareApp: Resultado producido por la operación.
        """
        software_app, _created = await self.upsert_winstall_app_with_created(app)
        return software_app

    async def upsert_winstall_app_with_created(
        self,
        app: WinstallApp,
    ) -> tuple[SoftwareApp, bool]:
        """Ejecuta `upsert_winstall_app_with_created` dentro de `CatalogRepository`.

        Args:
            app (WinstallApp): Aplicación sobre la que se realiza la operación.

        Returns:
            tuple[SoftwareApp, bool]: Resultado producido por la operación.
        """
        existing = await self.session.scalar(
            select(SoftwareApp)
            .options(
                selectinload(SoftwareApp.tags),
                selectinload(SoftwareApp.sources),
            )
            .where(SoftwareApp.winstall_id == app.package_id)
        )
        if existing is not None:
            await self._sync_existing_winstall_app(existing, app)
            await self._refresh_catalog_status(existing)
            return existing, False

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
            winstall_latest_version=app.latest_version,
            winstall_updated_at=parse_provider_datetime(app.raw.get("updatedAt")),
            winstall_summary_fingerprint=winstall_summary_fingerprint(app),
            winstall_detail_fingerprint=(
                winstall_detail_fingerprint(app) if app.installer_data_complete else None
            ),
            app_status=AppStatus.ACTIVE.value,
            metadata_json=json_safe(app.raw),
        )
        self.session.add(existing)
        await self.session.flush()

        await self._sync_tags(existing, app.tags)
        await self._ensure_default_source(existing, app)
        await self._refresh_catalog_status(existing)
        return existing, True

    async def _refresh_catalog_status(self, software_app: SoftwareApp) -> None:
        """Carga explícitamente la proyección modificada por triggers tras el flush.

        MySQL invalida el atributo calculado al actualizar la fila. Acceder después
        de forma síncrona desde el ORM async intentaría una carga implícita y provoca
        ``MissingGreenlet``.
        """
        await self.session.flush()
        await self.session.refresh(software_app, attribute_names=["catalog_status"])

    async def _sync_existing_winstall_app(
        self,
        software_app: SoftwareApp,
        app: WinstallApp,
    ) -> None:
        """Sincroniza datos del proveedor conservando cada campo marcado como manual."""
        old_summary = software_app.winstall_summary_fingerprint
        old_detail = software_app.winstall_detail_fingerprint
        new_summary = winstall_summary_fingerprint(app)
        new_detail = (
            winstall_detail_fingerprint(app) if app.installer_data_complete else old_detail
        )
        verification = (
            await self.active_absence_verification(software_app.id)
            if software_app.catalog_status == "missing"
            else None
        )
        manual_fields = manual_field_sources(software_app)
        changed = False

        icon_url = app.icon_url
        if app.icon and not app.icon.startswith("http"):
            icon_key = app.icon.removesuffix(".png")
            icon_url = f"https://api.winstall.app/icons/next/{icon_key}.webp"
        provider_fields = {
            "name": app.name or app.package_id,
            "publisher": app.publisher,
            "officialUrl": app.homepage,
            "latestVersion": app.latest_version,
            "description": app.description,
            "iconUrl": icon_url,
        }
        attributes = {
            "name": "name",
            "publisher": "publisher",
            "officialUrl": "official_url",
            "latestVersion": "latest_version",
            "description": "description",
            "iconUrl": "icon_url",
        }
        for field_name, value in provider_fields.items():
            if manual_fields.get(field_name) == "manual":
                continue
            if (
                field_name == "latestVersion"
                and software_app.catalog_status == "available"
                and software_app.latest_version != value
            ):
                # La versión publicada se promueve después de validar su artefacto.
                continue
            attribute = attributes[field_name]
            if getattr(software_app, attribute) != value:
                setattr(software_app, attribute, value)
                changed = True
                if attribute == "name":
                    software_app.normalized_name = normalize_text(value or app.package_id)

        metadata = dict(software_app.metadata_json or {})
        provider_metadata = dict(json_safe(app.raw) or {})
        for key in ("manual_installer", "website_discovery"):
            if key in metadata:
                provider_metadata[key] = metadata[key]
        if provider_metadata != metadata:
            software_app.metadata_json = provider_metadata
            changed = True

        if software_app.winstall_latest_version != app.latest_version:
            software_app.winstall_latest_version = app.latest_version
            changed = True
        provider_updated_at = parse_provider_datetime(app.raw.get("updatedAt"))
        if provider_updated_at and software_app.winstall_updated_at != provider_updated_at:
            software_app.winstall_updated_at = provider_updated_at
            changed = True
        if old_summary != new_summary:
            software_app.winstall_summary_fingerprint = new_summary
            changed = True
        if new_detail != old_detail:
            software_app.winstall_detail_fingerprint = new_detail
            changed = True

        evidence_change = verification is not None and (
            verification.winstall_summary_fingerprint != new_summary
            or verification.winstall_detail_fingerprint != new_detail
            or verification.official_url_fingerprint
            != text_fingerprint(software_app.official_url)
        )
        if evidence_change:
            await self.invalidate_absence_verifications(
                software_app.id,
                "winstall_changed_or_candidate_appeared",
            )

        await self._sync_tags(software_app, app.tags)
        await self._ensure_default_source(software_app, app)
        if changed:
            software_app.updated_at = utc_now()
            software_app.version += 1

    async def promote_winstall_latest_version(self, software_app_id: uuid.UUID) -> bool:
        """Promueve la versión anunciada solo después de validar el reemplazo."""
        software_app = await self.session.get(SoftwareApp, software_app_id)
        if software_app is None:
            return False
        if manual_field_sources(software_app).get("latestVersion") == "manual":
            return False
        if software_app.latest_version == software_app.winstall_latest_version:
            return False
        software_app.latest_version = software_app.winstall_latest_version
        software_app.updated_at = utc_now()
        software_app.version += 1
        return True

    async def _sync_tags(self, software_app: SoftwareApp, raw_tags: list[str]) -> None:
        """Ejecuta el paso interno `_sync_tags`.

        Args:
            software_app (SoftwareApp): Valor de `software_app` utilizado por la operación.
            raw_tags (list[str]): Valor de `raw_tags` utilizado por la operación.
        """
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

        for normalized, existing_tag in existing_tags.items():
            if normalized not in normalized_to_tag:
                await self.session.delete(existing_tag)
                changed = True

        if changed:
            software_app.updated_at = utc_now()
            software_app.version += 1
            await self.session.flush()
            await self.session.refresh(software_app, attribute_names=["tags"])

    async def update_icon_url(self, software_app_id: uuid.UUID, icon_url: str) -> bool:
        """Actualiza la operación `icon_url`.

        Args:
            software_app_id (uuid.UUID): Identificador de `software_app` utilizado por la operación.
            icon_url (str): Dirección de `icon` que debe procesarse.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        software_app = await self.session.get(SoftwareApp, software_app_id)
        if (
            not software_app
            or not is_replaceable_github_icon(software_app.icon_url)
            or not has_icon_url(icon_url)
        ):
            return False
        software_app.icon_url = icon_url
        software_app.updated_at = utc_now()
        software_app.version += 1
        return True

    async def apps_missing_long_descriptions(self) -> list[SoftwareApp]:
        """Ejecuta `apps_missing_long_descriptions` dentro de `CatalogRepository`.

        Returns:
            list[SoftwareApp]: Colección de elementos obtenidos por la operación.
        """
        result = await self.session.scalars(
            select(SoftwareApp)
            .where(SoftwareApp.app_status == AppStatus.ACTIVE.value)
            .where(
                or_(
                    SoftwareApp.long_description.is_(None),
                    func.trim(SoftwareApp.long_description) == "",
                )
            )
            .order_by(SoftwareApp.updated_at.asc())
        )
        return list(result)

    async def apps_pending_os_filter(self, limit: int = 250) -> list[SoftwareApp]:
        """Ejecuta `apps_pending_os_filter` dentro de `CatalogRepository`.

        Args:
            limit (int): Número máximo de elementos que se recuperarán.

        Returns:
            list[SoftwareApp]: Colección de elementos obtenidos por la operación.
        """
        candidate_ids = list(
            await self.session.scalars(
                select(SoftwareApp.id)
                .where(SoftwareApp.app_status == AppStatus.ACTIVE.value)
                .where(
                    or_(
                        SoftwareApp.operating_systems_updated_at.is_(None),
                        SoftwareApp.operating_systems_updated_at < utc_after(hours=-24),
                    )
                )
                .order_by(SoftwareApp.updated_at.asc())
                .limit(max(1, limit))
            )
        )
        if not candidate_ids:
            return []

        result = await self.session.scalars(
            select(SoftwareApp).where(SoftwareApp.id.in_(candidate_ids))
        )
        apps_by_id = {app.id: app for app in result}
        return [apps_by_id[app_id] for app_id in candidate_ids if app_id in apps_by_id]

    async def refresh_operating_systems(self, software_app_id: uuid.UUID) -> list[str] | None:
        """Ejecuta `refresh_operating_systems` dentro de `CatalogRepository`.

        Args:
            software_app_id (uuid.UUID): Identificador de `software_app` utilizado por la operación.

        Returns:
            list[str] | None: Colección de elementos obtenidos por la operación.
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

    async def _ensure_default_source(self, software_app: SoftwareApp, app: WinstallApp) -> None:
        """Ejecuta el paso interno `_ensure_default_source`.

        Args:
            software_app (SoftwareApp): Valor de `software_app` utilizado por la operación.
            app (WinstallApp): Aplicación sobre la que se realiza la operación.
        """
        await self.ensure_download_source(
            software_app_id=software_app.id,
            app=app,
            operating_system="windows",
            architecture="UNKNOWN",
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
        """Garantiza la operación `download_source`.

        Args:
            software_app_id (uuid.UUID): Identificador de `software_app` utilizado por la operación.
            app (WinstallApp): Aplicación sobre la que se realiza la operación.
            operating_system (str): Valor de `operating_system` utilizado por la operación.
            architecture (str): Valor de `architecture` utilizado por la operación.
            initial_url (str | None): Dirección de `initial` que debe procesarse.

        Returns:
            DownloadSource: Resultado producido por la operación.
        """
        sources = list(await self.session.scalars(
            select(DownloadSource)
            .where(DownloadSource.software_app_id == software_app_id)
            .where(DownloadSource.operating_system == operating_system)
            .where(DownloadSource.architecture == architecture)
        ))
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
        """Ejecuta el paso interno `_ensure_platform_source_from_existing`.

        Args:
            source (DownloadSource): Fuente de descarga sobre la que se actúa.
            operating_system (str): Valor de `operating_system` utilizado por la operación.
            architecture (str): Valor de `architecture` utilizado por la operación.
            status (str): Valor de `status` utilizado por la operación.
            validation_status (str): Valor de `validation_status` utilizado por la operación.

        Returns:
            DownloadSource: Resultado producido por la operación.
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
        """Ejecuta el paso interno `_refresh_source_status_from_resolved`.

        Args:
            source_id (uuid.UUID): Identificador de `source` utilizado por la operación.
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
        """Ejecuta `refresh_source_statuses` dentro de `CatalogRepository`.

        Args:
            source_ids (set[uuid.UUID]): Colección de identificadores de `source`.
        """
        await self.session.flush()
        for source_id in source_ids:
            await self._refresh_source_status_from_resolved(source_id)

    async def repair_source_statuses(self) -> int:
        """Ejecuta `repair_source_statuses` dentro de `CatalogRepository`.

        Returns:
            int: Resultado producido por la operación.
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
        """Ejecuta `source_for_platform` dentro de `CatalogRepository`.

        Args:
            software_app_id (uuid.UUID): Identificador de `software_app` utilizado por la operación.
            operating_system (str): Valor de `operating_system` utilizado por la operación.
            architecture (str): Valor de `architecture` utilizado por la operación.

        Returns:
            DownloadSource | None: Resultado producido por la operación.
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
        """Ejecuta `default_source_for_app` dentro de `CatalogRepository`.

        Args:
            software_app_id (uuid.UUID): Identificador de `software_app` utilizado por la operación.

        Returns:
            DownloadSource | None: Resultado producido por la operación.
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
        """Guarda la operación `resolved_source`.

        Args:
            item (ResolvedSourceCreate): Valor de `item` utilizado por la operación.

        Returns:
            ResolvedSource: Resultado producido por la operación.
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
        """Obtiene la evidencia negativa vigente más reciente."""
        return await self.session.scalar(
            select(InstallerAbsenceVerification)
            .where(InstallerAbsenceVerification.software_app_id == software_app_id)
            .where(
                InstallerAbsenceVerification.status
                == AbsenceVerificationStatus.ACTIVE.value
            )
            .order_by(InstallerAbsenceVerification.verified_at.desc())
            .limit(1)
        )

    async def invalidate_absence_verifications(
        self,
        software_app_id: uuid.UUID,
        reason: str,
    ) -> int:
        """Invalida actas activas y devuelve la aplicación a revisión si procede."""
        rows = list(
            await self.session.scalars(
                select(InstallerAbsenceVerification)
                .where(InstallerAbsenceVerification.software_app_id == software_app_id)
                .where(
                    InstallerAbsenceVerification.status
                    == AbsenceVerificationStatus.ACTIVE.value
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

    async def apps_for_description_enrichment(
        self,
        app_ids: list[uuid.UUID] | None = None,
        *,
        include_completed: bool = False,
    ) -> list[SoftwareApp]:
        """Ejecuta `apps_for_description_enrichment` dentro de `CatalogRepository`.

        Args:
            app_ids (list[uuid.UUID] | None): Colección de identificadores de `app`.
            include_completed (bool): Valor de `include_completed` utilizado por la operación.

        Returns:
            list[SoftwareApp]: Colección de elementos obtenidos por la operación.
        """
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

    async def semantic_documents(
        self,
        *,
        after_app_id: uuid.UUID | None,
        limit: int,
    ) -> tuple[list[SoftwareApp], str | None]:
        """Ejecuta `semantic_documents` dentro de `CatalogRepository`.

        Args:
            after_app_id (uuid.UUID | None): Identificador de `after_app` utilizado por la
                operación.
            limit (int): Número máximo de elementos que se recuperarán.

        Returns:
            tuple[list[SoftwareApp], str | None]: Colección de elementos obtenidos por la operación.
        """
        stmt = (
            select(SoftwareApp)
            .where(SoftwareApp.app_status == AppStatus.ACTIVE.value)
            .options(
                selectinload(SoftwareApp.tags),
                selectinload(SoftwareApp.sources),
            )
            .order_by(SoftwareApp.id)
            .limit(limit + 1)
        )
        if after_app_id is not None:
            stmt = stmt.where(SoftwareApp.id > after_app_id)
        result = list((await self.session.scalars(stmt)).unique())
        has_more = len(result) > limit
        page = result[:limit]
        next_after = str(page[-1].id) if has_more and page else None
        return page, next_after

    async def mark_long_description_pending(self, software_app_id: uuid.UUID) -> None:
        """Marca la operación `long_description_pending`.

        Args:
            software_app_id (uuid.UUID): Identificador de `software_app` utilizado por la operación.
        """
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
        """Guarda la operación `long_description`.

        Args:
            software_app_id (uuid.UUID): Identificador de `software_app` utilizado por la operación.
            description (str): Valor de `description` utilizado por la operación.
            language (str): Valor de `language` utilizado por la operación.
            source (str): Fuente de descarga sobre la que se actúa.
            model (str): Modelo utilizado por la operación.
            input_hash (str): Valor de `input_hash` utilizado por la operación.
        """
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
        """Marca la operación `long_description_failed`.

        Args:
            software_app_id (uuid.UUID): Identificador de `software_app` utilizado por la operación.
            input_hash (str): Valor de `input_hash` utilizado por la operación.
            error (str): Error que debe registrarse o propagarse.
            source (str | None): Fuente de descarga sobre la que se actúa.
            model (str | None): Modelo utilizado por la operación.
        """
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
        """Ejecuta `expire_valid_resolved_sources` dentro de `CatalogRepository`.

        Args:
            source_id (uuid.UUID): Identificador de `source` utilizado por la operación.
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

    async def mark_source_status(
        self,
        source_id: uuid.UUID,
        resolution_status: ResolutionStatus,
        validation_status: ValidationStatus = ValidationStatus.UNCHECKED,
    ) -> None:
        """Marca la operación `source_status`.

        Args:
            source_id (uuid.UUID): Identificador de `source` utilizado por la operación.
            resolution_status (ResolutionStatus): Valor de `resolution_status` utilizado por la
                operación.
            validation_status (ValidationStatus): Valor de `validation_status` utilizado por la
                operación.
        """
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
        """Busca la operación `apps`.

        Args:
            query (str | None): Valor de `query` utilizado por la operación.
            status (str | None): Valor de `status` utilizado por la operación.
            page (int): Número de página solicitado.
            page_size (int): Número máximo de elementos incluidos en una página.
            sort (str): Valor de `sort` utilizado por la operación.

        Returns:
            tuple[list[SoftwareApp], int]: Colección de elementos obtenidos por la operación.
        """
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
                _public_resolved_sources_loader(),
            )
        )
        return list(result.unique()), int(total or 0)

    async def catalog_stats(self) -> dict:
        """Ejecuta `catalog_stats` dentro de `CatalogRepository`.

        Returns:
            dict: Mapa con los datos producidos por la operación.

        Throws:
            RuntimeError: Si el estado de ejecución impide completar la operación.
        """
        counters = await self.session.get(CatalogCounter, 1)
        if counters is None:
            raise RuntimeError("catalog_projection_not_initialized")

        latest_run = await self.session.scalar(
            select(ScrapeRun).order_by(ScrapeRun.started_at.desc()).limit(1)
        )
        return {
            "total": int(counters.total_count),
            "filters": {
                "all": int(counters.total_count),
                "available": int(counters.available_count),
                "review": int(counters.review_count),
                "missing": int(counters.missing_count),
            },
            "last_run": latest_run,
        }

    async def get_app_by_public_id(self, public_id: str) -> SoftwareApp | None:
        """Obtiene la operación `app_by_public_id`.

        Args:
            public_id (str): Identificador de `public` utilizado por la operación.

        Returns:
            SoftwareApp | None: Resultado de `get_app_by_public_id`.
        """
        conditions = [SoftwareApp.slug == public_id, SoftwareApp.winstall_id == public_id]
        try:
            conditions.append(SoftwareApp.id == uuid.UUID(public_id))
        except (TypeError, ValueError):
            pass
        stmt = (
            select(SoftwareApp)
            .options(
                _public_resolved_sources_loader(),
                selectinload(SoftwareApp.tags),
            )
            .where(SoftwareApp.app_status == AppStatus.ACTIVE.value)
            .where(or_(*conditions))
        )
        return await self.session.scalar(stmt)

    async def get_resolved_source_by_ref(self, source_ref: str) -> ResolvedSource | None:
        """Obtiene la operación `resolved_source_by_ref`.

        Args:
            source_ref (str): Valor de `source_ref` utilizado por la operación.

        Returns:
            ResolvedSource | None: Resultado de `get_resolved_source_by_ref`.
        """
        try:
            resolved_source_id = uuid.UUID(source_ref)
        except (TypeError, ValueError):
            return None
        return await self.session.scalar(
            select(ResolvedSource)
            .options(selectinload(ResolvedSource.source))
            .where(ResolvedSource.id == resolved_source_id)
        )

    async def get_resolved_source_by_ref_for_update(
        self,
        source_ref: str,
    ) -> ResolvedSource | None:
        """Obtiene la operación `resolved_source_by_ref_for_update`.

        Args:
            source_ref (str): Valor de `source_ref` utilizado por la operación.

        Returns:
            ResolvedSource | None: Resultado de `get_resolved_source_by_ref_for_update`.
        """
        try:
            resolved_source_id = uuid.UUID(source_ref)
        except (TypeError, ValueError):
            return None
        return await self.session.scalar(
            select(ResolvedSource)
            .join(ResolvedSource.source)
            .where(ResolvedSource.id == resolved_source_id)
            .with_for_update()
            .options(
                selectinload(ResolvedSource.source).selectinload(
                    DownloadSource.software_app
                )
            )
            .execution_options(populate_existing=True)
        )

    def reveal_url(self, resolved_source: ResolvedSource) -> str | None:
        """Ejecuta `reveal_url` dentro de `CatalogRepository`.

        Args:
            resolved_source (ResolvedSource): Valor de `resolved_source` utilizado por la operación.

        Returns:
            str | None: Resultado producido por la operación.
        """
        return self.url_protector.reveal(resolved_source.resolved_url_encrypted)

    def _base_app_query(self, query: str | None, status: str | None) -> Select:
        """Ejecuta el paso interno `_base_app_query`.

        Args:
            query (str | None): Valor de `query` utilizado por la operación.
            status (str | None): Valor de `status` utilizado por la operación.

        Returns:
            Select: Resultado producido por la operación.
        """
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
        if status and status != "all":
            stmt = stmt.where(SoftwareApp.catalog_status == status)
        return stmt


def _public_resolved_sources_loader():
    """Ejecuta el paso interno `_public_resolved_sources_loader`.
    """
    return (
        selectinload(SoftwareApp.sources)
        .selectinload(DownloadSource.resolved_sources)
        .options(
            with_expression(
                ResolvedSource.catalog_downloadable,
                CATALOG_DOWNLOADABLE_COLUMN,
            )
        )
    )


def has_icon_url(value: str | None) -> bool:
    """Indica si existe la operación `icon_url`.

    Args:
        value (str | None): Valor que debe procesarse.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    return bool(value and value.strip() and value.strip() != "-")


def has_verified_binary_history(
    validation_status: str,
    resolution_status: str,
    metadata: dict,
) -> bool:
    """Indica si existe la operación `verified_binary_history`.

    Args:
        validation_status (str): Valor de `validation_status` utilizado por la operación.
        resolution_status (str): Valor de `resolution_status` utilizado por la operación.
        metadata (dict): Valor de `metadata` utilizado por la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    confidence = str(metadata.get("validation_confidence") or "").lower()
    return (
        validation_status == ValidationStatus.VALID.value
        and resolution_status
        in {ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value}
        and confidence in {"", "validated", "verified"}
        and metadata.get("transport_security")
        not in {"https_winstall_edge_attested", "http_winstall_verified"}
    )


def is_github_homepage(value: str | None) -> bool:
    """Indica si se cumple la operación `github_homepage`.

    Args:
        value (str | None): Valor que debe procesarse.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    if not value:
        return False
    parsed = urlparse(value)
    return parsed.scheme == "https" and parsed.netloc.lower() in {"github.com", "www.github.com"}


def is_replaceable_github_icon(value: str | None) -> bool:
    """Indica si se cumple la operación `replaceable_github_icon`.

    Args:
        value (str | None): Valor que debe procesarse.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    if not has_icon_url(value):
        return True
    hostname = (urlparse(value or "").hostname or "").lower()
    return hostname == "opengraph.githubassets.com" or hostname.endswith(
        ".opengraph.githubassets.com"
    )


def has_current_available_installer(
    app: SoftwareApp,
    now: datetime | None = None,
) -> bool:
        # ``now`` permanece en la firma para llamadores anteriores a la proyección
        # persistente. El tiempo por sí solo ya no retira un candidato válido del catálogo.
    """Indica si existe la operación `current_available_installer`.

    Args:
        app (SoftwareApp): Aplicación sobre la que se realiza la operación.
        now (datetime | None): Valor de `now` utilizado por la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    del now
    for source in app.sources:
        if source.resolution_status not in AVAILABLE_RESOLUTION_STATUSES:
            continue
        if source.validation_status != ValidationStatus.VALID.value:
            continue
        for resolved in source.resolved_sources:
            if resolved.status not in AVAILABLE_RESOLUTION_STATUSES:
                continue
            if has_verified_binary_history(
                resolved.validation_status,
                resolved.status,
                resolved.metadata_json or {},
            ):
                return True
    return False


def registered_domain(url: str | None) -> str | None:
    """Ejecuta la operación `registered_domain`.

    Args:
        url (str | None): URL del recurso que debe procesarse.

    Returns:
        str | None: Resultado producido por la operación.
    """
    if not url:
        return None
    extracted = tldextract.extract(url)
    if not extracted.domain or not extracted.suffix:
        return None
    return f"{extracted.domain}.{extracted.suffix}".lower()


def inferred_platform_for_resolved_source(resolved: ResolvedSource) -> str | None:
    """Ejecuta la operación `inferred_platform_for_resolved_source`.

    Args:
        resolved (ResolvedSource): Valor de `resolved` utilizado por la operación.

    Returns:
        str | None: Resultado producido por la operación.
    """
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
    """Ejecuta la operación `inferred_architecture_for_resolved_source`.

    Args:
        resolved (ResolvedSource): Valor de `resolved` utilizado por la operación.
        fallback (str): Valor de `fallback` utilizado por la operación.

    Returns:
        str: Resultado producido por la operación.
    """
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
    return fallback or "UNKNOWN"


def normalized_extension(value: str | None) -> str | None:
    """Ejecuta la operación `normalized_extension`.

    Args:
        value (str | None): Valor que debe procesarse.

    Returns:
        str | None: Resultado producido por la operación.
    """
    if not value:
        return None
    extension = value.lower().strip()
    return extension if extension.startswith(".") else f".{extension}"


def is_manual_download_source(source: DownloadSource) -> bool:
    """Impide que una sincronización Winstall reutilice una fuente publicada a mano."""
    return source.resolver_type == "manual_http" or (
        (source.resolver_config or {}).get("source") == "admin_manual"
    )


def manual_field_sources(software_app: SoftwareApp) -> dict[str, str]:
    """Extrae únicamente la procedencia explícita de una revisión manual."""
    metadata = software_app.metadata_json or {}
    manual = metadata.get("manual_installer")
    if not isinstance(manual, dict):
        return {}
    sources = manual.get("field_sources")
    if not isinstance(sources, dict):
        return {}
    return {str(key): str(value) for key, value in sources.items()}


def parse_provider_datetime(value: object) -> datetime | None:
    """Normaliza marcas ISO de Winstall al DATETIME UTC sin zona usado por MySQL."""
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is not None:
        parsed = parsed.astimezone(UTC).replace(tzinfo=None)
    return parsed


def artifact_fingerprint(item: ResolvedSourceCreate) -> str:
    """Genera una identidad estable sin depender de tokens efímeros de la URL."""
    metadata = item.metadata or {}
    parsed = urlparse(item.url)
    payload = {
        "domain": (item.final_domain or parsed.hostname or "").lower(),
        "path": parsed.path,
        "filename": (item.filename or "").lower(),
        "extension": (item.extension or "").lower(),
        "size": item.size_bytes,
        "version": item.version,
        "sha256": metadata.get("sha256") or metadata.get("expected_sha256"),
        "operating_system": metadata.get("operating_system"),
        "architecture": metadata.get("architecture"),
    }
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def text_fingerprint(value: str | None) -> str | None:
    """Replica la huella estable usada por Core para una página oficial."""
    if value is None or not value.strip():
        return None
    return hashlib.sha256(value.strip().encode("utf-8")).hexdigest()
