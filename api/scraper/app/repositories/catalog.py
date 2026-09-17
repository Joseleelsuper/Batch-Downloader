"""Reúne consultas públicas y enriquecimiento del catálogo y compone publicación de fuentes y
sincronización de Winstall.
"""

import uuid

from sqlalchemy import Select, case, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import load_only, selectinload

from app.core.time import utc_after, utc_now
from app.core.url_protector import UrlProtector
from app.db.enums import (
    AppStatus,
    LongDescriptionStatus,
)
from app.db.models import (
    CatalogCounter,
    DownloadSource,
    ResolvedSource,
    ScrapeRun,
    SoftwareApp,
    SoftwareAppTag,
)
from app.repositories.catalog_rules import (
    _public_resolved_sources_loader,
    has_icon_url,
    is_replaceable_github_icon,
)
from app.repositories.catalog_sources import CatalogSourceStore
from app.repositories.catalog_winstall import WinstallCatalogStore
from app.scraper.text import normalize_text


class CatalogRepository:
    """Ofrece consultas del catálogo y actualización editorial sobre la sesión del caso de uso.
    Los colaboradores sources y winstall comparten esa misma sesión; estas operaciones no
    confirman por su cuenta.

    See Also:
        app.repositories.catalog_sources.CatalogSourceStore: Publica y mantiene fuentes
            concretas.
        app.repositories.catalog_winstall.WinstallCatalogStore: Sincroniza datos del proveedor
            sin perder edición manual.
    """

    def __init__(self, session: AsyncSession, url_protector: UrlProtector) -> None:
        """Conecta las consultas y ambos almacenes a una sesión y un cifrador comunes.

        Args:
            session: Sesión asíncrona del llamador; se comparte por composición, nunca entre
                workers concurrentes.
            url_protector: Cifrador del despliegue para URL privadas de las resoluciones.
        """
        self.session = session
        self.url_protector = url_protector
        self.sources = CatalogSourceStore(session, url_protector)
        self.winstall = WinstallCatalogStore(session, url_protector, self.sources)

    async def update_icon_url(self, software_app_id: uuid.UUID, icon_url: str) -> bool:
        """Sustituye solo iconos ausentes o de OpenGraph GitHub por un valor no vacío y aumenta
        la versión de la aplicación.

        Args:
            software_app_id: UUID de la aplicación propietaria.
            icon_url: Nuevo icono público propuesto.

        Returns:
            True si se actualizó la entidad; False si falta o su icono no es reemplazable.
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
        """Selecciona aplicaciones activas cuyo texto ampliado falta o contiene solo espacios,
        empezando por la modificación más antigua.

        Returns:
            aplicaciones que necesitan texto ampliado.
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
        """Selecciona identidades activas sin comprobación de plataformas en las últimas 24 horas
        y carga las entidades conservando ese orden.

        Args:
            limit: Máximo solicitado de aplicaciones; se utiliza al menos uno.

        Returns:
            lote ordenado de aplicaciones pendientes de comprobar.
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
                .order_by(
                    SoftwareApp.operating_systems_updated_at.asc(),
                    SoftwareApp.id.asc(),
                )
                .limit(max(1, limit))
            )
        )
        if not candidate_ids:
            return []

        result = await self.session.scalars(
            select(SoftwareApp)
            .options(
                load_only(
                    SoftwareApp.id,
                    SoftwareApp.winstall_id,
                    SoftwareApp.name,
                    SoftwareApp.version,
                )
            )
            .where(SoftwareApp.id.in_(candidate_ids))
        )
        apps_by_id = {app.id: app for app in result}
        return [apps_by_id[app_id] for app_id in candidate_ids if app_id in apps_by_id]

    async def apps_for_description_enrichment(
        self,
        app_ids: list[uuid.UUID] | None = None,
        *,
        include_completed: bool = False,
    ) -> list[SoftwareApp]:
        """Carga aplicaciones activas con fuentes y etiquetas; una selección explícita de UUID
        permite regenerarlas aunque estén completadas.
        Sin selección y sin include_completed, prioriza texto ausente, estado pendiente y
        fallo.

        Args:
            app_ids: UUID de aplicaciones seleccionadas; None no filtra y una lista vacía no
                selecciona ninguna.
            include_completed: True permite incluir descripciones ya completadas cuando no se
                proporcionan UUID explícitos.

        Returns:
            aplicaciones únicas con los datos necesarios para describirlas.
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
        stmt = (
            select(SoftwareApp)
            .where(SoftwareApp.app_status == AppStatus.ACTIVE.value)
            .options(
                selectinload(SoftwareApp.tags),
                selectinload(SoftwareApp.sources).selectinload(DownloadSource.resolved_sources),
            )
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
                        SoftwareApp.long_description_status == LongDescriptionStatus.PENDING.value,
                        1,
                    ),
                    (
                        SoftwareApp.long_description_status == LongDescriptionStatus.FAILED.value,
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
        """Lee aplicaciones activas por UUID y pide una fila adicional para saber si existe otra
        página, cargando etiquetas y fuentes.

        Args:
            after_app_id: Cursor exclusivo por UUID; None comienza al principio.
            limit: Máximo de filas del lote; el llamador proporciona un valor positivo.

        Returns:
            par de aplicaciones del lote y último UUID para continuar, o None cuando termina.
        """
        stmt = (
            select(SoftwareApp)
            .with_hint(SoftwareApp, "FORCE INDEX (PRIMARY)", dialect_name="mysql")
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
        """Marca la descripción como pendiente, limpia su error y hace flush; no modifica una
        aplicación inexistente ni confirma la transacción.

        Args:
            software_app_id: UUID de la aplicación propietaria.
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
        """Guarda texto, procedencia y huella, marca la generación completada y aumenta la
        versión; no actúa si la aplicación ya no existe.

        Args:
            software_app_id: UUID de la aplicación propietaria.
            description: Texto ampliado generado para la aplicación.
            language: Código del idioma del texto generado.
            source: Proveedor o procedencia de la descripción.
            model: Identificador del modelo utilizado, si se conoce.
            input_hash: Huella de las entradas que originaron la generación.
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
        """Guarda estado fallido, procedencia, huella y error truncado y aumenta la versión; no
        retira el texto ampliado anterior.

        Args:
            software_app_id: UUID de la aplicación propietaria.
            input_hash: Huella de las entradas que originaron la generación.
            error: Resumen del fallo; se conserva un máximo de 1000 caracteres.
            source: Proveedor o procedencia de la descripción.
            model: Identificador del modelo utilizado, si se conoce.
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

    async def search_apps(
        self,
        query: str | None,
        status: str | None,
        page: int,
        page_size: int,
        sort: str = "name",
    ) -> tuple[list[SoftwareApp], int]:
        """Cuenta y pagina aplicaciones activas con los mismos filtros y precarga etiquetas y
        resoluciones publicables para evitar consultas por fila.

        Args:
            query: Texto de búsqueda; None o vacío no restringe por texto.
            status: Estado público de catálogo; None o all no filtra disponibilidad.
            page: Página desde uno, validada antes de consultar.
            page_size: Número de aplicaciones por página, validado por la ruta.
            sort: updated ordena por modificación descendente; cualquier otro valor utiliza
                nombre normalizado ascendente.

        Returns:
            par de aplicaciones de la página y total de coincidencias.
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
        """Lee los contadores materializados y la ejecución más reciente para construir
        estadísticas del catálogo.

        Returns:
            totales, partición por disponibilidad y última ejecución.

        Raises:
            RuntimeError: catalog_projection_not_initialized si falta la fila singleton de
                contadores.
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
        """Busca una aplicación activa por slug, identidad Winstall o UUID y precarga sus
        etiquetas y fuentes para la respuesta pública.

        Args:
            public_id: UUID textual, slug o identificador Winstall de la aplicación.

        Returns:
            aplicación encontrada o None.
        """
        conditions = [SoftwareApp.slug == public_id, SoftwareApp.winstall_id == public_id]
        try:
            conditions.append(SoftwareApp.id == uuid.UUID(public_id))
        except TypeError, ValueError:
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
        """Busca una resolución exacta y carga su fuente sin adquirir un bloqueo de
        actualización.

        Args:
            source_ref: UUID textual de la resolución exacta solicitada.

        Returns:
            resolución o None si no existe o la referencia no es un UUID.
        """
        try:
            resolved_source_id = uuid.UUID(source_ref)
        except TypeError, ValueError:
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
        """Bloquea la resolución y su fuente en la consulta y refresca la identidad ORM con
        relaciones de fuente y aplicación cargadas.

        Args:
            source_ref: UUID textual de la resolución exacta solicitada.

        Returns:
            resolución actual bloqueada, o None ante referencia inválida o ausente.
        """
        try:
            resolved_source_id = uuid.UUID(source_ref)
        except TypeError, ValueError:
            return None
        return await self.session.scalar(
            select(ResolvedSource)
            .join(ResolvedSource.source)
            .where(ResolvedSource.id == resolved_source_id)
            .with_for_update()
            .options(selectinload(ResolvedSource.source).selectinload(DownloadSource.software_app))
            .execution_options(populate_existing=True)
        )

    def reveal_url(self, resolved_source: ResolvedSource) -> str | None:
        """Recupera el texto de la URL protegida usando el secreto del despliegue.

        Args:
            resolved_source: Resolución que contiene la URL protegida.

        Returns:
            URL original o None si el token no puede autenticarse.
        """
        return self.url_protector.reveal(resolved_source.resolved_url_encrypted)

    def _base_app_query(self, query: str | None, status: str | None) -> Select:
        """Construye los filtros compartidos de aplicaciones activas, texto normalizado y estado
        materializado de catálogo.

        Args:
            query: Texto de búsqueda; None o vacío no restringe por texto.
            status: Estado público de catálogo; None o all no filtra disponibilidad.

        Returns:
            consulta sin paginación, orden ni relaciones precargadas.
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
