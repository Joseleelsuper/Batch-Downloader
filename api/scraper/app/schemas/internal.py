"""Define el intercambio autenticado con Core, el worker y el indexador, además de los resultados
de inspecciones y descubrimientos.
"""
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.domain.source_resolution import SourceTrustStatus


class InternalSourceResolution(BaseModel):
    """Entrega al worker la URL resuelta y las expectativas de una fuente exacta a través de la
    API interna autenticada.

    Attributes:
        source_ref, app_id: Identidades de resolución y aplicación; permiten rechazar una
            sustitución por otra fuente.
        url: URL privada autorizada para la descarga, o None si no está disponible.
        expected_filename, expected_size_bytes, expected_sha256, expected_mime: Nombre, bytes
            históricos, huella fijada y MIME esperados; None expresa dato desconocido.
        operating_system, architecture, trust_status: Contexto del instalador y confianza
            declarada.
        app_name, version, extension: Metadatos de presentación del artefacto.
        installation_profile, signature_base64: Receta Linux y firma adjunta opcionales.

    See Also:
        app.application.source_resolution.resolve_source: Resuelve y revalida la fuente antes
            de construir la respuesta.
    """
    model_config = ConfigDict(populate_by_name=True)


    source_ref: str = Field(alias="sourceRef")

    app_id: str = Field(alias="appId")

    url: str | None

    expected_filename: str | None = Field(alias="expectedFilename")

    expected_size_bytes: int | None = Field(alias="expectedSizeBytes")

    expected_sha256: str | None = Field(alias="expectedSha256")

    expected_mime: str | None = Field(alias="expectedMime")

    operating_system: str = Field(alias="operatingSystem")

    architecture: str = Field(alias="architecture")

    trust_status: SourceTrustStatus = Field(alias="trustStatus")
    app_name: str | None = Field(default=None, alias="appName")
    version: str | None = None
    extension: str | None = None
    installation_profile: dict | None = Field(default=None, alias="installationProfile")
    signature_base64: str | None = Field(default=None, alias="signatureBase64")


class InternalSourceSize(BaseModel):
    """Devuelve el tamaño estimable de una fuente sin exponer su URL privada."""

    model_config = ConfigDict(populate_by_name=True)

    source_ref: str = Field(alias="sourceRef")
    expected_size_bytes: int | None = Field(alias="expectedSizeBytes", gt=0)



class ContentEnqueueResult(BaseModel):
    """Resume una solicitud de enriquecimiento distinguiendo coincidencias, tareas nuevas y
    trabajo ya activo.

    Attributes:
        matched: Aplicaciones que cumplen la selección.
        enqueued: Tareas incorporadas a la cola.
        already_active: Aplicaciones que ya tenían trabajo activo.
    """
    model_config = ConfigDict(populate_by_name=True)


    matched: int

    enqueued: int

    already_active: int = Field(alias="alreadyActive")



class GenerateDescriptionRequest(BaseModel):
    """Solicita una descripción para una aplicación concreta.

    Attributes:
        app_id: UUID textual de la aplicación que se enriquecerá.
    """
    model_config = ConfigDict(populate_by_name=True)


    app_id: str = Field(alias="appId")



class GenerateDescriptionResult(BaseModel):
    """Permite seguir el trabajo persistente de generación de una descripción.

    Attributes:
        job_id: UUID de la tarea incorporada o recuperada.
        status: Estado actual de la tarea.
    """
    model_config = ConfigDict(populate_by_name=True)


    job_id: str = Field(alias="jobId")

    status: str



class SemanticDocument(BaseModel):
    """Transporta contenido indexable y su huella para actualizar embeddings solo cuando cambia
    la representación de una aplicación.

    Attributes:
        app_id: UUID textual de la aplicación.
        content_hash: Huella estable del contenido semántico.
        content: Texto preparado para codificación.
        metadata: Metadatos de catálogo asociados al documento.
    """
    model_config = ConfigDict(populate_by_name=True)


    app_id: str = Field(alias="appId")

    content_hash: str = Field(alias="contentHash")

    content: str

    metadata: dict[str, object]



class SemanticDocumentPage(BaseModel):
    """Entrega un lote de documentos y un cursor por UUID para continuar la lectura incremental.

    Attributes:
        documents: Documentos del lote en el orden de lectura.
        next_after_app_id: UUID tras el que continuar, o None al terminar.
    """
    model_config = ConfigDict(populate_by_name=True)


    documents: list[SemanticDocument]

    next_after_app_id: str | None = Field(alias="nextAfterAppId")



