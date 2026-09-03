"""Contiene las pruebas de `test_catalog_repository`.
"""
from uuid import uuid4

import pytest
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.api.app_mapper import to_details
from app.core.time import utc_after, utc_now
from app.core.url_protector import UrlProtector
from app.db.base import Base
from app.db.enums import ResolutionStatus, ValidationStatus
from app.db.models import (
    DownloadSource,
    InstallerAbsenceVerification,
    ResolvedSource,
    SoftwareApp,
)
from app.repositories.catalog import (
    CatalogRepository,
    ResolvedSourceCreate,
    has_current_available_installer,
    inferred_platform_for_resolved_source,
    text_fingerprint,
)
from app.scraper.winstall import parse_winstall_app


def make_app_with_source(
    resolution_status: ResolutionStatus,
    validation_status: ValidationStatus = ValidationStatus.VALID,
    resolved_status: ResolutionStatus | None = None,
    resolved_validation_status: ValidationStatus = ValidationStatus.VALID,
    expires_in_hours: int = 1,
) -> SoftwareApp:
    """Construye la operación `app_with_source`.

    Args:
        resolution_status (ResolutionStatus): Valor de `resolution_status` utilizado por la
            operación.
        validation_status (ValidationStatus): Valor de `validation_status` utilizado por la
            operación.
        resolved_status (ResolutionStatus | None): Valor de `resolved_status` utilizado por la
            operación.
        resolved_validation_status (ValidationStatus): Valor de `resolved_validation_status`
            utilizado por la operación.
        expires_in_hours (int): Valor de `expires_in_hours` utilizado por la operación.

    Returns:
        SoftwareApp: Resultado producido por la operación.
    """
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


def test_available_installer_requires_structurally_valid_resolved_source() -> None:
    """Comprueba el escenario `available_installer_requires_structurally_valid_resolved_source`.
    """
    app = make_app_with_source(
        ResolutionStatus.DIRECT,
        resolved_status=ResolutionStatus.DIRECT,
    )

    assert has_current_available_installer(app) is True


def test_available_installer_keeps_stale_valid_source_selectable() -> None:
    """Comprueba el escenario `available_installer_keeps_stale_valid_source_selectable`.
    """
    app = make_app_with_source(
        ResolutionStatus.FALLBACK,
        resolved_status=ResolutionStatus.FALLBACK,
        expires_in_hours=-1,
    )

    assert has_current_available_installer(app) is True


def test_current_available_installer_rejects_review_or_missing_statuses() -> None:
    """Comprueba el escenario `current_available_installer_rejects_review_or_missing_statuses`.
    """
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
async def test_public_load_reads_database_catalog_downloadable_projection() -> None:
    """Comprueba el escenario `public_load_reads_database_catalog_downloadable_projection`.
    """
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
        # SQLite no recibe deliberadamente esta columna desde los metadatos de
        # SQLAlchemy; emula Alembic 0010 para ejercitar el cargador público.
        await connection.execute(
            text(
                "ALTER TABLE resolved_sources "
                "ADD COLUMN catalog_downloadable BOOLEAN NOT NULL DEFAULT 0"
            )
        )
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    app = make_app_with_source(
        ResolutionStatus.DIRECT,
        resolved_status=ResolutionStatus.DIRECT,
    )
    async with session_factory() as session:
        session.add(app)
        await session.commit()
        await session.execute(
            text("UPDATE resolved_sources SET catalog_downloadable = 1")
        )
        await session.commit()

    async with session_factory() as session:
        loaded = await CatalogRepository(
            session,
            UrlProtector("test-secret"),
        ).get_app_by_public_id(str(app.id))

        assert loaded is not None
        assert loaded.sources[0].resolved_sources[0].catalog_downloadable is True
        assert to_details(loaded).downloadable is True
    await engine.dispose()


