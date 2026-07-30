"""Implementa las responsabilidades del módulo `apps`.
"""
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class ErrorResponse(BaseModel):
    """Representa una respuesta de `Error`.
    """
    code: str
    """Campo declarado `code` de `ErrorResponse`.
    """
    status: str
    """Campo declarado `status` de `ErrorResponse`.
    """
    message: str
    """Campo declarado `message` de `ErrorResponse`.
    """


class AppListItem(BaseModel):
    """Representa un elemento de `AppList`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `AppListItem`.
    """

    id: str
    """Campo declarado `id` de `AppListItem`.
    """
    slug: str
    """Campo declarado `slug` de `AppListItem`.
    """
    package_id: str = Field(alias="packageId")
    """Campo declarado `package_id` de `AppListItem`.
    """
    name: str
    """Campo declarado `name` de `AppListItem`.
    """
    publisher: str | None = None
    """Campo declarado `publisher` de `AppListItem`.
    """
    description: str | None = None
    """Campo declarado `description` de `AppListItem`.
    """
    long_description: str | None = Field(default=None, alias="longDescription")
    """Campo declarado `long_description` de `AppListItem`.
    """
    tags: list[str] = Field(default_factory=list)
    """Campo declarado `tags` de `AppListItem`.
    """
    operating_systems: list[str] = Field(default_factory=list, alias="operatingSystems")
    """Campo declarado `operating_systems` de `AppListItem`.
    """
    icon_url: str | None = Field(default=None, alias="iconUrl")
    """Campo declarado `icon_url` de `AppListItem`.
    """
    latest_version: str | None = Field(default=None, alias="latestVersion")
    """Campo declarado `latest_version` de `AppListItem`.
    """
    source_label: str = Field(alias="sourceLabel")
    """Campo declarado `source_label` de `AppListItem`.
    """
    resolution_status: str = Field(alias="resolutionStatus")
    """Campo declarado `resolution_status` de `AppListItem`.
    """
    validation_status: str = Field(alias="validationStatus")
    """Campo declarado `validation_status` de `AppListItem`.
    """
    downloadable: bool
    """Campo declarado `downloadable` de `AppListItem`.
    """
    updated_at: datetime = Field(alias="updatedAt")
    """Campo declarado `updated_at` de `AppListItem`.
    """


class AppSearchResponse(BaseModel):
    """Representa una respuesta de `AppSearch`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `AppSearchResponse`.
    """

    data: list[AppListItem]
    """Campo declarado `data` de `AppSearchResponse`.
    """
    page: int
    """Campo declarado `page` de `AppSearchResponse`.
    """
    page_size: int = Field(alias="pageSize")
    """Campo declarado `page_size` de `AppSearchResponse`.
    """
    total: int
    """Campo declarado `total` de `AppSearchResponse`.
    """


class CatalogFilterStats(BaseModel):
    """Representa los datos validados de `CatalogFilterStats`.
    """
    all: int
    """Campo declarado `all` de `CatalogFilterStats`.
    """
    available: int
    """Campo declarado `available` de `CatalogFilterStats`.
    """
    review: int
    """Campo declarado `review` de `CatalogFilterStats`.
    """
    missing: int
    """Campo declarado `missing` de `CatalogFilterStats`.
    """


class LastScrapeRun(BaseModel):
    """Representa los datos validados de `LastScrapeRun`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `LastScrapeRun`.
    """

    status: str
    """Campo declarado `status` de `LastScrapeRun`.
    """
    started_at: datetime = Field(alias="startedAt")
    """Campo declarado `started_at` de `LastScrapeRun`.
    """
    heartbeat_at: datetime = Field(alias="heartbeatAt")
    """Campo declarado `heartbeat_at` de `LastScrapeRun`.
    """
    finished_at: datetime | None = Field(default=None, alias="finishedAt")
    """Campo declarado `finished_at` de `LastScrapeRun`.
    """
    apps_discovered: int = Field(alias="appsDiscovered")
    """Campo declarado `apps_discovered` de `LastScrapeRun`.
    """
    apps_resolved: int = Field(alias="appsResolved")
    """Campo declarado `apps_resolved` de `LastScrapeRun`.
    """
    apps_failed: int = Field(alias="appsFailed")
    """Campo declarado `apps_failed` de `LastScrapeRun`.
    """
    apps_skipped: int = Field(default=0, alias="appsSkipped")
    """Campo declarado `apps_skipped` de `LastScrapeRun`.
    """


class CatalogStatsResponse(BaseModel):
    """Representa una respuesta de `CatalogStats`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `CatalogStatsResponse`.
    """

    total: int
    """Campo declarado `total` de `CatalogStatsResponse`.
    """
    filters: CatalogFilterStats
    """Campo declarado `filters` de `CatalogStatsResponse`.
    """
    last_scrape: LastScrapeRun | None = Field(default=None, alias="lastScrape")
    """Campo declarado `last_scrape` de `CatalogStatsResponse`.
    """
    generated_at: datetime = Field(alias="generatedAt")
    """Campo declarado `generated_at` de `CatalogStatsResponse`.
    """


