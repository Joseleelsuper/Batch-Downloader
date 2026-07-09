from uuid import uuid4

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.core.time import utc_after, utc_now
from app.core.url_protector import UrlProtector
from app.db.base import Base
from app.db.enums import ResolutionStatus, ValidationStatus
from app.db.models import DownloadSource, ResolvedSource, SoftwareApp
from app.repositories.catalog import CatalogRepository, has_current_available_installer


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


@pytest.mark.asyncio
async def test_description_enrichment_prioritizes_completed_apps_missing_long_description() -> None:
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    async with session_factory() as session:
        missing = software_app(
            "Vendor.CompletedMissing",
            long_description_status="completed",
            long_description="",
        )
        pending = software_app(
            "Vendor.Pending",
            long_description_status="pending",
            long_description=None,
        )
        completed = software_app(
            "Vendor.Completed",
            long_description_status="completed",
            long_description="Descripcion generada.",
        )
        session.add_all([completed, pending, missing])
        await session.commit()

        apps = await CatalogRepository(
            session,
            UrlProtector("test-secret"),
        ).apps_for_description_enrichment()

    await engine.dispose()
    ordered_ids = [app.winstall_id for app in apps]
    assert "Vendor.CompletedMissing" in ordered_ids
    assert "Vendor.Pending" in ordered_ids
    assert "Vendor.Completed" not in ordered_ids


@pytest.mark.asyncio
async def test_repair_resolved_source_platforms_moves_cross_platform_installers() -> None:
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    now = utc_now()
    async with session_factory() as session:
        app = SoftwareApp(
            id=uuid4(),
            winstall_id="Valve.Steam",
            slug="valve-steam",
            name="Steam",
            normalized_name="steam",
            app_status="active",
            created_at=now,
            updated_at=now,
        )
        source = DownloadSource(
            id=uuid4(),
            software_app_id=app.id,
            operating_system="windows",
            architecture="x86_64",
            initial_url="https://store.steampowered.com/about/",
            resolution_status=ResolutionStatus.DIRECT.value,
            validation_status=ValidationStatus.VALID.value,
        )
        source.resolved_sources = [
            ResolvedSource(
                id=uuid4(),
                download_source_id=source.id,
                resolved_url_encrypted="encrypted-exe",
                final_domain="steamstatic.com",
                filename="SteamSetup.exe",
                extension=".exe",
                score=100,
                status=ResolutionStatus.DIRECT.value,
                validation_status=ValidationStatus.VALID.value,
                checked_at=now,
                expires_at=utc_after(hours=1),
            ),
            ResolvedSource(
                id=uuid4(),
                download_source_id=source.id,
                resolved_url_encrypted="encrypted-deb",
                final_domain="steampowered.com",
                filename="steam_latest.deb",
                extension=".deb",
                score=90,
                status=ResolutionStatus.DIRECT.value,
                validation_status=ValidationStatus.VALID.value,
                checked_at=now,
                expires_at=utc_after(hours=1),
            ),
            ResolvedSource(
                id=uuid4(),
                download_source_id=source.id,
                resolved_url_encrypted="encrypted-dmg",
                final_domain="steamstatic.com",
                filename="steam.dmg",
                extension=".dmg",
                score=80,
                status=ResolutionStatus.DIRECT.value,
                validation_status=ValidationStatus.VALID.value,
                checked_at=now,
                expires_at=utc_after(hours=1),
            ),
        ]
        app.sources = [source]
        session.add(app)
        await session.commit()

        repaired = await CatalogRepository(session, UrlProtector("test-secret")).repair_resolved_source_platforms()
        await session.commit()

        assert repaired == 2
        rows = await session.execute(
            select(DownloadSource.operating_system, ResolvedSource.filename)
            .join(ResolvedSource, ResolvedSource.download_source_id == DownloadSource.id)
            .where(DownloadSource.software_app_id == app.id)
        )
        by_os: dict[str, list[str]] = {}
        for operating_system, filename in rows:
            by_os.setdefault(operating_system, []).append(filename)

    await engine.dispose()
    assert set(by_os) == {"windows", "linux", "macos"}
    assert by_os["windows"] == ["SteamSetup.exe"]
    assert by_os["linux"] == ["steam_latest.deb"]
    assert by_os["macos"] == ["steam.dmg"]


def software_app(
    winstall_id: str,
    *,
    long_description_status: str,
    long_description: str | None,
) -> SoftwareApp:
    now = utc_now()
    return SoftwareApp(
        id=uuid4(),
        winstall_id=winstall_id,
        slug=winstall_id.lower().replace(".", "-"),
        name=winstall_id,
        normalized_name=winstall_id.lower(),
        app_status="active",
        long_description_status=long_description_status,
        long_description=long_description,
        created_at=now,
        updated_at=now,
    )
