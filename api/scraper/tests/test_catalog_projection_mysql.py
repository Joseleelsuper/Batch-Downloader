"""Contiene las pruebas de `test_catalog_projection_mysql`.
"""
from __future__ import annotations

import asyncio
import os
from collections.abc import Iterator
from pathlib import Path
from uuid import UUID, uuid4

import pytest
from alembic.config import Config
from sqlalchemy import make_url, select, text
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine

from alembic import command
from app.api.app_mapper import to_details
from app.core.config import get_settings
from app.core.time import utc_after, utc_now
from app.core.url_protector import UrlProtector
from app.db.enums import ResolutionStatus, ValidationStatus
from app.db.models import CatalogCounter, DownloadSource, ResolvedSource, SoftwareApp
from app.repositories.catalog import CatalogRepository
from app.repositories.catalog_projection import CatalogProjectionRepository

testcontainers_mysql = pytest.importorskip("testcontainers.mysql")
"""Estado global asociado a `testcontainers_mysql`.
"""
MySqlContainer = testcontainers_mysql.MySqlContainer
"""Estado global asociado a `MySqlContainer`.
"""

SCRAPER_ROOT = Path(__file__).parents[1]
"""Constante que define `SCRAPER_ROOT`.
"""


@pytest.fixture(scope="module")
def mysql_url() -> Iterator[str]:
    """Ejecuta la operación `mysql_url`.

    Yields:
        Iterator[str]: Elemento producido por la operación.
    """
    container = MySqlContainer("mysql:8.4").with_command(
        "--log-bin-trust-function-creators=1"
    )
    try:
        container.start()
    except Exception as exc:  # pragma: no cover - depende del runtime Docker del host
        if os.environ.get("CI"):
            pytest.fail(
                f"Docker-backed MySQL is required in CI: {exc.__class__.__name__}"
            )
        pytest.skip(f"Docker-backed MySQL unavailable: {exc.__class__.__name__}")
    try:
        connection_url = make_url(container.get_connection_url()).set(
            drivername="mysql+aiomysql"
        )
        yield connection_url.render_as_string(hide_password=False)
    finally:
        container.stop()


