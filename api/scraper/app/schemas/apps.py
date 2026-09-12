"""Define las respuestas públicas del catálogo con metadatos y referencias opacas de instalador,
sin URL de descarga privadas.

See Also:
    app.api.app_mapper: Convierte entidades cargadas a estos modelos usando alias camelCase.
"""
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class ErrorResponse(BaseModel):
    """Presenta un fallo de API con código estable, estado y explicación legible.

    Attributes:
        code: Identificador que utiliza el cliente para interpretar el fallo.
        status: Clasificación textual del resultado.
        message: Descripción destinada al consumidor.
    """
    code: str

    status: str

    message: str



class AppListItem(BaseModel):
    """Resume una aplicación para el listado con metadatos, plataformas y disponibilidad de su
    mejor fuente.

    Attributes:
        id, slug, package_id: UUID interno, identificador de ruta e identidad del catálogo de
            origen.
        name, publisher, description, long_description: Nombre, editor y textos de
            presentación; los datos ausentes se expresan como None.
        tags, operating_systems: Etiquetas y plataformas publicadas.
        icon_url, latest_version: Icono público y versión más reciente conocida.
        source_label, resolution_status, validation_status: Origen legible y resultados de
            resolución y validación.
        downloadable: Indica que el mapeador encontró al menos una resolución publicable.
        updated_at: Última modificación de la aplicación.
    """
    model_config = ConfigDict(populate_by_name=True)


    id: str

    slug: str

    package_id: str = Field(alias="packageId")

    name: str

    publisher: str | None = None

    description: str | None = None

    long_description: str | None = Field(default=None, alias="longDescription")

    tags: list[str] = Field(default_factory=list)

    operating_systems: list[str] = Field(default_factory=list, alias="operatingSystems")

    icon_url: str | None = Field(default=None, alias="iconUrl")

    latest_version: str | None = Field(default=None, alias="latestVersion")

    source_label: str = Field(alias="sourceLabel")

    resolution_status: str = Field(alias="resolutionStatus")

    validation_status: str = Field(alias="validationStatus")

    downloadable: bool

    updated_at: datetime = Field(alias="updatedAt")



class AppSearchResponse(BaseModel):
    """Devuelve una página del catálogo y el total para que el cliente pueda paginar sin
    inferirlo del número de filas.

    Attributes:
        data: Aplicaciones de esta página en el orden de consulta.
        page, page_size: Página solicitada, desde uno, y tamaño por página.
        total: Número de aplicaciones que cumplen los filtros.
    """
    model_config = ConfigDict(populate_by_name=True)


    data: list[AppListItem]

    page: int

    page_size: int = Field(alias="pageSize")

    total: int



class CatalogFilterStats(BaseModel):
    """Expone los recuentos por disponibilidad que forman los filtros del catálogo.

    Attributes:
        all: Total de aplicaciones incluidas en el catálogo.
        available, review, missing: Aplicaciones disponibles, pendientes de revisión y sin
            instalador.
    """
    all: int

    available: int

    review: int

    missing: int



class LastScrapeRun(BaseModel):
    """Resume estado, tiempos y progreso de la ejecución más reciente del scraper.

    Attributes:
        status: Resultado o estado de la ejecución.
        started_at, heartbeat_at, finished_at: Inicio, último latido y fin; el fin es None
            mientras no ha terminado.
        apps_discovered, apps_resolved, apps_failed, apps_skipped: Contadores de aplicaciones
            descubiertas, resueltas, fallidas y omitidas.
    """
    model_config = ConfigDict(populate_by_name=True)


    status: str

    started_at: datetime = Field(alias="startedAt")

    heartbeat_at: datetime = Field(alias="heartbeatAt")

    finished_at: datetime | None = Field(default=None, alias="finishedAt")

    apps_discovered: int = Field(alias="appsDiscovered")

    apps_resolved: int = Field(alias="appsResolved")

    apps_failed: int = Field(alias="appsFailed")

    apps_skipped: int = Field(default=0, alias="appsSkipped")



