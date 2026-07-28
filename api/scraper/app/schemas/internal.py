from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.domain.source_resolution import SourceTrustStatus


class InternalSourceResolution(BaseModel):
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


class ContentEnqueueResult(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    matched: int
    enqueued: int
    already_active: int = Field(alias="alreadyActive")


class GenerateDescriptionRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    app_id: str = Field(alias="appId")


class GenerateDescriptionResult(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    job_id: str = Field(alias="jobId")
    status: str


class SemanticDocument(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    app_id: str = Field(alias="appId")
    content_hash: str = Field(alias="contentHash")
    content: str
    metadata: dict[str, object]


class SemanticDocumentPage(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    documents: list[SemanticDocument]
    next_after_app_id: str | None = Field(alias="nextAfterAppId")


class ManualInstallerUrls(BaseModel):
    windows: str | None = Field(default=None, max_length=2048)
    macos: str | None = Field(default=None, max_length=2048)
    linux: str | None = Field(default=None, max_length=2048)


class ManualInstallerInspectionRequest(BaseModel):
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
        if self.installer_url and self.installer_url.strip():
            return self
        if any(
            value and value.strip()
            for value in self.installer_urls.model_dump().values()
        ):
            return self
        raise ValueError("at_least_one_installer_url_required")


class ManualFieldSuggestion(BaseModel):
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
    model_config = ConfigDict(populate_by_name=True)

    name: ManualFieldSuggestion
    publisher: ManualFieldSuggestion
    official_url: ManualFieldSuggestion = Field(alias="officialUrl")
    latest_version: ManualFieldSuggestion = Field(alias="latestVersion")
    description: ManualFieldSuggestion
    long_description: ManualFieldSuggestion = Field(alias="longDescription")
    icon_url: ManualFieldSuggestion = Field(alias="iconUrl")


class ManualInstallerTechnicalData(BaseModel):
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
    model_config = ConfigDict(populate_by_name=True)

    status: Literal["ready", "unavailable", "failed"]
    provider: str | None = None
    model: str | None = None


class ManualInstallerInspectionView(BaseModel):
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
    model_config = ConfigDict(populate_by_name=True)

    app_id: str = Field(alias="appId")
    source_ref: str = Field(alias="sourceRef")
    source_refs: list[str] = Field(alias="sourceRefs", default_factory=list)
    app_version: int = Field(alias="appVersion")
    catalog_status: Literal["available"] = Field(alias="catalogStatus", default="available")
    warnings: list[str] = Field(default_factory=list)


class WebsiteAppInstallerUrls(BaseModel):
    windows: str | None = Field(default=None, max_length=2048)
    macos: str | None = Field(default=None, max_length=2048)
    linux: str | None = Field(default=None, max_length=2048)


class WebsiteAppDiscoveryRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    official_url: str = Field(alias="officialUrl", min_length=1, max_length=2048)
    installer_urls: WebsiteAppInstallerUrls = Field(
        alias="installerUrls",
        default_factory=WebsiteAppInstallerUrls,
    )


class WebsiteAppDiscoveryInstallerView(BaseModel):
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
    model_config = ConfigDict(populate_by_name=True)

    app_id: str = Field(alias="appId")
    app_version: int = Field(alias="appVersion")
    catalog_status: Literal["available", "review", "missing"] = Field(alias="catalogStatus")
    installer_count: int = Field(alias="installerCount")
    warnings: list[str] = Field(default_factory=list)