@pytest.mark.mysql
def test_mysql_projection_backfill_triggers_rollback_and_repair(
    mysql_url: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Comprueba el escenario `mysql_projection_backfill_triggers_rollback_and_repair`.

    Args:
        mysql_url (str): Dirección de `mysql` que debe procesarse.
        monkeypatch (pytest.MonkeyPatch): Utilidad de pytest para sustituir dependencias durante la
            prueba.
    """
    monkeypatch.setenv("SCRAPPER_DATABASE_URL_OVERRIDE", mysql_url)
    get_settings.cache_clear()
    config = Config(str(SCRAPER_ROOT / "alembic.ini"))
    config.set_main_option("script_location", str(SCRAPER_ROOT / "alembic"))
    command.upgrade(config, "20260716_0010")
    asyncio.run(seed_pre_projection_catalog(mysql_url))
    command.upgrade(config, "head")

    async def scenario() -> None:
        """Ejecuta la operación `scenario`.
        """
        engine = create_async_engine(mysql_url, pool_pre_ping=True)
        try:
            async with AsyncSession(engine, expire_on_commit=False) as session:
                projection = CatalogProjectionRepository(session)
                datetime_precision = await session.scalar(
                    text(
                        "SELECT DATETIME_PRECISION FROM information_schema.COLUMNS "
                        "WHERE TABLE_SCHEMA = DATABASE() "
                        "AND TABLE_NAME = 'software_apps' "
                        "AND COLUMN_NAME = 'updated_at'"
                    )
                )
                assert datetime_precision == 6
                await assert_counters(session, 1, 1, 0, 0)
                await session.execute(
                    text(
                        "DELETE FROM resolved_sources "
                        "WHERE id = UUID_TO_BIN('00000000-0000-0000-0000-000000000003')"
                    )
                )
                await session.execute(
                    text(
                        "DELETE FROM download_sources "
                        "WHERE id = UUID_TO_BIN('00000000-0000-0000-0000-000000000002')"
                    )
                )
                await session.execute(
                    text(
                        "DELETE FROM software_apps "
                        "WHERE id = UUID_TO_BIN('00000000-0000-0000-0000-000000000001')"
                    )
                )
                await session.commit()
                await assert_counters(session, 0, 0, 0, 0)

                now = utc_now()
                app = SoftwareApp(
                    winstall_id="Projection.Test",
                    slug="projection-test",
                    name="Projection Test",
                    normalized_name="projection test",
                    app_status="active",
                    created_at=now,
                    updated_at=now,
                )
                session.add(app)
                await session.commit()
                await assert_counters(session, 1, 0, 0, 1)
                version_before_non_transition = await counter_version(session)
                app.name = "Projection Test Renamed"
                await session.commit()
                assert await counter_version(session) == version_before_non_transition

                source = DownloadSource(
                    software_app_id=app.id,
                    operating_system="windows",
                    architecture="x86_64",
                    resolution_status=ResolutionStatus.REQUIRES_MANUAL_REVIEW.value,
                    validation_status=ValidationStatus.UNCHECKED.value,
                )
                session.add(source)
                await session.commit()
                await assert_counters(session, 1, 0, 1, 0)

                source.resolution_status = ResolutionStatus.DIRECT.value
                source.validation_status = ValidationStatus.VALID.value
                await session.commit()
                await assert_counters(session, 1, 0, 0, 1)

                first = stale_candidate(source)
                second = stale_candidate(source)
                session.add_all((first, second))
                await session.commit()
                await assert_counters(session, 1, 1, 0, 0)
                async with AsyncSession(engine, expire_on_commit=False) as public_session:
                    public_app = await CatalogRepository(
                        public_session,
                        UrlProtector("projection-test-secret"),
                    ).get_app_by_public_id(str(app.id))
                    assert public_app is not None
                    assert all(
                        candidate.catalog_downloadable is True
                        for candidate_source in public_app.sources
                        for candidate in candidate_source.resolved_sources
                    )
                    assert to_details(public_app).downloadable is True

                other = SoftwareApp(
                    winstall_id="Projection.Other",
                    slug="projection-other",
                    name="Projection Other",
                    normalized_name="projection other",
                    app_status="active",
                    created_at=now,
                    updated_at=now,
                )
                session.add(other)
                await session.commit()
                await assert_counters(session, 2, 1, 0, 1)

                source.software_app_id = other.id
                await session.commit()
                await assert_app_status(session, app.id, "missing")
                await assert_app_status(session, other.id, "available")
                await assert_counters(session, 2, 1, 0, 1)

                source.software_app_id = app.id
                await session.commit()
                await assert_app_status(session, app.id, "available")
                await assert_app_status(session, other.id, "missing")
                other.app_status = "disabled"
                await session.commit()
                await assert_counters(session, 1, 1, 0, 0)

                first.validation_status = ValidationStatus.EXPIRED.value
                await session.commit()
                await assert_counters(session, 1, 1, 0, 0)

                # La deriva solo se detecta y repara mediante el comando fuera de línea.
                await session.execute(
                    text(
                        """
                        UPDATE catalog_counters
                        SET available_count = available_count - 1,
                            missing_count = missing_count + 1
                        WHERE id = 1
                        """
                    )
                )
                await session.commit()
                assert (await projection.check()).consistent is False
                assert (await projection.repair()).consistent is True
                assert (await projection.repair()).consistent is True
                await assert_counters(session, 1, 1, 0, 0)

                source_id = source.id
                app_id = app.id
                second_id = second.id
                await session.rollback()
                response = await invoke_terminal_resolution(engine, second_id)
                assert response.status_code == 409
                await session.rollback()
                await assert_app_status(session, app_id, "missing")
                await assert_counters(session, 1, 0, 0, 1)
                invalidated = await session.get(
                    ResolvedSource,
                    second_id,
                    populate_existing=True,
                )
                assert invalidated is not None
                assert invalidated.validation_status == ValidationStatus.EXPIRED.value
                assert invalidated.metadata_json["last_revalidation_error"] == (
                    "source_url_unreadable"
                )
                source = await session.get(
                    DownloadSource,
                    source_id,
                    populate_existing=True,
                )
                app = await session.get(SoftwareApp, app_id, populate_existing=True)
                assert source is not None
                assert app is not None

                source.resolution_status = ResolutionStatus.REQUIRES_MANUAL_REVIEW.value
                source.validation_status = ValidationStatus.UNCHECKED.value
                await session.commit()
                await assert_counters(session, 1, 0, 1, 0)

                await session.delete(source)
                await session.commit()
                await assert_counters(session, 1, 0, 0, 1)

                app.app_status = "disabled"
                await session.commit()
                await assert_counters(session, 0, 0, 0, 0)

                app.app_status = "active"
                await session.flush()
                await session.rollback()
                await assert_counters(session, 0, 0, 0, 0)
                assert (await projection.check()).consistent is True

                concurrent_app = SoftwareApp(
                    winstall_id="Projection.Concurrent",
                    slug="projection-concurrent",
                    name="Projection Concurrent",
                    normalized_name="projection concurrent",
                    app_status="active",
                    created_at=now,
                    updated_at=now,
                )
                session.add(concurrent_app)
                await session.commit()
                concurrent_app_id = concurrent_app.id
                source_ids = (uuid4(), uuid4())

                async def insert_review_source(source_id: UUID) -> None:
                    """Ejecuta la operación `insert_review_source`.

                    Args:
                        source_id (UUID): Identificador de `source` utilizado por la operación.
                    """
                    async with AsyncSession(engine, expire_on_commit=False) as writer:
                        writer.add(
                            DownloadSource(
                                id=source_id,
                                software_app_id=concurrent_app_id,
                                operating_system="windows",
                                architecture="x86_64",
                                resolution_status=(
                                    ResolutionStatus.REQUIRES_MANUAL_REVIEW.value
                                ),
                                validation_status=ValidationStatus.UNCHECKED.value,
                            )
                        )
                        await writer.commit()

                await asyncio.gather(
                    *(insert_review_source(source_id) for source_id in source_ids)
                )
                await assert_app_projection_counts(
                    session,
                    concurrent_app_id,
                    available=0,
                    review=2,
                )
                await assert_counters(session, 1, 0, 1, 0)
                await session.rollback()

                async def make_source_available(source_id: UUID) -> None:
                    """Construye la operación `source_available`.

                    Args:
                        source_id (UUID): Identificador de `source` utilizado por la operación.
                    """
                    async with AsyncSession(engine, expire_on_commit=False) as writer:
                        concurrent_source = await writer.get(DownloadSource, source_id)
                        assert concurrent_source is not None
                        concurrent_source.resolution_status = ResolutionStatus.DIRECT.value
                        concurrent_source.validation_status = ValidationStatus.VALID.value
                        writer.add(stale_candidate(concurrent_source))
                        await writer.commit()

                await asyncio.gather(
                    *(make_source_available(source_id) for source_id in source_ids)
                )
                await session.rollback()
                await assert_app_projection_counts(
                    session,
                    concurrent_app_id,
                    available=2,
                    review=0,
                )
                await assert_counters(session, 1, 1, 0, 0)

                candidate_id = await session.scalar(
                    select(ResolvedSource.id).where(
                        ResolvedSource.download_source_id == source_ids[0]
                    )
                )
                assert candidate_id is not None
                candidate = await session.get(ResolvedSource, candidate_id)
                assert candidate is not None
                candidate.download_source_id = source_ids[1]
                await session.commit()
                assert await projected_source_count(session, source_ids[0]) == 0
                assert await projected_source_count(session, source_ids[1]) == 2
                await assert_app_projection_counts(
                    session,
                    concurrent_app_id,
                    available=1,
                    review=0,
                )
                await assert_counters(session, 1, 1, 0, 0)

                await session.execute(
                    text(
                        "UPDATE resolved_sources SET validation_status = 'expired' "
                        "WHERE download_source_id = :source_id"
                    ),
                    {"source_id": source_ids[1].bytes},
                )
                await assert_counters(session, 1, 0, 0, 1)
                await session.rollback()
                await assert_counters(session, 1, 1, 0, 0)

                await session.execute(
                    text(
                        "UPDATE download_sources SET catalog_downloadable_count = 0 "
                        "WHERE id = :source_id"
                    ),
                    {"source_id": source_ids[1].bytes},
                )
                await session.execute(
                    text(
                        "UPDATE software_apps SET catalog_review_source_count = 3 "
                        "WHERE id = :app_id"
                    ),
                    {"app_id": concurrent_app_id.bytes},
                )
                await session.commit()
                inconsistent = await projection.check()
                assert inconsistent.source_mismatches == 1
                assert inconsistent.app_mismatches == 1
                assert inconsistent.consistent is False
                assert (await projection.repair()).consistent is True
                await assert_counters(session, 1, 1, 0, 0)

                await session.execute(
                    text(
                        "DELETE FROM resolved_sources "
                        "WHERE download_source_id IN (:first_source, :second_source)"
                    ),
                    {
                        "first_source": source_ids[0].bytes,
                        "second_source": source_ids[1].bytes,
                    },
                )
                await session.commit()
                await assert_app_status(session, concurrent_app_id, "missing")
                await assert_counters(session, 1, 0, 0, 1)

                await session.execute(
                    text(
                        "DELETE FROM download_sources "
                        "WHERE id IN (:first_source, :second_source)"
                    ),
                    {
                        "first_source": source_ids[0].bytes,
                        "second_source": source_ids[1].bytes,
                    },
                )
                await session.commit()
                await assert_app_status(session, concurrent_app_id, "missing")
                await assert_counters(session, 1, 0, 0, 1)
        finally:
            await engine.dispose()

    try:
        asyncio.run(scenario())
        command.downgrade(config, "20260716_0010")
        asyncio.run(assert_projection_downgraded(mysql_url))
    finally:
        get_settings.cache_clear()


async def seed_pre_projection_catalog(mysql_url: str) -> None:
    """Ejecuta la operación `seed_pre_projection_catalog`.

    Args:
        mysql_url (str): Dirección de `mysql` que debe procesarse.
    """
    engine = create_async_engine(mysql_url, pool_pre_ping=True)
    try:
        async with engine.begin() as connection:
            await connection.execute(
                text(
                    """
                    INSERT INTO software_apps (
                        id, winstall_id, slug, name, normalized_name, app_status,
                        operating_systems_json, version, created_at, updated_at
                    ) VALUES (
                        UUID_TO_BIN('00000000-0000-0000-0000-000000000001'),
                        'Projection.Legacy', 'projection-legacy', 'Projection Legacy',
                        'projection legacy', 'active', JSON_ARRAY(), 0,
                        UTC_TIMESTAMP(), UTC_TIMESTAMP()
                    )
                    """
                )
            )
            await connection.execute(
                text(
                    """
                    INSERT INTO download_sources (
                        id, software_app_id, operating_system, architecture,
                        resolver_type, resolution_status, validation_status,
                        version, created_at, updated_at
                    ) VALUES (
                        UUID_TO_BIN('00000000-0000-0000-0000-000000000002'),
                        UUID_TO_BIN('00000000-0000-0000-0000-000000000001'),
                        'windows', 'x86_64', 'generic_http', 'direct', 'valid',
                        0, UTC_TIMESTAMP(), UTC_TIMESTAMP()
                    )
                    """
                )
            )
            await connection.execute(
                text(
                    """
                    INSERT INTO resolved_sources (
                        id, download_source_id, resolved_url_encrypted, final_domain,
                        filename, extension, content_type, size_bytes, score, status,
                        validation_status, checked_at, expires_at, metadata_json
                    ) VALUES (
                        UUID_TO_BIN('00000000-0000-0000-0000-000000000003'),
                        UUID_TO_BIN('00000000-0000-0000-0000-000000000002'),
                        'encrypted', 'example.test', 'legacy.exe', '.exe',
                        'application/octet-stream', 4096, 100, 'direct', 'valid',
                        UTC_TIMESTAMP() - INTERVAL 48 HOUR,
                        UTC_TIMESTAMP() - INTERVAL 24 HOUR,
                        JSON_OBJECT('validation_confidence', 'validated')
                    )
                    """
                )
            )
    finally:
        await engine.dispose()


async def invoke_terminal_resolution(engine, candidate_id: UUID):
    """Ejecuta la operación `invoke_terminal_resolution`.

    Args:
        engine (Any): Valor de `engine` utilizado por la operación.
        candidate_id (UUID): Identificador de `candidate` utilizado por la operación.
    """
    import httpx
    from fastapi import FastAPI

    from app.api.internal_routes import INTERNAL_SERVICE_TOKEN_HEADER, internal_router
    from app.core.config import Settings
    from app.db.session import get_session

    token = "projection-internal-token"
    settings = Settings(
        database_url_override="sqlite+aiosqlite:///:memory:",
        internal_service_token=token,
        url_protection_secret="projection-test-secret",
    )
    application = FastAPI()
    application.include_router(internal_router)
    application.dependency_overrides[get_settings] = lambda: settings

    async def override_session():
        """Ejecuta la operación `override_session`.

        Yields:
            Any: Elemento producido por la operación.
        """
        async with AsyncSession(engine, expire_on_commit=False) as session:
            yield session

    application.dependency_overrides[get_session] = override_session
    transport = httpx.ASGITransport(app=application)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.get(
            f"/internal/v1/sources/{candidate_id}/resolution",
            headers={INTERNAL_SERVICE_TOKEN_HEADER: token},
        )


def stale_candidate(source: DownloadSource) -> ResolvedSource:
    """Ejecuta la operación `stale_candidate`.

    Args:
        source (DownloadSource): Fuente de descarga sobre la que se actúa.

    Returns:
        ResolvedSource: Resultado producido por la operación.
    """
    return ResolvedSource(
        download_source_id=source.id,
        resolved_url_encrypted="encrypted",
        final_domain="example.test",
        filename="setup.exe",
        extension=".exe",
        content_type="application/octet-stream",
        size_bytes=4096,
        score=100,
        status=ResolutionStatus.DIRECT.value,
        validation_status=ValidationStatus.VALID.value,
        checked_at=utc_after(hours=-48),
        expires_at=utc_after(hours=-24),
        metadata_json={"validation_confidence": "validated"},
    )


async def assert_counters(
    session: AsyncSession,
    total: int,
    available: int,
    review: int,
    missing: int,
) -> None:
    """Comprueba la operación `counters`.

    Args:
        session (AsyncSession): Sesión de base de datos utilizada por la operación.
        total (int): Valor de `total` utilizado por la operación.
        available (int): Valor de `available` utilizado por la operación.
        review (int): Valor de `review` utilizado por la operación.
        missing (int): Valor de `missing` utilizado por la operación.
    """
    counters = await session.scalar(
        select(CatalogCounter)
        .where(CatalogCounter.id == 1)
        .execution_options(populate_existing=True)
    )
    assert counters is not None
    assert (
        counters.total_count,
        counters.available_count,
        counters.review_count,
        counters.missing_count,
    ) == (total, available, review, missing)
    assert total == available + review + missing


async def counter_version(session: AsyncSession) -> int:
    """Ejecuta la operación `counter_version`.

    Args:
        session (AsyncSession): Sesión de base de datos utilizada por la operación.

    Returns:
        int: Número de elementos afectados por la operación.
    """
    counters = await session.get(CatalogCounter, 1, populate_existing=True)
    assert counters is not None
    return int(counters.version)


async def assert_app_status(
    session: AsyncSession,
    app_id: UUID,
    expected: str,
) -> None:
    """Comprueba la operación `app_status`.

    Args:
        session (AsyncSession): Sesión de base de datos utilizada por la operación.
        app_id (UUID): Identificador de `app` utilizado por la operación.
        expected (str): Valor de `expected` utilizado por la operación.
    """
    status = await session.scalar(
        select(SoftwareApp.catalog_status).where(SoftwareApp.id == app_id)
    )
    assert status == expected


async def assert_app_projection_counts(
    session: AsyncSession,
    app_id: UUID,
    *,
    available: int,
    review: int,
) -> None:
    """Comprueba la operación `app_projection_counts`.

    Args:
        session (AsyncSession): Sesión de base de datos utilizada por la operación.
        app_id (UUID): Identificador de `app` utilizado por la operación.
        available (int): Valor de `available` utilizado por la operación.
        review (int): Valor de `review` utilizado por la operación.
    """
    counts = (
        await session.execute(
            select(
                SoftwareApp.catalog_available_source_count,
                SoftwareApp.catalog_review_source_count,
            ).where(SoftwareApp.id == app_id)
        )
    ).one()
    assert counts == (available, review)


async def projected_source_count(session: AsyncSession, source_id: UUID) -> int:
    """Ejecuta la operación `projected_source_count`.

    Args:
        session (AsyncSession): Sesión de base de datos utilizada por la operación.
        source_id (UUID): Identificador de `source` utilizado por la operación.

    Returns:
        int: Número de elementos afectados por la operación.
    """
    count = await session.scalar(
        select(DownloadSource.catalog_downloadable_count).where(
            DownloadSource.id == source_id
        )
    )
    assert count is not None
    return int(count)


async def assert_projection_downgraded(mysql_url: str) -> None:
    """Comprueba la operación `projection_downgraded`.

    Args:
        mysql_url (str): Dirección de `mysql` que debe procesarse.
    """
    engine = create_async_engine(mysql_url, pool_pre_ping=True)
    try:
        async with AsyncSession(engine) as session:
            trigger_count = await session.scalar(
                text(
                    "SELECT COUNT(*) FROM information_schema.TRIGGERS "
                    "WHERE TRIGGER_SCHEMA = DATABASE() "
                    "AND TRIGGER_NAME LIKE 'trg_%_catalog_%'"
                )
            )
            projection_column_count = await session.scalar(
                text(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS "
                    "WHERE TABLE_SCHEMA = DATABASE() AND ("
                    "(TABLE_NAME = 'software_apps' AND COLUMN_NAME LIKE 'catalog_%') "
                    "OR (TABLE_NAME = 'download_sources' AND COLUMN_NAME LIKE 'catalog_%')"
                    ")"
                )
            )
            counters_present = await session.scalar(
                text(
                    "SELECT COUNT(*) FROM information_schema.TABLES "
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'catalog_counters'"
                )
            )
            assert trigger_count == 0
            assert projection_column_count == 0
            assert counters_present == 0
    finally:
        await engine.dispose()
