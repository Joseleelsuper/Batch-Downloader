"""Adapta operaciones autenticadas de resolución, enriquecimiento e inspección a contratos HTTP y
confirma sus cambios antes de responder.

See Also:
    app.api.dependencies.require_internal_service_token: Autentica a Core, worker e indexador.
    app.application.source_resolution.resolve_source: Mantiene la política de resolución fuera
        de la ruta HTTP.
"""

from __future__ import annotations

import secrets as secrets
from typing import Annotated, cast
from uuid import UUID

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Query
from fastapi.responses import JSONResponse, PlainTextResponse
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.pool import AsyncAdaptedQueuePool

from app.api.dependencies import (
    INTERNAL_SERVICE_TOKEN_HEADER as INTERNAL_SERVICE_TOKEN_HEADER,
)
from app.api.dependencies import (
    require_internal_service_token,
)
from app.application.source_resolution import (
    SourceNotFoundError,
    SourceRevalidationTransientError,
    resolve_source,
)
from app.core.config import Settings, get_settings
from app.core.url_protector import UrlProtector
from app.db.session import engine, get_session
from app.domain.source_resolution import SourceTrustStatus
from app.repositories.catalog import CatalogRepository
from app.repositories.pipeline import (
    QUEUE_SO_FILTER_DESCRIPTOR,
    PipelineRepository,
)
from app.schemas.internal import (
    ContentEnqueueResult,
    GenerateDescriptionRequest,
    GenerateDescriptionResult,
    InternalSourceResolution,
    ManualInstallerApplyRequest,
    ManualInstallerApplyResult,
    ManualInstallerInspectionRequest,
    ManualInstallerInspectionView,
    SemanticDocument,
    SemanticDocumentPage,
    WebsiteAppDiscoveryApplyRequest,
    WebsiteAppDiscoveryApplyResult,
    WebsiteAppDiscoveryRequest,
    WebsiteAppDiscoveryView,
)
from app.scraper.content_workers import (
    DescriptorWorker,
    enqueue_descriptor_for_app,
)
from app.scraper.description_enricher import (
    build_embedding_metadata,
    build_embedding_text,
    embedding_content_hash,
)
from app.scraper.manual_installer import (
    ManualInstallerError,
    ManualInstallerInspectionRepository,
    ManualInstallerTransientError,
    inspection_view,
)
from app.scraper.manual_installer_apply import apply_manual_installer
from app.scraper.safe_http import SafeHttpError
from app.scraper.website_discovery import (
    WebsiteAppDiscoveryRepository,
    WebsiteDiscoveryError,
    WebsiteDiscoveryTransientError,
    apply_website_app_discovery,
    website_discovery_view,
)

internal_router = APIRouter(prefix="/internal/v1")



@internal_router.get("/metrics", response_class=PlainTextResponse, responses={401: {}})
async def internal_metrics(
    _authorized: Annotated[None, Depends(require_internal_service_token)],
) -> PlainTextResponse:
    """Lee contadores del pool asíncrono y los expone como métricas gauge en formato de texto
    Prometheus.

    Args:
        _authorized: Dependencia que ha comprobado el secreto interno antes de ejecutar la
            ruta.

    Returns:
        tamaño, conexiones ocupadas, disponibles y de desbordamiento del pool.
    """
    pool = cast(AsyncAdaptedQueuePool, engine.pool)
    values = {
        "scraper_db_pool_size": pool.size(),
        "scraper_db_pool_checked_out": pool.checkedout(),
        "scraper_db_pool_checked_in": pool.checkedin(),
        "scraper_db_pool_overflow": pool.overflow(),
    }
    body = "".join(f"# TYPE {name} gauge\n{name} {value}\n" for name, value in values.items())
    return PlainTextResponse(body, media_type="text/plain; version=0.0.4")