class DownloadOption(BaseModel):
    """Representa los datos validados de `DownloadOption`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `DownloadOption`.
    """

    id: str
    """Campo declarado `id` de `DownloadOption`.
    """
    filename: str | None = None
    """Campo declarado `filename` de `DownloadOption`.
    """
    extension: str | None = None
    """Campo declarado `extension` de `DownloadOption`.
    """
    operating_system: str = Field(alias="operatingSystem")
    """Campo declarado `operating_system` de `DownloadOption`.
    """
    architecture: str
    """Campo declarado `architecture` de `DownloadOption`.
    """
    version: str | None = None
    """Campo declarado `version` de `DownloadOption`.
    """
    is_latest: bool = Field(default=False, alias="isLatest")
    """Campo declarado `is_latest` de `DownloadOption`.
    """
    version_status: str | None = Field(default=None, alias="versionStatus")
    """Campo declarado `version_status` de `DownloadOption`.
    """
    source_label: str = Field(alias="sourceLabel")
    """Campo declarado `source_label` de `DownloadOption`.
    """
    score: int
    """Campo declarado `score` de `DownloadOption`.
    """
    final_domain: str | None = Field(default=None, alias="finalDomain")
    """Campo declarado `final_domain` de `DownloadOption`.
    """
    is_primary: bool = Field(alias="isPrimary")
    """Campo declarado `is_primary` de `DownloadOption`.
    """


class AppDetails(BaseModel):
    """Representa los datos validados de `AppDetails`.
    """
    model_config = ConfigDict(populate_by_name=True)
    """Campo declarado `model_config` de `AppDetails`.
    """

    id: str
    """Campo declarado `id` de `AppDetails`.
    """
    slug: str
    """Campo declarado `slug` de `AppDetails`.
    """
    package_id: str = Field(alias="packageId")
    """Campo declarado `package_id` de `AppDetails`.
    """
    name: str
    """Campo declarado `name` de `AppDetails`.
    """
    publisher: str | None = None
    """Campo declarado `publisher` de `AppDetails`.
    """
    description: str | None = None
    """Campo declarado `description` de `AppDetails`.
    """
    long_description: str | None = Field(default=None, alias="longDescription")
    """Campo declarado `long_description` de `AppDetails`.
    """
    tags: list[str] = Field(default_factory=list)
    """Campo declarado `tags` de `AppDetails`.
    """
    operating_systems: list[str] = Field(default_factory=list, alias="operatingSystems")
    """Campo declarado `operating_systems` de `AppDetails`.
    """
    icon_url: str | None = Field(default=None, alias="iconUrl")
    """Campo declarado `icon_url` de `AppDetails`.
    """
    official_url: str | None = Field(default=None, alias="officialUrl")
    """Campo declarado `official_url` de `AppDetails`.
    """
    origin_url: str | None = Field(default=None, alias="originUrl")
    """Campo declarado `origin_url` de `AppDetails`.
    """
    latest_version: str | None = Field(default=None, alias="latestVersion")
    """Campo declarado `latest_version` de `AppDetails`.
    """
    installer_filename: str | None = Field(default=None, alias="installerFilename")
    """Campo declarado `installer_filename` de `AppDetails`.
    """
    installer_type: str | None = Field(default=None, alias="installerType")
    """Campo declarado `installer_type` de `AppDetails`.
    """
    content_type: str | None = Field(default=None, alias="contentType")
    """Campo declarado `content_type` de `AppDetails`.
    """
    size_bytes: int | None = Field(default=None, alias="sizeBytes")
    """Campo declarado `size_bytes` de `AppDetails`.
    """
    final_domain: str | None = Field(default=None, alias="finalDomain")
    """Campo declarado `final_domain` de `AppDetails`.
    """
    score: int | None = None
    """Campo declarado `score` de `AppDetails`.
    """
    resolution_status: str = Field(alias="resolutionStatus")
    """Campo declarado `resolution_status` de `AppDetails`.
    """
    validation_status: str = Field(alias="validationStatus")
    """Campo declarado `validation_status` de `AppDetails`.
    """
    downloadable: bool
    """Campo declarado `downloadable` de `AppDetails`.
    """
    updated_at: datetime = Field(alias="updatedAt")
    """Campo declarado `updated_at` de `AppDetails`.
    """
    source_label: str = Field(alias="sourceLabel")
    """Campo declarado `source_label` de `AppDetails`.
    """
    checked_at: datetime | None = Field(default=None, alias="checkedAt")
    """Campo declarado `checked_at` de `AppDetails`.
    """
    expires_at: datetime | None = Field(default=None, alias="expiresAt")
    """Campo declarado `expires_at` de `AppDetails`.
    """
    download_options: list[DownloadOption] = Field(default_factory=list, alias="downloadOptions")
    """Campo declarado `download_options` de `AppDetails`.
    """
    notes: str
    """Campo declarado `notes` de `AppDetails`.
    """