class CatalogStatsResponse(BaseModel):
    """Agrupa contadores del catálogo y última ejecución con el instante de generación de la
    respuesta.

    Attributes:
        total, filters: Total de aplicaciones y partición por estado del catálogo.
        last_scrape: Resumen de la última ejecución, o None si aún no existe.
        generated_at: Instante en que se construyó esta respuesta.
    """
    model_config = ConfigDict(populate_by_name=True)


    total: int

    filters: CatalogFilterStats

    last_scrape: LastScrapeRun | None = Field(default=None, alias="lastScrape")

    generated_at: datetime = Field(alias="generatedAt")



class DownloadOption(BaseModel):
    """Identifica una resolución concreta que el usuario puede seleccionar conservando
    plataforma, arquitectura y versión.

    Attributes:
        id: UUID de la resolución que debe viajar como sourceRef en la descarga.
        filename, extension, final_domain: Nombre, formato y dominio del artefacto; no
            contienen la URL privada completa.
        operating_system, architecture: Plataforma y arquitectura de esta fuente.
        version, is_latest, version_status: Versión reconocida y relación con las
            publicaciones recientes.
        source_label, score, is_primary: Origen legible, puntuación de preferencia y marca de
            opción principal.
    """
    model_config = ConfigDict(populate_by_name=True)


    id: str

    filename: str | None = None

    extension: str | None = None

    operating_system: str = Field(alias="operatingSystem")

    architecture: str

    version: str | None = None

    is_latest: bool = Field(default=False, alias="isLatest")

    version_status: str | None = Field(default=None, alias="versionStatus")

    source_label: str = Field(alias="sourceLabel")

    score: int

    final_domain: str | None = Field(default=None, alias="finalDomain")

    is_primary: bool = Field(alias="isPrimary")



class AppDetails(BaseModel):
    """Presenta una aplicación y sus opciones de descarga ordenadas, incluida la referencia
    exacta de cada instalador.
    Los metadatos de instalador principal quedan en None cuando ninguna fuente es publicable.

    Attributes:
        id, slug, package_id, name: Identidades de la aplicación y nombre público.
        publisher, description, long_description, tags, icon_url: Información editorial y
            visual del catálogo.
        operating_systems, latest_version: Plataformas publicadas y versión reciente conocida.
        official_url, origin_url: Página oficial y página de procedencia de la aplicación.
        installer_filename, installer_type, content_type, size_bytes: Nombre, formato, MIME y
            bytes conocidos del instalador principal.
        final_domain, score, source_label: Dominio, puntuación y origen legible de la opción
            principal.
        resolution_status, validation_status, downloadable: Estado de resolución, validación y
            existencia de una opción descargable.
        updated_at, checked_at, expires_at: Modificación de aplicación, comprobación del
            instalador y caducidad de su validación.
        download_options: Opciones concretas ordenadas por preferencia.
        notes: Explicación de origen, revisión necesaria o ausencia de instalador.

    See Also:
        DownloadOption: Conserva la identidad seleccionable de cada resolución.
    """
    model_config = ConfigDict(populate_by_name=True)


    id: str

    slug: str

    package_id: str = Field(alias="packageId")

    name: str

    publisher: str | None = None

    description: str | None = None

    long_description: str | None = Field(default=None, alias="longDescription")

    tags: list[str] = Field(default_factory=list)

    operating_systems: list[str] = Field(default_factory=list, alias="operatingSystems")

    icon_url: str | None = Field(default=None, alias="iconUrl")

    official_url: str | None = Field(default=None, alias="officialUrl")

    origin_url: str | None = Field(default=None, alias="originUrl")

    latest_version: str | None = Field(default=None, alias="latestVersion")

    installer_filename: str | None = Field(default=None, alias="installerFilename")

    installer_type: str | None = Field(default=None, alias="installerType")

    content_type: str | None = Field(default=None, alias="contentType")

    size_bytes: int | None = Field(default=None, alias="sizeBytes")

    final_domain: str | None = Field(default=None, alias="finalDomain")

    score: int | None = None

    resolution_status: str = Field(alias="resolutionStatus")

    validation_status: str = Field(alias="validationStatus")

    downloadable: bool

    updated_at: datetime = Field(alias="updatedAt")

    source_label: str = Field(alias="sourceLabel")

    checked_at: datetime | None = Field(default=None, alias="checkedAt")

    expires_at: datetime | None = Field(default=None, alias="expiresAt")

    download_options: list[DownloadOption] = Field(default_factory=list, alias="downloadOptions")

    notes: str