def test_platform_repair_keeps_macos_tar_gz_out_of_linux() -> None:
    """Comprueba el escenario `platform_repair_keeps_macos_tar_gz_out_of_linux`.
    """
    now = utc_now()
    resolved = ResolvedSource(
        id=uuid4(),
        download_source_id=uuid4(),
        resolved_url_encrypted="encrypted",
        final_domain="github.com",
        filename="uad_gui-macos.tar.gz",
        extension=".tar.gz",
        score=100,
        status=ResolutionStatus.DIRECT.value,
        validation_status=ValidationStatus.VALID.value,
        checked_at=now,
        expires_at=utc_after(hours=1),
        metadata_json={"candidate_label": "uad_gui-macos.tar.gz"},
    )

    assert inferred_platform_for_resolved_source(resolved) == "macos"


@pytest.mark.asyncio
async def test_should_scrape_retries_review_apps_but_skips_resolved_apps() -> None:
    """Comprueba el escenario `should_scrape_retries_review_apps_but_skips_resolved_apps`.
    """
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    async with session_factory() as session:
        app = make_app_with_source(ResolutionStatus.DIRECT)
        session.add(app)
        await session.commit()
        repository = CatalogRepository(session, UrlProtector("test-secret"))

        assert await repository.should_scrape_winstall_package("Vendor.App") is False

        app.sources[0].resolution_status = ResolutionStatus.REQUIRES_MANUAL_REVIEW.value
        app.sources[0].validation_status = ValidationStatus.UNCHECKED.value
        await session.commit()

        assert await repository.should_scrape_winstall_package("Vendor.App") is True

    await engine.dispose()


@pytest.mark.asyncio
async def test_new_winstall_source_starts_in_review_not_missing() -> None:
    """Crear el registro no constituye evidencia de ausencia de instalador."""
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    async with session_factory() as session:
        repository = CatalogRepository(session, UrlProtector("test-secret"))
        app = await repository.upsert_winstall_app(
            parse_winstall_app(
                {
                    "_id": "Vendor.New",
                    "name": "New App",
                    "versions": [],
                }
            )
        )
        source = await repository.default_source_for_app(app.id)

        assert source is not None
        assert source.resolution_status == ResolutionStatus.REQUIRES_MANUAL_REVIEW.value
        assert source.validation_status == ValidationStatus.UNCHECKED.value
    await engine.dispose()


@pytest.mark.asyncio
async def test_winstall_refresh_updates_provider_fields_but_preserves_manual_values() -> None:
    """Un full refresca versiones/tags sin pisar un nombre marcado como manual."""
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    protector = UrlProtector("test-secret")
    initial = parse_winstall_app({
        "_id": "Vendor.Refresh",
        "name": "Provider Name",
        "latestVersion": "1.0.0",
        "tags": ["old"],
        "versions": [{"version": "1.0.0", "installers": []}],
    })
    refreshed = parse_winstall_app({
        "_id": "Vendor.Refresh",
        "name": "Provider Renamed",
        "latestVersion": "2.0.0",
        "tags": ["new"],
        "updatedAt": "2026-08-19T08:00:00Z",
        "versions": [{"version": "2.0.0", "installers": []}],
    })
    async with session_factory() as session:
        repository = CatalogRepository(session, protector)
        app = await repository.upsert_winstall_app(initial)
        app.name = "Reviewed Name"
        app.normalized_name = "reviewed name"
        app.metadata_json = {
            **(app.metadata_json or {}),
            "manual_installer": {"field_sources": {"name": "manual"}},
        }
        source = await repository.default_source_for_app(app.id)
        assert source is not None
        source.resolver_type = "manual_http"
        source.resolver_config = {"source": "admin_manual"}
        source.initial_url = "https://manual.example.test/download"
        await session.commit()

        updated, created = await repository.upsert_winstall_app_with_created(refreshed)
        await session.commit()

        assert created is False
        assert updated.name == "Reviewed Name"
        assert updated.latest_version == "2.0.0"
        assert updated.winstall_latest_version == "2.0.0"
        assert updated.winstall_summary_fingerprint
        assert updated.winstall_detail_fingerprint
        assert [tag.tag for tag in updated.tags] == ["new"]
        assert source.resolver_type == "manual_http"
        assert source.initial_url == "https://manual.example.test/download"
        sources = list(await session.scalars(
            select(DownloadSource).where(DownloadSource.software_app_id == app.id)
        ))
        provider_sources = [item for item in sources if item.resolver_type != "manual_http"]
        assert len(provider_sources) == 1
        assert provider_sources[0].resolver_config == {"winstall_id": "Vendor.Refresh"}
    await engine.dispose()


