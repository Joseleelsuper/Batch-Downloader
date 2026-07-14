from __future__ import annotations

from dataclasses import dataclass
from uuid import UUID, uuid4

import httpx
import pytest
import pytest_asyncio
from fastapi import FastAPI
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.api.internal_routes import INTERNAL_SERVICE_TOKEN_HEADER, internal_router
from app.core.config import Settings, get_settings
from app.core.time import utc_after, utc_now
from app.core.url_protector import UrlProtector
from app.db.base import Base
from app.db.enums import ResolutionStatus, ValidationStatus
from app.db.models import DownloadSource, ResolvedSource, SoftwareApp
from app.db.session import get_session
from app.scraper.validator import ValidationConfidence, ValidationResult

INTERNAL_TOKEN = "worker-test-token"
URL_SECRET = "internal-route-test-secret"


@dataclass
class InternalApiFixture:
    client: httpx.AsyncClient
    session: AsyncSession

    async def add_source(
        self,
        *,
        confidence: str,
        expires_in_hours: int = 1,
    ) -> tuple[SoftwareApp, ResolvedSource, str]:
        identifier = uuid4()
        app = SoftwareApp(
            id=uuid4(),
            winstall_id=f"Vendor.App.{identifier}",
            slug=f"vendor-app-{identifier}",
            name="Vendor App",
            normalized_name=f"vendor app {identifier}",
            app_status="active",
        )
        source = DownloadSource(
            id=uuid4(),
            software_app_id=app.id,
            operating_system="windows",
            architecture="x86_64",
            resolution_status=ResolutionStatus.DIRECT.value,
            validation_status=ValidationStatus.VALID.value,
        )
        download_url = f"https://downloads.example.test/{identifier}/AppSetup.exe"
        resolved = ResolvedSource(
            id=uuid4(),
            download_source_id=source.id,
            resolved_url_encrypted=UrlProtector(URL_SECRET).protect(download_url),
            final_domain="example.test",
            filename="AppSetup.exe",
            extension=".exe",
            content_type="application/octet-stream",
            size_bytes=4096,
            score=100,
            status=ResolutionStatus.DIRECT.value,
            validation_status=ValidationStatus.VALID.value,
            checked_at=utc_now(),
            expires_at=utc_after(hours=expires_in_hours),
            metadata_json={
                "validation_confidence": confidence,
                "sha256": "a" * 64,
            },
        )
        app.sources = [source]
        source.resolved_sources = [resolved]
        self.session.add(app)
        await self.session.commit()
        return app, resolved, download_url


@pytest_asyncio.fixture
async def internal_api() -> InternalApiFixture:
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    async with session_factory() as session:
        settings = Settings(
            database_url_override="sqlite+aiosqlite:///:memory:",
            internal_service_token=INTERNAL_TOKEN,
            url_protection_secret=URL_SECRET,
        )
        application = FastAPI()
        application.include_router(internal_router)
        application.dependency_overrides[get_settings] = lambda: settings

        async def override_session():
            yield session

        application.dependency_overrides[get_session] = override_session
        transport = httpx.ASGITransport(app=application)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            yield InternalApiFixture(client=client, session=session)
    await engine.dispose()


@pytest.mark.asyncio
async def test_internal_resolution_requires_constant_time_service_token(
    internal_api: InternalApiFixture,
    monkeypatch,
) -> None:
    comparisons: list[tuple[str, str]] = []

    def compare_digest(provided: str, expected: str) -> bool:
        comparisons.append((provided, expected))
        return provided == expected

    monkeypatch.setattr("app.api.internal_routes.secrets.compare_digest", compare_digest)

    missing = await internal_api.client.get(
        f"/internal/v1/sources/{uuid4()}/resolution"
    )
    invalid = await internal_api.client.get(
        f"/internal/v1/sources/{uuid4()}/resolution",
        headers={INTERNAL_SERVICE_TOKEN_HEADER: "wrong-token"},
    )

    assert missing.status_code == 401
    assert invalid.status_code == 401
    assert comparisons == [("", INTERNAL_TOKEN), ("wrong-token", INTERNAL_TOKEN)]


