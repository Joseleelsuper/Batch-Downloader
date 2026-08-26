"""Implementa las responsabilidades del módulo `internal`.
"""
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.domain.source_resolution import SourceTrustStatus


class InternalSourceResolution(BaseModel):
    """Representa los datos validados de `InternalSourceResolution`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `InternalSourceResolution`.
    """

    source_ref: str = Field(alias="sourceRef")
    """Campo declarado `source_ref` de `InternalSourceResolution`.
    """
    app_id: str = Field(alias="appId")
    """Campo declarado `app_id` de `InternalSourceResolution`.
    """
    url: str | None
    """Campo declarado `url` de `InternalSourceResolution`.
    """
    expected_filename: str | None = Field(alias="expectedFilename")
    """Campo declarado `expected_filename` de `InternalSourceResolution`.
    """
    expected_size_bytes: int | None = Field(alias="expectedSizeBytes")
    """Campo declarado `expected_size_bytes` de `InternalSourceResolution`.
    """
    expected_sha256: str | None = Field(alias="expectedSha256")
    """Campo declarado `expected_sha256` de `InternalSourceResolution`.
    """
    expected_mime: str | None = Field(alias="expectedMime")
    """Campo declarado `expected_mime` de `InternalSourceResolution`.
    """
    operating_system: str = Field(alias="operatingSystem")
    """Campo declarado `operating_system` de `InternalSourceResolution`.
    """
    architecture: str = Field(alias="architecture")
    """Campo declarado `architecture` de `InternalSourceResolution`.
    """
    trust_status: SourceTrustStatus = Field(alias="trustStatus")
    """Campo declarado `trust_status` de `InternalSourceResolution`.
    """


class ContentEnqueueResult(BaseModel):
    """Representa el resultado de `ContentEnqueue`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `ContentEnqueueResult`.
    """

    matched: int
    """Campo declarado `matched` de `ContentEnqueueResult`.
    """
    enqueued: int
    """Campo declarado `enqueued` de `ContentEnqueueResult`.
    """
    already_active: int = Field(alias="alreadyActive")
    """Campo declarado `already_active` de `ContentEnqueueResult`.
    """


class GenerateDescriptionRequest(BaseModel):
    """Representa una solicitud de `GenerateDescription`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `GenerateDescriptionRequest`.
    """

    app_id: str = Field(alias="appId")
    """Campo declarado `app_id` de `GenerateDescriptionRequest`.
    """


class GenerateDescriptionResult(BaseModel):
    """Representa el resultado de `GenerateDescription`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `GenerateDescriptionResult`.
    """

    job_id: str = Field(alias="jobId")
    """Campo declarado `job_id` de `GenerateDescriptionResult`.
    """
    status: str
    """Campo declarado `status` de `GenerateDescriptionResult`.
    """


class SemanticDocument(BaseModel):
    """Representa los datos validados de `SemanticDocument`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `SemanticDocument`.
    """

    app_id: str = Field(alias="appId")
    """Campo declarado `app_id` de `SemanticDocument`.
    """
    content_hash: str = Field(alias="contentHash")
    """Campo declarado `content_hash` de `SemanticDocument`.
    """
    content: str
    """Campo declarado `content` de `SemanticDocument`.
    """
    metadata: dict[str, object]
    """Campo declarado `metadata` de `SemanticDocument`.
    """


class SemanticDocumentPage(BaseModel):
    """Representa los datos validados de `SemanticDocumentPage`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `SemanticDocumentPage`.
    """

    documents: list[SemanticDocument]
    """Campo declarado `documents` de `SemanticDocumentPage`.
    """
    next_after_app_id: str | None = Field(alias="nextAfterAppId")
    """Campo declarado `next_after_app_id` de `SemanticDocumentPage`.
    """


class ManualInstallerUrls(BaseModel):
    """Representa los datos validados de `ManualInstallerUrls`.
    """
    windows: str | None = Field(default=None, max_length=2048)
    """Campo declarado `windows` de `ManualInstallerUrls`.
    """
    macos: str | None = Field(default=None, max_length=2048)
    """Campo declarado `macos` de `ManualInstallerUrls`.
    """
    linux: str | None = Field(default=None, max_length=2048)
    """Campo declarado `linux` de `ManualInstallerUrls`.
    """