@pytest.mark.asyncio
async def test_resolved_artifact_fingerprint_deduplicates_revalidation() -> None:
    """Revalidar el mismo binario renueva su fila sin perder el historial de otros."""
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    async with session_factory() as session:
        repository = CatalogRepository(session, UrlProtector("test-secret"))
        app = await repository.upsert_winstall_app(parse_winstall_app({
            "_id": "Vendor.Dedupe",
            "name": "Dedupe",
            "versions": [],
        }))
        source = await repository.default_source_for_app(app.id)
        assert source is not None
        create = ResolvedSourceCreate(
            source_id=source.id,
            url="https://cdn.example.test/releases/App-1.0.exe?token=first",
            final_domain="example.test",
            filename="App-1.0.exe",
            extension=".exe",
            content_type="application/octet-stream",
            size_bytes=4096,
            version="1.0.0",
            score=100,
            status=ResolutionStatus.DIRECT,
            validation_status=ValidationStatus.VALID,
            metadata={"operating_system": "windows", "architecture": "x86_64"},
        )
        first = await repository.save_resolved_source(create)
        await session.flush()
        second = await repository.save_resolved_source(create)
        await session.flush()
        rows = list(await session.scalars(select(ResolvedSource)))

        assert first.id == second.id
        assert len(rows) == 1
        assert rows[0].artifact_fingerprint
    await engine.dispose()


@pytest.mark.asyncio
async def test_available_app_promotes_new_version_only_after_validation() -> None:
    """Un fallo transitorio no adelanta la versión pública respecto al binario vigente."""
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    async with session_factory() as session:
        repository = CatalogRepository(session, UrlProtector("test-secret"))
        app = await repository.upsert_winstall_app(parse_winstall_app({
            "_id": "Vendor.Promote",
            "name": "Promote",
            "latestVersion": "1.0.0",
            "versions": [],
        }))
        app.catalog_available_source_count = 1
        await session.commit()
        await session.refresh(app)

        await repository.upsert_winstall_app(parse_winstall_app({
            "_id": "Vendor.Promote",
            "name": "Promote",
            "latestVersion": "2.0.0",
            "versions": [],
        }))
        assert app.latest_version == "1.0.0"
        assert app.winstall_latest_version == "2.0.0"

        assert await repository.promote_winstall_latest_version(app.id) is True
        assert app.latest_version == "2.0.0"
    await engine.dispose()


@pytest.mark.asyncio
async def test_absence_evidence_persists_until_provider_fingerprints_change() -> None:
    """Una relectura idéntica conserva el acta; un instalador nuevo la invalida."""
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    async with session_factory() as session:
        repository = CatalogRepository(session, UrlProtector("test-secret"))
        payload = {
            "_id": "Vendor.Absence",
            "name": "Absence",
            "homepage": "https://vendor.example.test/downloads",
            "latestVersion": "1.0.0",
            "versions": [
                {
                    "version": "1.0.0",
                    "installers": ["https://vendor.example.test/unsupported.appx"],
                }
            ],
        }
        app = await repository.upsert_winstall_app(parse_winstall_app(payload))
        verification = InstallerAbsenceVerification(
            software_app_id=app.id,
            status="active",
            reason_code="no_supported_binary",
            checked_urls_json=["https://winstall.app/apps/Vendor.Absence"],
            verified_by="tester",
            app_version=app.version,
            winstall_latest_version=app.winstall_latest_version,
            winstall_summary_fingerprint=app.winstall_summary_fingerprint,
            winstall_detail_fingerprint=app.winstall_detail_fingerprint,
            official_url_fingerprint=text_fingerprint(app.official_url),
        )
        session.add(verification)
        await session.commit()

        await repository.upsert_winstall_app(parse_winstall_app(payload))
        await session.flush()
        assert verification.status == "active"

        changed = {
            **payload,
            "versions": [
                {
                    "version": "1.0.0",
                    "installers": ["https://vendor.example.test/new-installer.exe"],
                }
            ],
        }
        await repository.upsert_winstall_app(parse_winstall_app(changed))
        await session.flush()

        source = await repository.default_source_for_app(app.id)
        assert verification.status == "invalidated"
        assert verification.invalidation_reason == "winstall_changed_or_candidate_appeared"
        assert source is not None
        assert source.resolution_status == ResolutionStatus.REQUIRES_MANUAL_REVIEW.value
    await engine.dispose()