class ManualInstallerUrls(BaseModel):
    """Acepta una URL opcional por plataforma para inspeccionar instaladores de una aplicación
    existente.

    Attributes:
        windows, macos, linux: URL de cada plataforma, limitada a 2048 caracteres; None omite
            esa plataforma.
    """
    windows: str | None = Field(default=None, max_length=2048)

    macos: str | None = Field(default=None, max_length=2048)

    linux: str | None = Field(default=None, max_length=2048)



class ManualInstallerInspectionRequest(BaseModel):
    """Reúne instaladores y página de origen antes de iniciar una inspección sin modificar
    todavía el catálogo.

    Attributes:
        installer_url: URL individual de compatibilidad, opcional y de hasta 2048 caracteres.
        installer_urls: URL opcionales por plataforma.
        source_page_url: Página de origen obligatoria, entre uno y 2048 caracteres.
    """
    model_config = ConfigDict(populate_by_name=True)


    installer_url: str | None = Field(
        alias="installerUrl",
        default=None,
        max_length=2048,
    )

    installer_urls: ManualInstallerUrls = Field(
        alias="installerUrls",
        default_factory=ManualInstallerUrls,
    )

    source_page_url: str = Field(alias="sourcePageUrl", min_length=1, max_length=2048)


    @model_validator(mode="after")
    def require_an_installer_url(self):
        """Exige al menos una URL no vacía entre la entrada individual y las entradas por
        plataforma.

        Returns:
            la solicitud validada sin normalizar sus URL.

        Raises:
            ValueError: at_least_one_installer_url_required si todas las entradas faltan o
                contienen solo espacios.
        """
        if self.installer_url and self.installer_url.strip():
            return self
        if any(
            value and value.strip()
            for value in self.installer_urls.model_dump().values()
        ):
            return self
        raise ValueError("at_least_one_installer_url_required")


class ManualFieldSuggestion(BaseModel):
    """Acompaña una propuesta editorial de su procedencia para que el administrador pueda
    evaluarla.

    Attributes:
        value: Texto sugerido, o None cuando no se obtuvo.
        source: Origen de la sugerencia, como dato actual, metadatos de página, archivo,
            generación o revisión manual.
    """
    value: str | None = None

    source: Literal[
        "current",
        "json_ld",
        "open_graph",
        "twitter",
        "canonical",
        "filename",
        "generated_ai",
        "manual",
        "source_page",
        "unavailable",
    ]



class ManualInstallerSuggestions(BaseModel):
    """Agrupa propuestas de nombre, editor, página, versión, descripción e icono conservando la
    procedencia de cada una.

    See Also:
        ManualFieldSuggestion: Relaciona cada valor propuesto con su evidencia.
    """
    model_config = ConfigDict(populate_by_name=True)


    name: ManualFieldSuggestion

    publisher: ManualFieldSuggestion

    official_url: ManualFieldSuggestion = Field(alias="officialUrl")

    latest_version: ManualFieldSuggestion = Field(alias="latestVersion")

    description: ManualFieldSuggestion

    long_description: ManualFieldSuggestion = Field(alias="longDescription")

    icon_url: ManualFieldSuggestion = Field(alias="iconUrl")



class ManualInstallerTechnicalData(BaseModel):
    """Resume la evidencia técnica de un instalador inspeccionado sin publicar su URL privada.

    Attributes:
        final_domain, filename, extension, content_type: Dominio final, nombre, formato y MIME
            observados.
        size_bytes: Tamaño conocido en bytes, o None si no se pudo determinar.
        version, operating_system, architecture: Versión y plataforma inferidas o conocidas.
        platform_required: True cuando el administrador debe completar la plataforma antes de
            aplicar.
    """
    model_config = ConfigDict(populate_by_name=True)


    final_domain: str | None = Field(alias="finalDomain")

    filename: str | None

    extension: str | None

    content_type: str | None = Field(alias="contentType")

    size_bytes: int | None = Field(alias="sizeBytes")

    version: str | None

    operating_system: str | None = Field(alias="operatingSystem")

    architecture: str

    platform_required: bool = Field(alias="platformRequired")



class ManualInstallerAiState(BaseModel):
    """Explica si el enriquecimiento de sugerencias mediante IA está disponible o ha fallado.

    Attributes:
        status: ready, unavailable o failed según la generación.
        provider, model: Proveedor y modelo utilizados, si se conocen.
    """
    model_config = ConfigDict(populate_by_name=True)


    status: Literal["ready", "unavailable", "failed"]

    provider: str | None = None

    model: str | None = None



