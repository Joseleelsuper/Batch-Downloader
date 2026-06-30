from uuid import uuid4

from app.core.time import utc_after, utc_now
from app.db.enums import ResolutionStatus, ValidationStatus
from app.db.models import DownloadSource, ResolvedSource, SoftwareApp
from app.repositories.catalog import has_current_available_installer


def make_app_with_source(
    resolution_status: ResolutionStatus,
    validation_status: ValidationStatus = ValidationStatus.VALID,
    resolved_status: ResolutionStatus | None = None,
    resolved_validation_status: ValidationStatus = ValidationStatus.VALID,
    expires_in_hours: int = 1,
) -> SoftwareApp:
    now = utc_now()
    app = SoftwareApp(
        id=uuid4(),
        winstall_id="Vendor.App",
        slug="vendor-app",
        name="Vendor App",
        normalized_name="vendor app",
        app_status="active",
        created_at=now,
        updated_at=now,
    )
    source = DownloadSource(
        id=uuid4(),
        software_app_id=app.id,
        operating_system="windows",
        architecture="x86_64",
        resolution_status=resolution_status.value,
        validation_status=validation_status.value,
    )
    source.resolved_sources = []
    if resolved_status is not None:
        source.resolved_sources = [
            ResolvedSource(
                id=uuid4(),
                download_source_id=source.id,
                resolved_url_encrypted="encrypted",
                final_domain="example.com",
                filename="VendorApp.exe",
                extension=".exe",
                score=100,
                status=resolved_status.value,
                validation_status=resolved_validation_status.value,
                checked_at=now,
                expires_at=utc_after(hours=expires_in_hours),
            )
        ]
    app.sources = [source]
    return app


def test_current_available_installer_requires_valid_unexpired_resolved_source() -> None:
    app = make_app_with_source(
        ResolutionStatus.DIRECT,
        resolved_status=ResolutionStatus.DIRECT,
    )

    assert has_current_available_installer(app) is True


def test_current_available_installer_rejects_expired_sources() -> None:
    app = make_app_with_source(
        ResolutionStatus.FALLBACK,
        resolved_status=ResolutionStatus.FALLBACK,
        expires_in_hours=-1,
    )

    assert has_current_available_installer(app) is False


def test_current_available_installer_rejects_review_or_missing_statuses() -> None:
    review_app = make_app_with_source(
        ResolutionStatus.REQUIRES_MANUAL_REVIEW,
        resolved_status=None,
    )
    missing_app = make_app_with_source(
        ResolutionStatus.MISSING,
        resolved_status=None,
    )

    assert has_current_available_installer(review_app) is False
    assert has_current_available_installer(missing_app) is False
