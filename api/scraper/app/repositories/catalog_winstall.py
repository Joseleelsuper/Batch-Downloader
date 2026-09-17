"""Sincroniza catálogo Winstall con entidades locales, preservando campos manuales y las
evidencias que justifican disponibilidad o ausencia.
"""

import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.json_safe import json_safe
from app.core.time import utc_now
from app.core.url_protector import UrlProtector
from app.db.enums import (
    AppStatus,
    LongDescriptionStatus,
    ResolutionStatus,
    ValidationStatus,
)
from app.db.models import (
    DownloadSource,
    SoftwareApp,
    SoftwareAppTag,
)
from app.repositories.catalog_rules import (
    manual_field_sources,
    parse_provider_datetime,
    sync_provider_fields,
    text_fingerprint,
)
from app.repositories.catalog_sources import CatalogSourceStore
from app.scraper.text import normalize_text, slugify
from app.scraper.winstall import (
    WinstallApp,
    winstall_detail_fingerprint,
    winstall_summary_fingerprint,
)


class WinstallCatalogStore:
    """Actualiza metadatos, etiquetas y fuente Windows de aplicaciones importadas utilizando la
    sesión compartida por el catálogo.

    See Also:
        app.repositories.catalog_sources.CatalogSourceStore: Mantiene fuentes y retira actas
            cuya evidencia ha cambiado.
    """

    def __init__(
        self, session: AsyncSession, url_protector: UrlProtector, sources: CatalogSourceStore
    ) -> None:
        """Conecta sincronización y publicación de fuentes a los colaboradores cedidos por el
        catálogo.

        Args:
            session: Sesión asíncrona del llamador; se comparte por composición, nunca entre
                workers concurrentes.
            url_protector: Cifrador del despliegue para URL privadas de las resoluciones.
            sources: Almacén de fuentes que utiliza la misma sesión para mantener la operación
                en su transacción.
        """
        self.session = session
        self.url_protector = url_protector
        self.sources = sources

    async def should_scrape_winstall_package(
        self,
        package_id: str,
        *,
        force_refresh: bool = False,
    ) -> bool:
        """Solicita resolución si se fuerza, el paquete es nuevo o no conserva una fuente directa
        o fallback válida.

        Args:
            package_id: Identidad del paquete en Winstall.
            force_refresh: True obliga a resolver aunque ya exista una fuente válida.

        Returns:
            True cuando el paquete necesita pasar por el resolutor.
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
        """Carga en una consulta las identidades, estados públicos y huellas de resumen de todas
        las aplicaciones activas.

        Returns:
            mapa por paquete para comparar el catálogo entrante sin consultar por aplicación.
        """
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
        """Captura aplicaciones activas ordenadas por UUID con filtros opcionales de
        disponibilidad y selección explícita.

        Args:
            statuses: Estados de catálogo admitidos; None no filtra y el conjunto vacío no
                selecciona filas.
            app_ids: UUID de aplicaciones seleccionadas; None no filtra y una lista vacía no
                selecciona ninguna.

        Returns:
            conjunto ordenado de entidades que fija el alcance del refresco.
        """
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

    async def upsert_winstall_app(self, app: WinstallApp) -> SoftwareApp:
        """Sincroniza la aplicación por identidad Winstall y omite la marca auxiliar de creación.

        Args:
            app: Datos de la aplicación recibidos de Winstall.

        Returns:
            entidad local creada o actualizada.
        """
        software_app, _created = await self.upsert_winstall_app_with_created(app)
        return software_app

    async def upsert_winstall_app_with_created(
        self,
        app: WinstallApp,
    ) -> tuple[SoftwareApp, bool]:
        """Actualiza una aplicación existente o crea su identidad, metadatos, etiquetas y fuente
        inicial y recarga el estado calculado.

        Args:
            app: Datos de la aplicación recibidos de Winstall.

        Returns:
            par de entidad local y True solo si se acaba de crear.
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
        """Hace flush y recarga únicamente la columna calculada de disponibilidad para observar
        los cambios dentro de la transacción.

        Args:
            software_app: Entidad de aplicación de la sesión actual.
        """
        await self.session.flush()
        await self.session.refresh(software_app, attribute_names=["catalog_status"])

    async def _sync_existing_winstall_app(
        self,
        software_app: SoftwareApp,
        app: WinstallApp,
    ) -> None:
        """Aplica cambios del proveedor conservando metadatos y campos manuales, compara huellas
        de ausencia y sincroniza etiquetas y fuente inicial.

        Args:
            software_app: Entidad de aplicación de la sesión actual.
            app: Datos de la aplicación recibidos de Winstall.
        """
        old_summary = software_app.winstall_summary_fingerprint
        old_detail = software_app.winstall_detail_fingerprint
        new_summary = winstall_summary_fingerprint(app)
        new_detail = winstall_detail_fingerprint(app) if app.installer_data_complete else old_detail
        verification = (
            await self.sources.active_absence_verification(software_app.id)
            if software_app.catalog_status == "missing"
            else None
        )
        manual_fields = manual_field_sources(software_app)
        changed = sync_provider_fields(software_app, app, manual_fields)

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
            or verification.official_url_fingerprint != text_fingerprint(software_app.official_url)
        )
        if evidence_change:
            await self.sources.invalidate_absence_verifications(
                software_app.id,
                "winstall_changed_or_candidate_appeared",
            )

        await self._sync_tags(software_app, app.tags)
        await self._ensure_default_source(software_app, app)
        if changed:
            software_app.updated_at = utc_now()
            software_app.version += 1

    async def promote_winstall_latest_version(self, software_app_id: uuid.UUID) -> bool:
        """Publica la versión reciente conocida de Winstall si difiere de la visible y no fue
        fijada manualmente.

        Args:
            software_app_id: UUID de la aplicación propietaria.

        Returns:
            True si cambió la versión visible de la aplicación.
        """
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
        """Normaliza y limita a 120 caracteres las etiquetas Winstall; añade, actualiza o retira
        solo las de ese origen y aumenta la versión si cambian.

        Args:
            software_app: Entidad de aplicación de la sesión actual.
            raw_tags: Etiquetas recibidas del proveedor antes de normalización y límite de
                longitud.
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

    async def _ensure_default_source(self, software_app: SoftwareApp, app: WinstallApp) -> None:
        """Garantiza una fuente automática Windows con arquitectura UNKNOWN y página inicial del
        proveedor.

        Args:
            software_app: Entidad de aplicación de la sesión actual.
            app: Datos de la aplicación recibidos de Winstall.
        """
        await self.sources.ensure_download_source(
            software_app_id=software_app.id,
            app=app,
            operating_system="windows",
            architecture="UNKNOWN",
            initial_url=app.homepage,
        )