@internal_router.get(
    "/semantic/documents",
    response_model=SemanticDocumentPage,
    response_model_by_alias=True,
    responses={401: {}},
)
async def semantic_documents(
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
    after_app_id: Annotated[
        UUID | None,
        Query(alias="afterAppId"),
    ] = None,
    limit: Annotated[int, Query(ge=1, le=500)] = 500,
) -> SemanticDocumentPage:
    """Pagina aplicaciones activas por UUID y construye texto, huella y metadatos para que el
    indexador reconozca cambios.

    Args:
        _authorized: Dependencia que ha comprobado el secreto interno antes de ejecutar la
            ruta.
        session: Sesión independiente de la petición; las escrituras se confirman antes de
            responder.
        settings: Configuración validada de salud, límites y credenciales internas del
            servicio.
        after_app_id: Cursor exclusivo por UUID; None comienza desde el primer documento.
        limit: Máximo entre uno y 500 documentos, validado por FastAPI.

    Returns:
        lote de documentos y cursor exclusivo para la siguiente página.
    """
    catalog = CatalogRepository(session, UrlProtector(settings.url_protection_secret))
    apps, next_after = await catalog.semantic_documents(
        after_app_id=after_app_id,
        limit=limit,
    )
    return SemanticDocumentPage(
        documents=[
            SemanticDocument(
                appId=str(software_app.id),
                contentHash=embedding_content_hash(software_app),
                content=build_embedding_text(software_app),
                metadata=build_embedding_metadata(software_app),
            )
            for software_app in apps
        ],
        nextAfterAppId=next_after,
    )


