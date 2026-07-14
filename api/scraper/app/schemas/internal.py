from pydantic import BaseModel, ConfigDict, Field

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