@pytest.mark.asyncio
async def test_internal_resolution_returns_verified_source_without_logging_secrets(
    internal_api: InternalApiFixture,
    caplog,
) -> None:
    app, resolved, download_url = await internal_api.add_source(confidence="validated")

    response = await internal_api.client.get(
        f"/internal/v1/sources/{resolved.id}/resolution",
        headers={INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN},
    )

    assert response.status_code == 200
    assert response.json() == {
        "sourceRef": str(resolved.id),
        "appId": str(app.id),
        "url": download_url,
        "expectedFilename": "AppSetup.exe",
        "expectedSizeBytes": 4096,
        "expectedSha256": "a" * 64,
        "expectedMime": "application/octet-stream",
        "operatingSystem": "windows",
        "architecture": "x86_64",
        "trustStatus": "VERIFIED",
    }
    assert INTERNAL_TOKEN not in caplog.text
    assert download_url not in caplog.text


@pytest.mark.asyncio
async def test_internal_resolution_rejects_non_verified_sources(
    internal_api: InternalApiFixture,
) -> None:
    _app, resolved, _download_url = await internal_api.add_source(
        confidence="attested",
    )

    response = await internal_api.client.get(
        f"/internal/v1/sources/{resolved.id}/resolution",
        headers={INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN},
    )

    assert response.status_code == 409
    assert response.json()["trustStatus"] == "ATTESTED"
    assert response.json()["url"] is None


@pytest.mark.asyncio
async def test_internal_resolution_revalidates_expired_candidate_before_revealing_url(
    internal_api: InternalApiFixture,
    monkeypatch,
) -> None:
    app, resolved, download_url = await internal_api.add_source(
        confidence="validated",
        expires_in_hours=-1,
    )

    async def validate(_validator, candidate):
        assert candidate.url == download_url
        return ValidationResult(
            ok=True,
            url=candidate.url,
            final_url=candidate.url,
            final_domain="example.test",
            filename="AppSetup.exe",
            extension=".exe",
            content_type="application/octet-stream",
            size_bytes=4096,
            confidence=ValidationConfidence.VALIDATED,
        )

    monkeypatch.setattr("app.api.internal_routes.DownloadValidator.validate", validate)

    response = await internal_api.client.get(
        f"/internal/v1/sources/{resolved.id}/resolution",
        headers={INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN},
    )

    assert response.status_code == 200
    assert response.json()["appId"] == str(app.id)
    assert response.json()["url"] == download_url
    assert response.json()["trustStatus"] == "VERIFIED"
    assert resolved.expires_at > utc_now()
    assert resolved.validation_status == ValidationStatus.VALID.value


@pytest.mark.asyncio
async def test_internal_resolution_keeps_failed_revalidation_secret(
    internal_api: InternalApiFixture,
    monkeypatch,
) -> None:
    _app, resolved, download_url = await internal_api.add_source(
        confidence="validated",
        expires_in_hours=-1,
    )

    async def validate(_validator, candidate):
        return ValidationResult(ok=False, url=candidate.url, reason="http_404")

    monkeypatch.setattr("app.api.internal_routes.DownloadValidator.validate", validate)

    response = await internal_api.client.get(
        f"/internal/v1/sources/{resolved.id}/resolution",
        headers={INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN},
    )

    assert response.status_code == 409
    assert response.json()["trustStatus"] == "UNRESOLVED"
    assert response.json()["url"] is None
    assert download_url not in response.text
    assert resolved.validation_status == ValidationStatus.EXPIRED.value
    assert resolved.metadata_json["last_revalidation_error"] == "http_404"


@pytest.mark.asyncio
async def test_internal_resolution_never_reveals_http_url(
    internal_api: InternalApiFixture,
) -> None:
    _app, resolved, _download_url = await internal_api.add_source(
        confidence="validated",
    )
    insecure_url = "http://downloads.example.test/AppSetup.exe"
    resolved.resolved_url_encrypted = UrlProtector(URL_SECRET).protect(insecure_url)
    await internal_api.session.commit()

    response = await internal_api.client.get(
        f"/internal/v1/sources/{resolved.id}/resolution",
        headers={INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN},
    )

    assert response.status_code == 409
    assert response.json()["url"] is None
    assert insecure_url not in response.text


@pytest.mark.asyncio
async def test_internal_resolution_returns_not_found_for_unknown_reference(
    internal_api: InternalApiFixture,
) -> None:
    unknown_ref = UUID(int=0)

    response = await internal_api.client.get(
        f"/internal/v1/sources/{unknown_ref}/resolution",
        headers={INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN},
    )

    assert response.status_code == 404
