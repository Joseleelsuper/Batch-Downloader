from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class ErrorResponse(BaseModel):
    code: str
    status: str
    message: str


class AppListItem(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    package_id: str = Field(alias="packageId")
    name: str
    publisher: str | None = None
    description: str | None = None
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


class AppDetails(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    package_id: str = Field(alias="packageId")
    name: str
    publisher: str | None = None
    description: str | None = None
    icon_url: str | None = Field(default=None, alias="iconUrl")
    official_url: str | None = Field(default=None, alias="officialUrl")
    latest_version: str | None = Field(default=None, alias="latestVersion")
    installer_filename: str | None = Field(default=None, alias="installerFilename")
    installer_type: str | None = Field(default=None, alias="installerType")
    content_type: str | None = Field(default=None, alias="contentType")
    size_bytes: int | None = Field(default=None, alias="sizeBytes")
    final_domain: str | None = Field(default=None, alias="finalDomain")
    score: int | None = None
    resolution_status: str = Field(alias="resolutionStatus")
    validation_status: str = Field(alias="validationStatus")
    source_label: str = Field(alias="sourceLabel")
    checked_at: datetime | None = Field(default=None, alias="checkedAt")
    expires_at: datetime | None = Field(default=None, alias="expiresAt")
    notes: str
