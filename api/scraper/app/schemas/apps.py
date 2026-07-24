from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class ErrorResponse(BaseModel):
    code: str
    status: str
    message: str


class AppListItem(BaseModel):
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
    model_config = ConfigDict(populate_by_name=True)

    data: list[AppListItem]
    page: int
    page_size: int = Field(alias="pageSize")
    total: int


class CatalogFilterStats(BaseModel):
    all: int
    available: int
    review: int
    missing: int


class LastScrapeRun(BaseModel):
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
    model_config = ConfigDict(populate_by_name=True)

    total: int
    filters: CatalogFilterStats
    last_scrape: LastScrapeRun | None = Field(default=None, alias="lastScrape")
    generated_at: datetime = Field(alias="generatedAt")


class DownloadOption(BaseModel):
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