class ManualInstallerInspectionRequest(BaseModel):
    """Representa una solicitud de `ManualInstallerInspection`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `ManualInstallerInspectionRequest`.
    """

    installer_url: str | None = Field(
        alias="installerUrl",
        default=None,
        max_length=2048,
    )
    """Campo declarado `installer_url` de `ManualInstallerInspectionRequest`.
    """
    installer_urls: ManualInstallerUrls = Field(
        alias="installerUrls",
        default_factory=ManualInstallerUrls,
    )
    """Campo declarado `installer_urls` de `ManualInstallerInspectionRequest`.
    """
    source_page_url: str = Field(alias="sourcePageUrl", min_length=1, max_length=2048)
    """Campo declarado `source_page_url` de `ManualInstallerInspectionRequest`.
    """

    @model_validator(mode="after")
    def require_an_installer_url(self):
        """Ejecuta `require_an_installer_url` dentro de `ManualInstallerInspectionRequest`.

        Throws:
            ValueError: Si los datos recibidos no cumplen las restricciones requeridas.
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
    """Representa los datos validados de `ManualFieldSuggestion`.
    """
    value: str | None = None
    """Campo declarado `value` de `ManualFieldSuggestion`.
    """
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
    """Campo declarado `source` de `ManualFieldSuggestion`.
    """


class ManualInstallerSuggestions(BaseModel):
    """Representa los datos validados de `ManualInstallerSuggestions`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `ManualInstallerSuggestions`.
    """

    name: ManualFieldSuggestion
    """Campo declarado `name` de `ManualInstallerSuggestions`.
    """
    publisher: ManualFieldSuggestion
    """Campo declarado `publisher` de `ManualInstallerSuggestions`.
    """
    official_url: ManualFieldSuggestion = Field(alias="officialUrl")
    """Campo declarado `official_url` de `ManualInstallerSuggestions`.
    """
    latest_version: ManualFieldSuggestion = Field(alias="latestVersion")
    """Campo declarado `latest_version` de `ManualInstallerSuggestions`.
    """
    description: ManualFieldSuggestion
    """Campo declarado `description` de `ManualInstallerSuggestions`.
    """
    long_description: ManualFieldSuggestion = Field(alias="longDescription")
    """Campo declarado `long_description` de `ManualInstallerSuggestions`.
    """
    icon_url: ManualFieldSuggestion = Field(alias="iconUrl")
    """Campo declarado `icon_url` de `ManualInstallerSuggestions`.
    """


class ManualInstallerTechnicalData(BaseModel):
    """Representa los datos validados de `ManualInstallerTechnicalData`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `ManualInstallerTechnicalData`.
    """

    final_domain: str | None = Field(alias="finalDomain")
    """Campo declarado `final_domain` de `ManualInstallerTechnicalData`.
    """
    filename: str | None
    """Campo declarado `filename` de `ManualInstallerTechnicalData`.
    """
    extension: str | None
    """Campo declarado `extension` de `ManualInstallerTechnicalData`.
    """
    content_type: str | None = Field(alias="contentType")
    """Campo declarado `content_type` de `ManualInstallerTechnicalData`.
    """
    size_bytes: int | None = Field(alias="sizeBytes")
    """Campo declarado `size_bytes` de `ManualInstallerTechnicalData`.
    """
    version: str | None
    """Campo declarado `version` de `ManualInstallerTechnicalData`.
    """
    operating_system: str | None = Field(alias="operatingSystem")
    """Campo declarado `operating_system` de `ManualInstallerTechnicalData`.
    """
    architecture: str
    """Campo declarado `architecture` de `ManualInstallerTechnicalData`.
    """
    platform_required: bool = Field(alias="platformRequired")
    """Campo declarado `platform_required` de `ManualInstallerTechnicalData`.
    """


class ManualInstallerAiState(BaseModel):
    """Representa los datos validados de `ManualInstallerAiState`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `ManualInstallerAiState`.
    """

    status: Literal["ready", "unavailable", "failed"]
    """Campo declarado `status` de `ManualInstallerAiState`.
    """
    provider: str | None = None
    """Campo declarado `provider` de `ManualInstallerAiState`.
    """
    model: str | None = None
    """Campo declarado `model` de `ManualInstallerAiState`.
    """


class ManualInstallerInspectionView(BaseModel):
    """Representa los datos validados de `ManualInstallerInspectionView`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `ManualInstallerInspectionView`.
    """

    id: str
    """Campo declarado `id` de `ManualInstallerInspectionView`.
    """
    app_id: str = Field(alias="appId")
    """Campo declarado `app_id` de `ManualInstallerInspectionView`.
    """
    status: Literal["queued", "running", "ready", "failed", "applied", "expired"]
    """Campo declarado `status` de `ManualInstallerInspectionView`.
    """
    phase: str
    """Campo declarado `phase` de `ManualInstallerInspectionView`.
    """
    expected_app_version: int = Field(alias="expectedAppVersion")
    """Campo declarado `expected_app_version` de `ManualInstallerInspectionView`.
    """
    warnings: list[str] = Field(default_factory=list)
    """Campo declarado `warnings` de `ManualInstallerInspectionView`.
    """
    suggestions: ManualInstallerSuggestions | None = None
    """Campo declarado `suggestions` de `ManualInstallerInspectionView`.
    """
    installer: ManualInstallerTechnicalData | None = None
    """Campo declarado `installer` de `ManualInstallerInspectionView`.
    """
    installers: list[ManualInstallerTechnicalData] = Field(default_factory=list)
    """Campo declarado `installers` de `ManualInstallerInspectionView`.
    """
    ai: ManualInstallerAiState | None = None
    """Campo declarado `ai` de `ManualInstallerInspectionView`.
    """
    error_code: str | None = Field(alias="errorCode", default=None)
    """Campo declarado `error_code` de `ManualInstallerInspectionView`.
    """
    source_ref: str | None = Field(alias="sourceRef", default=None)
    """Campo declarado `source_ref` de `ManualInstallerInspectionView`.
    """
    created_at: datetime = Field(alias="createdAt")
    """Campo declarado `created_at` de `ManualInstallerInspectionView`.
    """
    updated_at: datetime = Field(alias="updatedAt")
    """Campo declarado `updated_at` de `ManualInstallerInspectionView`.
    """
    expires_at: datetime = Field(alias="expiresAt")
    """Campo declarado `expires_at` de `ManualInstallerInspectionView`.
    """


class ManualInstallerApplyRequest(BaseModel):
    """Representa una solicitud de `ManualInstallerApply`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `ManualInstallerApplyRequest`.
    """

    expected_app_version: int = Field(alias="expectedAppVersion", ge=0)
    """Campo declarado `expected_app_version` de `ManualInstallerApplyRequest`.
    """
    name: str = Field(min_length=1, max_length=180)
    """Campo declarado `name` de `ManualInstallerApplyRequest`.
    """
    publisher: str | None = Field(default=None, max_length=180)
    """Campo declarado `publisher` de `ManualInstallerApplyRequest`.
    """
    official_url: str | None = Field(alias="officialUrl", default=None, max_length=2048)
    """Campo declarado `official_url` de `ManualInstallerApplyRequest`.
    """
    latest_version: str | None = Field(alias="latestVersion", default=None, max_length=100)
    """Campo declarado `latest_version` de `ManualInstallerApplyRequest`.
    """
    description: str | None = Field(default=None, max_length=4000)
    """Campo declarado `description` de `ManualInstallerApplyRequest`.
    """
    long_description: str | None = Field(
        alias="longDescription",
        default=None,
        max_length=12000,
    )
    """Campo declarado `long_description` de `ManualInstallerApplyRequest`.
    """
    icon_url: str | None = Field(alias="iconUrl", default=None, max_length=2048)
    """Campo declarado `icon_url` de `ManualInstallerApplyRequest`.
    """
    operating_system: Literal["windows", "macos", "linux"] | None = Field(
        alias="operatingSystem",
        default=None,
    )
    """Campo declarado `operating_system` de `ManualInstallerApplyRequest`.
    """


class ManualInstallerApplyResult(BaseModel):
    """Representa el resultado de `ManualInstallerApply`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `ManualInstallerApplyResult`.
    """

    app_id: str = Field(alias="appId")
    """Campo declarado `app_id` de `ManualInstallerApplyResult`.
    """
    source_ref: str = Field(alias="sourceRef")
    """Campo declarado `source_ref` de `ManualInstallerApplyResult`.
    """
    source_refs: list[str] = Field(alias="sourceRefs", default_factory=list)
    """Campo declarado `source_refs` de `ManualInstallerApplyResult`.
    """
    app_version: int = Field(alias="appVersion")
    """Campo declarado `app_version` de `ManualInstallerApplyResult`.
    """
    catalog_status: Literal["available"] = Field(alias="catalogStatus", default="available")
    """Campo declarado `catalog_status` de `ManualInstallerApplyResult`.
    """
    warnings: list[str] = Field(default_factory=list)
    """Campo declarado `warnings` de `ManualInstallerApplyResult`.
    """


class WebsiteAppInstallerUrls(BaseModel):
    """Representa los datos validados de `WebsiteAppInstallerUrls`.
    """
    windows: str | None = Field(default=None, max_length=2048)
    """Campo declarado `windows` de `WebsiteAppInstallerUrls`.
    """
    macos: str | None = Field(default=None, max_length=2048)
    """Campo declarado `macos` de `WebsiteAppInstallerUrls`.
    """
    linux: str | None = Field(default=None, max_length=2048)
    """Campo declarado `linux` de `WebsiteAppInstallerUrls`.
    """


class WebsiteAppDiscoveryRequest(BaseModel):
    """Representa una solicitud de `WebsiteAppDiscovery`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `WebsiteAppDiscoveryRequest`.
    """

    official_url: str = Field(alias="officialUrl", min_length=1, max_length=2048)
    """Campo declarado `official_url` de `WebsiteAppDiscoveryRequest`.
    """
    installer_urls: WebsiteAppInstallerUrls = Field(
        alias="installerUrls",
        default_factory=WebsiteAppInstallerUrls,
    )
    """Campo declarado `installer_urls` de `WebsiteAppDiscoveryRequest`.
    """


class WebsiteAppDiscoveryInstallerView(BaseModel):
    """Representa los datos validados de `WebsiteAppDiscoveryInstallerView`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `WebsiteAppDiscoveryInstallerView`.
    """

    id: str
    """Campo declarado `id` de `WebsiteAppDiscoveryInstallerView`.
    """
    final_domain: str | None = Field(alias="finalDomain", default=None)
    """Campo declarado `final_domain` de `WebsiteAppDiscoveryInstallerView`.
    """
    filename: str | None = None
    """Campo declarado `filename` de `WebsiteAppDiscoveryInstallerView`.
    """
    extension: str | None = None
    """Campo declarado `extension` de `WebsiteAppDiscoveryInstallerView`.
    """
    content_type: str | None = Field(alias="contentType", default=None)
    """Campo declarado `content_type` de `WebsiteAppDiscoveryInstallerView`.
    """
    size_bytes: int | None = Field(alias="sizeBytes", default=None)
    """Campo declarado `size_bytes` de `WebsiteAppDiscoveryInstallerView`.
    """
    version: str | None = None
    """Campo declarado `version` de `WebsiteAppDiscoveryInstallerView`.
    """
    operating_system: str = Field(alias="operatingSystem")
    """Campo declarado `operating_system` de `WebsiteAppDiscoveryInstallerView`.
    """
    architecture: str
    """Campo declarado `architecture` de `WebsiteAppDiscoveryInstallerView`.
    """


class WebsiteAppDiscoveryView(BaseModel):
    """Representa los datos validados de `WebsiteAppDiscoveryView`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `WebsiteAppDiscoveryView`.
    """

    id: str
    """Campo declarado `id` de `WebsiteAppDiscoveryView`.
    """
    status: Literal["queued", "running", "ready", "failed", "applied", "expired"]
    """Campo declarado `status` de `WebsiteAppDiscoveryView`.
    """
    phase: str
    """Campo declarado `phase` de `WebsiteAppDiscoveryView`.
    """
    warnings: list[str] = Field(default_factory=list)
    """Campo declarado `warnings` de `WebsiteAppDiscoveryView`.
    """
    provided_installer_platforms: list[str] = Field(
        alias="providedInstallerPlatforms",
        default_factory=list,
    )
    """Campo declarado `provided_installer_platforms` de `WebsiteAppDiscoveryView`.
    """
    suggestions: ManualInstallerSuggestions | None = None
    """Campo declarado `suggestions` de `WebsiteAppDiscoveryView`.
    """
    installers: list[WebsiteAppDiscoveryInstallerView] = Field(default_factory=list)
    """Campo declarado `installers` de `WebsiteAppDiscoveryView`.
    """
    ai: ManualInstallerAiState | None = None
    """Campo declarado `ai` de `WebsiteAppDiscoveryView`.
    """
    error_code: str | None = Field(alias="errorCode", default=None)
    """Campo declarado `error_code` de `WebsiteAppDiscoveryView`.
    """
    applied_app_id: str | None = Field(alias="appliedAppId", default=None)
    """Campo declarado `applied_app_id` de `WebsiteAppDiscoveryView`.
    """
    created_at: datetime = Field(alias="createdAt")
    """Campo declarado `created_at` de `WebsiteAppDiscoveryView`.
    """
    updated_at: datetime = Field(alias="updatedAt")
    """Campo declarado `updated_at` de `WebsiteAppDiscoveryView`.
    """
    expires_at: datetime = Field(alias="expiresAt")
    """Campo declarado `expires_at` de `WebsiteAppDiscoveryView`.
    """


class WebsiteAppDiscoveryApplyRequest(BaseModel):
    """Representa una solicitud de `WebsiteAppDiscoveryApply`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `WebsiteAppDiscoveryApplyRequest`.
    """

    name: str = Field(min_length=1, max_length=180)
    """Campo declarado `name` de `WebsiteAppDiscoveryApplyRequest`.
    """
    publisher: str | None = Field(default=None, max_length=180)
    """Campo declarado `publisher` de `WebsiteAppDiscoveryApplyRequest`.
    """
    official_url: str = Field(alias="officialUrl", min_length=1, max_length=2048)
    """Campo declarado `official_url` de `WebsiteAppDiscoveryApplyRequest`.
    """
    latest_version: str | None = Field(alias="latestVersion", default=None, max_length=100)
    """Campo declarado `latest_version` de `WebsiteAppDiscoveryApplyRequest`.
    """
    description: str | None = Field(default=None, max_length=4000)
    """Campo declarado `description` de `WebsiteAppDiscoveryApplyRequest`.
    """
    long_description: str | None = Field(
        alias="longDescription",
        default=None,
        max_length=12000,
    )
    """Campo declarado `long_description` de `WebsiteAppDiscoveryApplyRequest`.
    """
    icon_url: str | None = Field(alias="iconUrl", default=None, max_length=2048)
    """Campo declarado `icon_url` de `WebsiteAppDiscoveryApplyRequest`.
    """


class WebsiteAppDiscoveryApplyResult(BaseModel):
    """Representa el resultado de `WebsiteAppDiscoveryApply`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `WebsiteAppDiscoveryApplyResult`.
    """

    app_id: str = Field(alias="appId")
    """Campo declarado `app_id` de `WebsiteAppDiscoveryApplyResult`.
    """
    app_version: int = Field(alias="appVersion")
    """Campo declarado `app_version` de `WebsiteAppDiscoveryApplyResult`.
    """
    catalog_status: Literal["available", "review", "missing"] = Field(alias="catalogStatus")
    """Campo declarado `catalog_status` de `WebsiteAppDiscoveryApplyResult`.
    """
    installer_count: int = Field(alias="installerCount")
    """Campo declarado `installer_count` de `WebsiteAppDiscoveryApplyResult`.
    """
    warnings: list[str] = Field(default_factory=list)
    """Campo declarado `warnings` de `WebsiteAppDiscoveryApplyResult`.
    """
