from uuid import uuid4

from app.api.app_mapper import best_resolved_source, to_details
from app.core.time import utc_after, utc_now
from app.db.enums import ResolutionStatus, ValidationStatus
from app.db.models import DownloadSource, ResolvedSource, SoftwareApp, SoftwareAppTag


def test_details_exposes_download_options_and_primary_candidate() -> None:
    now = utc_now()
    app = SoftwareApp(
        id=uuid4(),
        winstall_id="GeoGebra.GraphingCalculator",
        slug="geogebra-graphing-calculator",
        name="GeoGebra Graphing Calculator",
        normalized_name="geogebra graphing calculator",
        description="Dynamic mathematics app.",
        long_description=(
            "GeoGebra Graphing Calculator permite crear graficas y analizar funciones."
        ),
        long_description_status="completed",
        app_status="active",
        created_at=now,
        updated_at=now,
    )
    app.tags = [
        SoftwareAppTag(
            id=uuid4(),
            software_app_id=app.id,
            tag="graphing",
            normalized_tag="graphing",
            source="winstall",
            created_at=now,
        )
    ]
    source = DownloadSource(
        id=uuid4(),
        software_app_id=app.id,
        operating_system="windows",
        architecture="x86_64",
        resolution_status=ResolutionStatus.DIRECT.value,
        validation_status=ValidationStatus.VALID.value,
    )
    primary = ResolvedSource(
        id=uuid4(),
        download_source_id=source.id,
        resolved_url_encrypted="encrypted-primary",
        final_domain="geogebra.org",
        filename="GeoGebraGraphing.exe",
        extension=".exe",
        score=130,
        status=ResolutionStatus.DIRECT.value,
        validation_status=ValidationStatus.VALID.value,
        checked_at=utc_now(),
        expires_at=utc_after(hours=1),
        metadata_json={"is_primary": True},
    )
    primary.catalog_downloadable = True
    alternative = ResolvedSource(
        id=uuid4(),
        download_source_id=source.id,
        resolved_url_encrypted="encrypted-alt",
        final_domain="geogebra.org",
        filename="GeoGebraSuite.exe",
        extension=".exe",
        score=80,
        status=ResolutionStatus.DIRECT.value,
        validation_status=ValidationStatus.VALID.value,
        checked_at=utc_now(),
        expires_at=utc_after(hours=1),
        metadata_json={"is_primary": False},
    )
    alternative.catalog_downloadable = True
    source.resolved_sources = [alternative, primary]
    app.sources = [source]

    details = to_details(app)

    assert best_resolved_source(app) == primary
    assert details.long_description == app.long_description
    assert details.tags == ["graphing"]
    assert [option.filename for option in details.download_options] == [
        "GeoGebraGraphing.exe",
        "GeoGebraSuite.exe",
    ]
    assert details.download_options[0].is_primary is True
    assert details.downloadable is True
    assert details.updated_at == app.updated_at


def test_expired_valid_sources_remain_downloadable_candidates() -> None:
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
        resolution_status=ResolutionStatus.FALLBACK.value,
        validation_status=ValidationStatus.VALID.value,
    )
    resolved = ResolvedSource(
        id=uuid4(),
        download_source_id=source.id,
        resolved_url_encrypted="encrypted",
        final_domain="downloads.example.com",
        filename="VendorApp.exe",
        extension=".exe",
        score=80,
        status=ResolutionStatus.FALLBACK.value,
        validation_status=ValidationStatus.VALID.value,
        checked_at=now,
        expires_at=utc_after(hours=-1),
        metadata_json={"is_primary": True},
    )
    resolved.catalog_downloadable = True
    source.resolved_sources = [resolved]
    app.sources = [source]
    app.tags = []

    details = to_details(app)

    assert best_resolved_source(app) == resolved
    assert details.download_options[0].filename == "VendorApp.exe"
    assert details.resolution_status == ResolutionStatus.FALLBACK.value
    assert details.downloadable is True


def test_mapper_fails_closed_when_projection_marks_candidate_unavailable() -> None:
    now = utc_now()
    app = SoftwareApp(
        id=uuid4(),
        winstall_id="Vendor.Untrusted",
        slug="vendor-untrusted",
        name="Vendor Untrusted",
        normalized_name="vendor untrusted",
        app_status="active",
        created_at=now,
        updated_at=now,
    )
    source = DownloadSource(
        id=uuid4(),
        software_app_id=app.id,
        operating_system="windows",
        architecture="x86_64",
        resolution_status=ResolutionStatus.DIRECT.value,
        validation_status=ValidationStatus.VALID.value,
    )
    projected_unavailable = ResolvedSource(
        id=uuid4(),
        download_source_id=source.id,
        resolved_url_encrypted="encrypted",
        final_domain="edge.example.test",
        filename="unsafe.exe",
        extension=".exe",
        score=100,
        status=ResolutionStatus.DIRECT.value,
        validation_status=ValidationStatus.VALID.value,
        checked_at=now,
        expires_at=utc_after(hours=1),
        metadata_json={
            "validation_confidence": "validated",
        },
    )
    projected_unavailable.catalog_downloadable = False
    source.resolved_sources = [projected_unavailable]
    app.sources = [source]
    app.tags = []

    details = to_details(app)

    assert best_resolved_source(app) is None
    assert details.downloadable is False
    assert details.resolution_status == ResolutionStatus.MISSING.value
    assert details.download_options == []


def test_query_expression_does_not_add_generated_column_to_sqlite_metadata() -> None:
    assert "catalog_downloadable" not in ResolvedSource.__table__.c