@internal_router.get(
    "/sources/{source_ref}/resolution",
    response_model=InternalSourceResolution,
    response_model_by_alias=True,
    responses={
        401: {},
        404: {},
        409: {"model": InternalSourceResolution},
        503: {},
    },
)
async def get_source_resolution(
    source_ref: str,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> InternalSourceResolution | JSONResponse:
    """Delega la resolución exacta y traduce confianza insuficiente a 409, conservando el mismo
    cuerpo de respuesta con estado de confianza.

    Args:
        source_ref: Referencia exacta de resolución que debe pertenecer a la aplicación cuando
            la ruta la especifica.
        _authorized: Dependencia que ha comprobado el secreto interno antes de ejecutar la
            ruta.
        session: Sesión independiente de la petición; las escrituras se confirman antes de
            responder.
        settings: Configuración validada de salud, límites y credenciales internas del
            servicio.

    Returns:
        resolución verificada con HTTP 200 o cuerpo de resolución no verificada con HTTP 409.

    Raises:
        fastapi.HTTPException: 404 si falta la fuente; 503 si la revalidación falla
            transitoriamente.
    """
    try:
        response = await resolve_source(source_ref, session, settings)
    except SourceNotFoundError as exception:
        raise HTTPException(status_code=404, detail={"code": str(exception)}) from exception
    except SourceRevalidationTransientError as exception:
        raise HTTPException(status_code=503, detail={"code": str(exception)}) from exception
    if response.trust_status != SourceTrustStatus.VERIFIED:
        return JSONResponse(
            status_code=409, content=response.model_dump(by_alias=True, mode="json")
        )
    return response


@internal_router.post(
    "/content/descriptions/enqueue-missing",
    status_code=202,
    response_model=ContentEnqueueResult,
    response_model_by_alias=True,
)
async def enqueue_missing_descriptions(
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> ContentEnqueueResult:
    """Selecciona aplicaciones sin descripción ampliada y encola generación prioritaria solo
    cuando no tienen tarea activa; confirma el lote.

    Args:
        _authorized: Dependencia que ha comprobado el secreto interno antes de ejecutar la
            ruta.
        session: Sesión independiente de la petición; las escrituras se confirman antes de
            responder.
        settings: Configuración validada de salud, límites y credenciales internas del
            servicio.

    Returns:
        cantidades encontradas, encoladas y ya activas, con HTTP 202.
    """
    catalog = CatalogRepository(session, UrlProtector(settings.url_protection_secret))
    pipeline = PipelineRepository(session)
    matched = 0
    enqueued = 0
    already_active = 0
    for app in await catalog.apps_missing_long_descriptions():
        matched += 1
        if await pipeline.has_active_item(QUEUE_SO_FILTER_DESCRIPTOR, app.winstall_id):
            already_active += 1
            continue
        item = await enqueue_descriptor_for_app(
            catalog,
            pipeline,
            None,
            app,
            force=True,
            priority=100,
        )
        if item:
            enqueued += 1
    await session.commit()
    return ContentEnqueueResult(
        matched=matched,
        enqueued=enqueued,
        alreadyActive=already_active,
    )


@internal_router.post(
    "/content/descriptions/generate",
    status_code=202,
    response_model=GenerateDescriptionResult,
    response_model_by_alias=True,
    responses={401: {}, 404: {}, 409: {}},
)
async def generate_description(
    request: GenerateDescriptionRequest,
    background_tasks: BackgroundTasks,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> GenerateDescriptionResult:
    """Busca una aplicación activa, fuerza una tarea prioritaria de descripción y confirma antes
    de impulsar un consumidor en segundo plano.

    Args:
        request: Cuerpo validado con los datos de la operación solicitada.
        background_tasks: Ejecutor de tareas posterior a la respuesta para impulsar la
            generación encolada.
        _authorized: Dependencia que ha comprobado el secreto interno antes de ejecutar la
            ruta.
        session: Sesión independiente de la petición; las escrituras se confirman antes de
            responder.
        settings: Configuración validada de salud, límites y credenciales internas del
            servicio.

    Returns:
        identidad y estado de la tarea con HTTP 202.

    Raises:
        fastapi.HTTPException: 404 si falta la aplicación o 409 si no se crea una tarea porque
            la descripción ya es actual.
    """
    catalog = CatalogRepository(session, UrlProtector(settings.url_protection_secret))
    app = await catalog.get_app_by_public_id(request.app_id)
    if not app:
        raise HTTPException(status_code=404, detail={"code": "app_not_found"})
    item = await enqueue_descriptor_for_app(
        catalog,
        PipelineRepository(session),
        None,
        app,
        force=True,
        priority=100,
    )
    if not item:
        raise HTTPException(
            status_code=409,
            detail={"code": "description_already_current"},
        )
    await session.commit()
    background_tasks.add_task(_run_descriptor_once_background)
    return GenerateDescriptionResult(jobId=str(item.id), status=item.status)


@internal_router.post(
    "/admin/apps/{app_id}/manual-installer-inspections",
    status_code=202,
    response_model=ManualInstallerInspectionView,
    response_model_by_alias=True,
    responses={401: {}, 404: {}, 409: {}, 422: {}},
)
async def create_manual_installer_inspection(
    app_id: UUID,
    request: ManualInstallerInspectionRequest,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> ManualInstallerInspectionView:
    """Crea o reutiliza una inspección con sus URL por plataforma y página de origen y confirma
    la reserva recuperable.

    Args:
        app_id: UUID de la aplicación propietaria de la inspección, receta o dependencias.
        request: Cuerpo validado con los datos de la operación solicitada.
        _authorized: Dependencia que ha comprobado el secreto interno antes de ejecutar la
            ruta.
        session: Sesión independiente de la petición; las escrituras se confirman antes de
            responder.
        settings: Configuración validada de salud, límites y credenciales internas del
            servicio.

    Returns:
        vista de inspección con HTTP 202, sin publicar todavía fuentes.

    Raises:
        fastapi.HTTPException: Propaga el estado clasificado de la inspección o 422/503 según
            el fallo de HTTP seguro.
    """
    repository = ManualInstallerInspectionRepository(
        session,
        UrlProtector(settings.url_protection_secret),
        settings,
    )
    try:
        inspection, _created = await repository.create_or_reuse(
            app_id,
            request.installer_url,
            request.source_page_url,
            request.installer_urls.model_dump(),
        )
    except (ManualInstallerError, SafeHttpError) as exc:
        raise_manual_installer_http_error(exc)
    await session.commit()
    return ManualInstallerInspectionView.model_validate(inspection_view(inspection))


@internal_router.get(
    "/admin/apps/{app_id}/manual-installer-inspections/current",
    response_model=ManualInstallerInspectionView,
    response_model_by_alias=True,
    responses={401: {}, 404: {}},
)
async def current_manual_installer_inspection(
    app_id: UUID,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> ManualInstallerInspectionView:
    """Recupera la inspección actual de la aplicación y confirma posibles transiciones de
    caducidad antes de construir su vista.

    Args:
        app_id: UUID de la aplicación propietaria de la inspección, receta o dependencias.
        _authorized: Dependencia que ha comprobado el secreto interno antes de ejecutar la
            ruta.
        session: Sesión independiente de la petición; las escrituras se confirman antes de
            responder.
        settings: Configuración validada de salud, límites y credenciales internas del
            servicio.

    Returns:
        inspección recuperable actual.

    Raises:
        fastapi.HTTPException: 404 con inspection_not_found cuando no existe una inspección
            actual.
    """
    repository = ManualInstallerInspectionRepository(
        session,
        UrlProtector(settings.url_protection_secret),
        settings,
    )
    inspection = await repository.current(app_id)
    if inspection is None:
        raise HTTPException(status_code=404, detail={"code": "inspection_not_found"})
    await session.commit()
    return ManualInstallerInspectionView.model_validate(inspection_view(inspection))


@internal_router.get(
    "/admin/apps/{app_id}/manual-installer-inspections/{inspection_id}",
    response_model=ManualInstallerInspectionView,
    response_model_by_alias=True,
    responses={401: {}, 404: {}},
)
async def get_manual_installer_inspection(
    app_id: UUID,
    inspection_id: UUID,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> ManualInstallerInspectionView:
    """Recupera una inspección por sus identidades de aplicación y operación y confirma posibles
    cambios de vigencia.

    Args:
        app_id: UUID de la aplicación propietaria de la inspección, receta o dependencias.
        inspection_id: UUID de la inspección persistida dentro de esa aplicación.
        _authorized: Dependencia que ha comprobado el secreto interno antes de ejecutar la
            ruta.
        session: Sesión independiente de la petición; las escrituras se confirman antes de
            responder.
        settings: Configuración validada de salud, límites y credenciales internas del
            servicio.

    Returns:
        vista de la inspección solicitada.

    Raises:
        fastapi.HTTPException: 404 con inspection_not_found si no pertenece a esa aplicación o
            no existe.
    """
    repository = ManualInstallerInspectionRepository(
        session,
        UrlProtector(settings.url_protection_secret),
        settings,
    )
    inspection = await repository.get(app_id, inspection_id)
    if inspection is None:
        raise HTTPException(status_code=404, detail={"code": "inspection_not_found"})
    await session.commit()
    return ManualInstallerInspectionView.model_validate(inspection_view(inspection))


@internal_router.post(
    "/admin/apps/{app_id}/manual-installer-inspections/{inspection_id}/apply",
    response_model=ManualInstallerApplyResult,
    response_model_by_alias=True,
    responses={401: {}, 404: {}, 409: {}, 422: {}, 503: {}},
)
async def apply_manual_installer_inspection(
    app_id: UUID,
    inspection_id: UUID,
    request: ManualInstallerApplyRequest,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> ManualInstallerApplyResult:
    """Delega la aplicación de valores revisados y fuentes inspeccionadas, confirma el resultado
    y publica todas las referencias creadas.

    Args:
        app_id: UUID de la aplicación propietaria de la inspección, receta o dependencias.
        inspection_id: UUID de la inspección persistida dentro de esa aplicación.
        request: Cuerpo validado con los datos de la operación solicitada.
        _authorized: Dependencia que ha comprobado el secreto interno antes de ejecutar la
            ruta.
        session: Sesión independiente de la petición; las escrituras se confirman antes de
            responder.
        settings: Configuración validada de salud, límites y credenciales internas del
            servicio.

    Returns:
        aplicación, nueva versión, fuente principal, fuentes completas y avisos.

    Raises:
        fastapi.HTTPException: Fallo clasificado de validación o conflicto; 503 para una
            comprobación transitoria.
    """
    try:
        app, source_refs, warnings = await apply_manual_installer(
            session,
            settings,
            app_id,
            inspection_id,
            request,
        )
    except (ManualInstallerError, ManualInstallerTransientError, SafeHttpError) as exc:
        raise_manual_installer_http_error(exc)
    await session.commit()
    return ManualInstallerApplyResult(
        appId=str(app.id),
        sourceRef=str(source_refs[0]),
        sourceRefs=[str(source_ref) for source_ref in source_refs],
        appVersion=app.version,
        catalogStatus="available",
        warnings=warnings,
    )


@internal_router.post(
    "/admin/app-discoveries",
    status_code=202,
    response_model=WebsiteAppDiscoveryView,
    response_model_by_alias=True,
    responses={401: {}, 409: {}, 422: {}},
)
async def create_website_app_discovery(
    request: WebsiteAppDiscoveryRequest,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> WebsiteAppDiscoveryView:
    """Crea o reutiliza un descubrimiento a partir de web oficial e instaladores aportados y
    confirma la operación antes de devolver su estado.

    Args:
        request: Cuerpo validado con los datos de la operación solicitada.
        _authorized: Dependencia que ha comprobado el secreto interno antes de ejecutar la
            ruta.
        session: Sesión independiente de la petición; las escrituras se confirman antes de
            responder.
        settings: Configuración validada de salud, límites y credenciales internas del
            servicio.

    Returns:
        vista recuperable con HTTP 202.

    Raises:
        fastapi.HTTPException: Fallo clasificado de la entrada o del acceso HTTP seguro.
    """
    repository = WebsiteAppDiscoveryRepository(
        session,
        UrlProtector(settings.url_protection_secret),
        settings,
    )
    try:
        discovery, _created = await repository.create_or_reuse(
            request.official_url,
            request.installer_urls.model_dump(),
        )
    except (WebsiteDiscoveryError, SafeHttpError) as exc:
        raise_website_discovery_http_error(exc)
    await session.commit()
    return WebsiteAppDiscoveryView.model_validate(website_discovery_view(discovery))


@internal_router.get(
    "/admin/app-discoveries/{discovery_id}",
    response_model=WebsiteAppDiscoveryView,
    response_model_by_alias=True,
    responses={401: {}, 404: {}},
)
async def get_website_app_discovery(
    discovery_id: UUID,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> WebsiteAppDiscoveryView:
    """Recupera el descubrimiento solicitado y confirma transiciones de caducidad antes de
    serializarlo.

    Args:
        discovery_id: UUID de la operación de descubrimiento que se recupera o aplica.
        _authorized: Dependencia que ha comprobado el secreto interno antes de ejecutar la
            ruta.
        session: Sesión independiente de la petición; las escrituras se confirman antes de
            responder.
        settings: Configuración validada de salud, límites y credenciales internas del
            servicio.

    Returns:
        estado, propuestas e instaladores conocidos del descubrimiento.

    Raises:
        fastapi.HTTPException: 404 con website_discovery_not_found si la operación no existe.
    """
    repository = WebsiteAppDiscoveryRepository(
        session,
        UrlProtector(settings.url_protection_secret),
        settings,
    )
    discovery = await repository.get(discovery_id)
    if discovery is None:
        raise HTTPException(
            status_code=404,
            detail={"code": "website_discovery_not_found"},
        )
    await session.commit()
    return WebsiteAppDiscoveryView.model_validate(website_discovery_view(discovery))


@internal_router.post(
    "/admin/app-discoveries/{discovery_id}/apply",
    response_model=WebsiteAppDiscoveryApplyResult,
    response_model_by_alias=True,
    responses={401: {}, 404: {}, 409: {}, 422: {}, 503: {}},
)
async def apply_website_app_discovery_route(
    discovery_id: UUID,
    request: WebsiteAppDiscoveryApplyRequest,
    _authorized: Annotated[None, Depends(require_internal_service_token)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> WebsiteAppDiscoveryApplyResult:
    """Delega la publicación del descubrimiento, confirma la aplicación creada y comunica
    disponibilidad e instaladores publicados.

    Args:
        discovery_id: UUID de la operación de descubrimiento que se recupera o aplica.
        request: Cuerpo validado con los datos de la operación solicitada.
        _authorized: Dependencia que ha comprobado el secreto interno antes de ejecutar la
            ruta.
        session: Sesión independiente de la petición; las escrituras se confirman antes de
            responder.
        settings: Configuración validada de salud, límites y credenciales internas del
            servicio.

    Returns:
        identidad y versión de aplicación, estado de catálogo, número de instaladores y
            avisos.

    Raises:
        fastapi.HTTPException: Error de validación, conflicto o ausencia clasificados; 503
            para fallos transitorios.
    """
    try:
        app, installer_count, warnings = await apply_website_app_discovery(
            session,
            settings,
            discovery_id,
            request,
        )
    except (
        WebsiteDiscoveryError,
        WebsiteDiscoveryTransientError,
        SafeHttpError,
    ) as exc:
        raise_website_discovery_http_error(exc)
    await session.commit()
    return WebsiteAppDiscoveryApplyResult(
        appId=str(app.id),
        appVersion=app.version,
        catalogStatus=app.catalog_status or "missing",
        installerCount=installer_count,
        warnings=warnings,
    )


def raise_manual_installer_http_error(
    error: ManualInstallerError | ManualInstallerTransientError | SafeHttpError,
) -> None:
    """Conserva estado y código de errores manuales, asigna 503 a transitorios y distingue
    422/503 en errores de HTTP seguro.

    Args:
        error: Fallo clasificado del caso de uso o de la política HTTP segura.

    Raises:
        fastapi.HTTPException: Siempre, con el código estable y causa original del fallo de
            inspección.
    """
    if isinstance(error, ManualInstallerError):
        status_code = error.status_code
        code = error.code
    elif isinstance(error, ManualInstallerTransientError):
        status_code = 503
        code = error.code
    else:
        status_code = 503 if error.transient else 422
        code = error.code
    raise HTTPException(status_code=status_code, detail={"code": code}) from error


def raise_website_discovery_http_error(
    error: WebsiteDiscoveryError | WebsiteDiscoveryTransientError | SafeHttpError,
) -> None:
    """Conserva estado y código de errores del descubrimiento y traduce los fallos transitorios a
    503 y restricciones HTTP definitivas a 422.

    Args:
        error: Fallo clasificado del caso de uso o de la política HTTP segura.

    Raises:
        fastapi.HTTPException: Siempre, manteniendo el código estable y la causa del
            descubrimiento.
    """
    if isinstance(error, WebsiteDiscoveryError):
        status_code = error.status_code
        code = error.code
    elif isinstance(error, WebsiteDiscoveryTransientError):
        status_code = 503
        code = error.code
    else:
        status_code = 503 if error.transient else 422
        code = error.code
    raise HTTPException(status_code=status_code, detail={"code": code}) from error


async def _run_descriptor_once_background() -> None:
    """Crea un worker de descripciones con la configuración del proceso e intenta consumir una
    tarea ya persistida.
    """
    await DescriptorWorker(get_settings()).process_one()