class ManualInstallerInspectionView(BaseModel):
    """Expone progreso, evidencias y vigencia de una inspección recuperable de una aplicación
    existente.

    Attributes:
        id, app_id: Identidades de inspección y aplicación.
        status, phase: Estado persistido y fase de progreso.
        expected_app_version: Versión capturada para impedir aplicar sobre cambios
            concurrentes.
        warnings, suggestions, installer, installers, ai: Avisos, propuestas y evidencias
            técnicas; installer conserva la representación individual compatible.
        error_code, source_ref: Fallo clasificado o resolución creada al aplicar, si existen.
        created_at, updated_at, expires_at: Creación, último cambio y fin de vigencia.
    """
    model_config = ConfigDict(populate_by_name=True)


    id: str

    app_id: str = Field(alias="appId")

    status: Literal["queued", "running", "ready", "failed", "applied", "expired"]

    phase: str

    expected_app_version: int = Field(alias="expectedAppVersion")

    warnings: list[str] = Field(default_factory=list)

    suggestions: ManualInstallerSuggestions | None = None

    installer: ManualInstallerTechnicalData | None = None

    installers: list[ManualInstallerTechnicalData] = Field(default_factory=list)

    ai: ManualInstallerAiState | None = None

    error_code: str | None = Field(alias="errorCode", default=None)

    source_ref: str | None = Field(alias="sourceRef", default=None)

    created_at: datetime = Field(alias="createdAt")

    updated_at: datetime = Field(alias="updatedAt")

    expires_at: datetime = Field(alias="expiresAt")



class ManualInstallerApplyRequest(BaseModel):
    """Confirma los valores revisados de una inspección y la versión de aplicación que el
    administrador espera actualizar.

    Attributes:
        expected_app_version: Versión no negativa contra la que se comprueba concurrencia.
        name, publisher, official_url, latest_version: Nombre y metadatos revisados, sujetos a
            los límites declarados en sus campos.
        description, long_description, icon_url: Textos e icono aceptados por el
            administrador.
        operating_system: Plataforma elegida cuando la inspección no pudo deducirla; None
            conserva la determinada.
    """
    model_config = ConfigDict(populate_by_name=True)


    expected_app_version: int = Field(alias="expectedAppVersion", ge=0)

    name: str = Field(min_length=1, max_length=180)

    publisher: str | None = Field(default=None, max_length=180)

    official_url: str | None = Field(alias="officialUrl", default=None, max_length=2048)

    latest_version: str | None = Field(alias="latestVersion", default=None, max_length=100)

    description: str | None = Field(default=None, max_length=4000)

    long_description: str | None = Field(
        alias="longDescription",
        default=None,
        max_length=12000,
    )

    icon_url: str | None = Field(alias="iconUrl", default=None, max_length=2048)

    operating_system: Literal["windows", "macos", "linux"] | None = Field(
        alias="operatingSystem",
        default=None,
    )



class ManualInstallerApplyResult(BaseModel):
    """Confirma la publicación de fuentes inspeccionadas y devuelve las identidades necesarias
    para refrescar el catálogo.

    Attributes:
        app_id, app_version: Aplicación modificada y su nueva versión.
        source_ref, source_refs: Resolución principal y conjunto completo de resoluciones
            creadas.
        catalog_status: Estado available del resultado aplicado.
        warnings: Avisos que no impidieron la publicación.
    """
    model_config = ConfigDict(populate_by_name=True)


    app_id: str = Field(alias="appId")

    source_ref: str = Field(alias="sourceRef")

    source_refs: list[str] = Field(alias="sourceRefs", default_factory=list)

    app_version: int = Field(alias="appVersion")

    catalog_status: Literal["available"] = Field(alias="catalogStatus", default="available")

    warnings: list[str] = Field(default_factory=list)



class WebsiteAppInstallerUrls(BaseModel):
    """Aporta opcionalmente instaladores conocidos por plataforma para complementar el
    descubrimiento de una web oficial.

    Attributes:
        windows, macos, linux: URL de instalador de hasta 2048 caracteres; None deja esa
            plataforma al descubrimiento.
    """
    windows: str | None = Field(default=None, max_length=2048)

    macos: str | None = Field(default=None, max_length=2048)

    linux: str | None = Field(default=None, max_length=2048)