@pytest.mark.asyncio
async def test_description_enrichment_prioritizes_completed_apps_missing_long_description() -> None:
    """Comprueba que el enriquecimiento prioriza aplicaciones sin descripción larga."""
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
async def test_public_details_never_return_disabled_apps() -> None:
    """Comprueba el escenario `public_details_never_return_disabled_apps`.
    """
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    async with session_factory() as session:
        disabled = software_app(
            "Vendor.Disabled",
            long_description_status="completed",
            long_description="No visible",
        )
        disabled.app_status = "disabled"
        session.add(disabled)
        await session.commit()

        result = await CatalogRepository(
            session,
            UrlProtector("test-secret"),
        ).get_app_by_public_id(str(disabled.id))

        assert result is None
    await engine.dispose()


@pytest.mark.asyncio
async def test_repair_resolved_source_platforms_moves_cross_platform_installers() -> None:
    """Comprueba el escenario `repair_resolved_source_platforms_moves_cross_platform_installers`.
    """
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

        repaired = await CatalogRepository(
            session,
            UrlProtector("test-secret"),
        ).repair_resolved_source_platforms()
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


@pytest.mark.asyncio
async def test_refresh_source_status_uses_latest_direct_candidate() -> None:
    """Comprueba el escenario `refresh_source_status_uses_latest_direct_candidate`.
    """
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    now = utc_now()
    async with session_factory() as session:
        app = SoftwareApp(
            id=uuid4(),
            winstall_id="0xGingi.Browser",
            slug="0xgingi-browser",
            name="0xGingi-Browser",
            normalized_name="0xgingi browser",
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
        source.resolved_sources = [
            ResolvedSource(
                id=uuid4(),
                download_source_id=source.id,
                resolved_url_encrypted="direct",
                final_domain="githubusercontent.com",
                filename="Browser-latest.exe",
                extension=".exe",
                version="115.0.5790.110",
                release_rank=0,
                is_latest=True,
                score=174,
                status=ResolutionStatus.DIRECT.value,
                validation_status=ValidationStatus.VALID.value,
                checked_at=now,
                expires_at=utc_after(hours=1),
            ),
            ResolvedSource(
                id=uuid4(),
                download_source_id=source.id,
                resolved_url_encrypted="fallback",
                final_domain="githubusercontent.com",
                filename="Browser-latest-from-winstall.exe",
                extension=".exe",
                version="115.0.5790.110",
                release_rank=0,
                is_latest=True,
                score=200,
                status=ResolutionStatus.FALLBACK.value,
                validation_status=ValidationStatus.VALID.value,
                checked_at=now,
                expires_at=utc_after(hours=1),
            ),
        ]
        app.sources = [source]
        session.add(app)
        await session.commit()

        repository = CatalogRepository(session, UrlProtector("test-secret"))
        await repository.refresh_source_statuses({source.id})
        await session.commit()

        assert source.resolution_status == ResolutionStatus.DIRECT.value
        assert source.validation_status == ValidationStatus.VALID.value

    await engine.dispose()


@pytest.mark.asyncio
async def test_so_filter_projects_platforms_with_verified_binary_history() -> None:
    """Comprueba el escenario `so_filter_projects_platforms_with_verified_binary_history`.
    """
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    now = utc_now()
    async with session_factory() as session:
        app = SoftwareApp(
            id=uuid4(),
            winstall_id="Vendor.CrossPlatform",
            slug="vendor-cross-platform",
            name="Cross Platform",
            normalized_name="cross platform",
            app_status="active",
            operating_systems=["linux"],
            created_at=now,
            updated_at=now,
        )
        windows = source_with_resolved_app(
            app,
            "windows",
            expires_in_hours=1,
            metadata={"validation_confidence": "verified"},
        )
        linux = source_with_resolved_app(
            app,
            "linux",
            expires_in_hours=1,
            metadata={
                "validation_confidence": "verified",
                "transport_security": "https_winstall_edge_attested",
            },
        )
        macos = source_with_resolved_app(
            app,
            "macos",
            expires_in_hours=-1,
            metadata={"validation_confidence": "validated"},
        )
        app.sources = [windows, linux, macos]
        session.add(app)
        await session.commit()

        systems = await CatalogRepository(
            session,
            UrlProtector("test-secret"),
        ).refresh_operating_systems(app.id)
        await session.commit()

        assert systems == ["windows", "macos"]
        assert app.operating_systems == ["windows", "macos"]
        assert app.operating_systems_updated_at is not None
        assert app.version == 1

        systems = await CatalogRepository(
            session,
            UrlProtector("test-secret"),
        ).refresh_operating_systems(app.id)
        await session.commit()
        assert systems == ["windows", "macos"]
        assert app.version == 1
        assert await CatalogRepository(
            session,
            UrlProtector("test-secret"),
        ).apps_pending_os_filter() == []

        app.operating_systems_updated_at = utc_after(hours=-25)
        await session.commit()
        pending = await CatalogRepository(
            session,
            UrlProtector("test-secret"),
        ).apps_pending_os_filter()
        assert [item.id for item in pending] == [app.id]

    await engine.dispose()


def software_app(
    winstall_id: str,
    *,
    long_description_status: str,
    long_description: str | None,
) -> SoftwareApp:
    """Ejecuta la operación `software_app`.

    Args:
        winstall_id (str): Identificador de `winstall` utilizado por la operación.
        long_description_status (str): Valor de `long_description_status` utilizado por la
            operación.
        long_description (str | None): Valor de `long_description` utilizado por la operación.

    Returns:
        SoftwareApp: Resultado producido por la operación.
    """
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


def source_with_resolved_app(
    app: SoftwareApp,
    operating_system: str,
    *,
    expires_in_hours: int,
    metadata: dict[str, str],
) -> DownloadSource:
    """Ejecuta la operación `source_with_resolved_app`.

    Args:
        app (SoftwareApp): Aplicación sobre la que se realiza la operación.
        operating_system (str): Valor de `operating_system` utilizado por la operación.
        expires_in_hours (int): Valor de `expires_in_hours` utilizado por la operación.
        metadata (dict[str, str]): Valor de `metadata` utilizado por la operación.

    Returns:
        DownloadSource: Resultado producido por la operación.
    """
    now = utc_now()
    source = DownloadSource(
        id=uuid4(),
        software_app_id=app.id,
        operating_system=operating_system,
        architecture="UNKNOWN",
        resolution_status=ResolutionStatus.DIRECT.value,
        validation_status=ValidationStatus.VALID.value,
    )
    source.resolved_sources = [
        ResolvedSource(
            id=uuid4(),
            download_source_id=source.id,
            resolved_url_encrypted=f"encrypted-{operating_system}",
            final_domain="example.com",
            filename=f"app-{operating_system}",
            score=100,
            status=ResolutionStatus.DIRECT.value,
            validation_status=ValidationStatus.VALID.value,
            checked_at=now,
            expires_at=utc_after(hours=expires_in_hours),
            metadata_json=metadata,
        )
    ]
    return source