class WebsiteAppDiscoveryRequest(BaseModel):
    """Inicia la inspección de una web oficial para crear posteriormente una aplicación con sus
    instaladores.

    Attributes:
        official_url: Página oficial obligatoria de hasta 2048 caracteres.
        installer_urls: Instaladores opcionales indicados por el administrador.
    """
    model_config = ConfigDict(populate_by_name=True)


    official_url: str = Field(alias="officialUrl", min_length=1, max_length=2048)

    installer_urls: WebsiteAppInstallerUrls = Field(
        alias="installerUrls",
        default_factory=WebsiteAppInstallerUrls,
    )



class WebsiteAppDiscoveryInstallerView(BaseModel):
    """Describe un instalador descubierto que todavía no es una fuente publicada del catálogo.

    Attributes:
        id: UUID del instalador dentro del descubrimiento.
        final_domain, filename, extension, content_type, size_bytes: Metadatos técnicos
            observados, con tamaño en bytes y None para datos ausentes.
        version, operating_system, architecture: Versión y contexto de plataforma del
            candidato.
    """
    model_config = ConfigDict(populate_by_name=True)


    id: str

    final_domain: str | None = Field(alias="finalDomain", default=None)

    filename: str | None = None

    extension: str | None = None

    content_type: str | None = Field(alias="contentType", default=None)

    size_bytes: int | None = Field(alias="sizeBytes", default=None)

    version: str | None = None

    operating_system: str = Field(alias="operatingSystem")

    architecture: str



class WebsiteAppDiscoveryView(BaseModel):
    """Permite recuperar progreso y propuestas de un descubrimiento hasta su aplicación o
    caducidad.

    Attributes:
        id, status, phase: Identidad de operación, estado persistido y fase actual.
        warnings, provided_installer_platforms: Avisos y plataformas cuyas URL fueron
            aportadas manualmente.
        suggestions, installers, ai: Valores editoriales, candidatos técnicos y resultado de
            enriquecimiento.
        error_code, applied_app_id: Fallo clasificado o aplicación creada, cuando corresponde.
        created_at, updated_at, expires_at: Tiempos de creación, último cambio y caducidad.
    """
    model_config = ConfigDict(populate_by_name=True)


    id: str

    status: Literal["queued", "running", "ready", "failed", "applied", "expired"]

    phase: str

    warnings: list[str] = Field(default_factory=list)

    provided_installer_platforms: list[str] = Field(
        alias="providedInstallerPlatforms",
        default_factory=list,
    )

    suggestions: ManualInstallerSuggestions | None = None

    installers: list[WebsiteAppDiscoveryInstallerView] = Field(default_factory=list)

    ai: ManualInstallerAiState | None = None

    error_code: str | None = Field(alias="errorCode", default=None)

    applied_app_id: str | None = Field(alias="appliedAppId", default=None)

    created_at: datetime = Field(alias="createdAt")

    updated_at: datetime = Field(alias="updatedAt")

    expires_at: datetime = Field(alias="expiresAt")



class WebsiteAppDiscoveryApplyRequest(BaseModel):
    """Recibe los valores editoriales revisados antes de publicar una aplicación descubierta.

    Attributes:
        name, publisher, official_url, latest_version: Identidad editorial, editor, página
            oficial y versión; nombre y página son obligatorios.
        description, long_description, icon_url: Textos e icono aceptados por el
            administrador.
    """
    model_config = ConfigDict(populate_by_name=True)


    name: str = Field(min_length=1, max_length=180)

    publisher: str | None = Field(default=None, max_length=180)

    official_url: str = Field(alias="officialUrl", min_length=1, max_length=2048)

    latest_version: str | None = Field(alias="latestVersion", default=None, max_length=100)

    description: str | None = Field(default=None, max_length=4000)

    long_description: str | None = Field(
        alias="longDescription",
        default=None,
        max_length=12000,
    )

    icon_url: str | None = Field(alias="iconUrl", default=None, max_length=2048)



class WebsiteAppDiscoveryApplyResult(BaseModel):
    """Confirma qué aplicación se creó al aplicar un descubrimiento y cuántos instaladores
    quedaron publicados.

    Attributes:
        app_id, app_version: Identidad de aplicación y versión resultante.
        catalog_status: Disponibilidad resultante: available, review o missing.
        installer_count: Número de instaladores publicados.
        warnings: Avisos conservados tras la aplicación.
    """
    model_config = ConfigDict(populate_by_name=True)


    app_id: str = Field(alias="appId")

    app_version: int = Field(alias="appVersion")

    catalog_status: Literal["available", "review", "missing"] = Field(alias="catalogStatus")

    installer_count: int = Field(alias="installerCount")

    warnings: list[str] = Field(default_factory=list)
