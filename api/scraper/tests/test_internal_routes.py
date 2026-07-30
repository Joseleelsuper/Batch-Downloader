"""Contiene las pruebas de `test_internal_routes`.
"""
from __future__ import annotations

from dataclasses import dataclass
from uuid import UUID, uuid4

import httpx
import pytest
import pytest_asyncio
from fastapi import FastAPI
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.api.internal_routes import INTERNAL_SERVICE_TOKEN_HEADER, internal_router
from app.core.config import Settings, get_settings
from app.core.time import utc_after, utc_now
from app.core.url_protector import UrlProtector
from app.db.base import Base
from app.db.enums import ResolutionStatus, ValidationStatus
from app.db.models import (
    DownloadSource,
    ManualInstallerInspection,
    ResolvedSource,
    ScraperWorkItem,
    SoftwareApp,
    SoftwareAppTag,
    WebsiteAppDiscovery,
    WebsiteAppDiscoveryInstaller,
)
from app.db.session import get_session
from app.scraper.manual_installer import ValidatedManualInstaller
from app.scraper.validator import ValidationConfidence, ValidationResult

INTERNAL_TOKEN = "worker-test-token"
"""Constante que define `INTERNAL_TOKEN`.
"""
URL_SECRET = "internal-route-test-secret"
"""Constante que define `URL_SECRET`.
"""


@dataclass
class InternalApiFixture:
    """Agrupa los escenarios de prueba de `InternalApiFixture`.
    """
    client: httpx.AsyncClient
    """Atributo de clase `client` de `InternalApiFixture`.
    """
    session: AsyncSession
    """Atributo de clase `session` de `InternalApiFixture`.
    """

    async def add_source(
        self,
        *,
        confidence: str,
        expires_in_hours: int = 1,
    ) -> tuple[SoftwareApp, ResolvedSource, str]:
        """Ejecuta `add_source` dentro de `InternalApiFixture`.

        Args:
            confidence (str): Valor de `confidence` utilizado por la operación.
            expires_in_hours (int): Valor de `expires_in_hours` utilizado por la operación.

        Returns:
            tuple[SoftwareApp, ResolvedSource, str]: Resultado producido por la operación.
        """
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
            catalog_downloadable_count=1,
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

    async def add_unresolved_app(self) -> SoftwareApp:
        """Ejecuta `add_unresolved_app` dentro de `InternalApiFixture`.

        Returns:
            SoftwareApp: Resultado producido por la operación.
        """
        identifier = uuid4()
        app = SoftwareApp(
            id=uuid4(),
            winstall_id=f"manual.{identifier}",
            slug=f"manual-{identifier}",
            name="Manual App",
            normalized_name=f"manual app {identifier}",
            publisher="Manual Vendor",
            description="Existing description",
            latest_version="1.0.0",
            app_status="active",
        )
        self.session.add(app)
        await self.session.commit()
        return app


@pytest_asyncio.fixture
async def internal_api() -> InternalApiFixture:
    """Ejecuta la operación `internal_api`.

    Yields:
        InternalApiFixture: Elemento producido por la operación.
    """
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
            """Ejecuta la operación `override_session`.

            Yields:
                Any: Elemento producido por la operación.
            """
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
    """Comprueba el escenario `internal_resolution_requires_constant_time_service_token`.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    comparisons: list[tuple[str, str]] = []

    def compare_digest(provided: str, expected: str) -> bool:
        """Ejecuta la operación `compare_digest`.

        Args:
            provided (str): Valor de `provided` utilizado por la operación.
            expected (str): Valor de `expected` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
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
async def test_manual_inspection_is_idempotent_encrypted_and_never_echoes_installer_url(
    internal_api: InternalApiFixture,
    monkeypatch,
) -> None:
    """Comprueba que la inspección manual es idempotente y protege la URL del instalador.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    app = await internal_api.add_unresolved_app()
    installer_url = "https://downloads.example.test/ManualSetup.exe?token=secret"
    source_page_url = "https://example.test/download"

    async def public_url(url: str) -> str:
        """Ejecuta la operación `public_url`.

        Args:
            url (str): URL del recurso que debe procesarse.

        Returns:
            str: Resultado producido por la operación.
        """
        return url

    monkeypatch.setattr(
        "app.scraper.manual_installer.validate_public_https_url",
        public_url,
    )
    headers = {INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN}
    path = f"/internal/v1/admin/apps/{app.id}/manual-installer-inspections"
    first = await internal_api.client.post(
        path,
        headers=headers,
        json={
            "installerUrl": installer_url,
            "sourcePageUrl": source_page_url,
        },
    )
    repeated = await internal_api.client.post(
        path,
        headers=headers,
        json={
            "installerUrl": installer_url,
            "sourcePageUrl": source_page_url,
        },
    )

    assert first.status_code == 202
    assert repeated.status_code == 202
    assert repeated.json()["id"] == first.json()["id"]
    assert installer_url not in first.text
    assert "secret" not in first.text

    inspection = await internal_api.session.scalar(select(ManualInstallerInspection))
    assert inspection is not None
    assert installer_url not in inspection.installer_url_encrypted
    assert UrlProtector(URL_SECRET).reveal(inspection.installer_url_encrypted) == installer_url
    work_item = await internal_api.session.scalar(select(ScraperWorkItem))
    assert work_item is not None
    assert work_item.payload_json == {"inspection_id": str(inspection.id)}
    assert installer_url not in str(work_item.payload_json)

    conflict = await internal_api.client.post(
        path,
        headers=headers,
        json={
            "installerUrl": "https://downloads.example.test/OtherSetup.exe",
            "sourcePageUrl": source_page_url,
        },
    )
    assert conflict.status_code == 409
    assert conflict.json()["detail"]["code"] == "inspection_already_active"


@pytest.mark.asyncio
async def test_manual_inspection_requires_fresh_analysis_after_the_app_changes(
    internal_api: InternalApiFixture,
    monkeypatch,
) -> None:
    """Comprueba el escenario `manual_inspection_requires_fresh_analysis_after_the_app_changes`.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    app = await internal_api.add_unresolved_app()
    installer_url = "https://downloads.example.test/ManualSetup.exe"
    source_page_url = "https://example.test/download"

    async def public_url(url: str) -> str:
        """Ejecuta la operación `public_url`.

        Args:
            url (str): URL del recurso que debe procesarse.

        Returns:
            str: Resultado producido por la operación.
        """
        return url

    monkeypatch.setattr(
        "app.scraper.manual_installer.validate_public_https_url",
        public_url,
    )
    headers = {INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN}
    path = f"/internal/v1/admin/apps/{app.id}/manual-installer-inspections"
    payload = {
        "installerUrl": installer_url,
        "sourcePageUrl": source_page_url,
    }
    first = await internal_api.client.post(path, headers=headers, json=payload)
    assert first.status_code == 202

    app.version += 1
    await internal_api.session.commit()
    second = await internal_api.client.post(path, headers=headers, json=payload)

    assert second.status_code == 202
    assert second.json()["id"] != first.json()["id"]
    stale = await internal_api.session.get(
        ManualInstallerInspection,
        UUID(first.json()["id"]),
    )
    assert stale is not None
    assert stale.status == "expired"
    assert stale.error_code == "app_changed_reinspect_required"


@pytest.mark.asyncio
async def test_manual_inspection_encrypts_optional_platform_urls_and_requires_one(
    internal_api: InternalApiFixture,
    monkeypatch,
) -> None:
    """Comprueba el escenario `manual_inspection_encrypts_optional_platform_urls_and_requires_one`.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    app = await internal_api.add_unresolved_app()
    windows_url = "https://downloads.example.test/ManualSetup.exe"
    linux_url = "https://downloads.example.test/manual-setup.AppImage"
    source_page_url = "https://example.test/download"

    async def public_url(url: str) -> str:
        """Ejecuta la operación `public_url`.

        Args:
            url (str): URL del recurso que debe procesarse.

        Returns:
            str: Resultado producido por la operación.
        """
        return url

    monkeypatch.setattr(
        "app.scraper.manual_installer.validate_public_https_url",
        public_url,
    )
    headers = {INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN}
    path = f"/internal/v1/admin/apps/{app.id}/manual-installer-inspections"
    response = await internal_api.client.post(
        path,
        headers=headers,
        json={
            "installerUrls": {
                "windows": windows_url,
                "macos": None,
                "linux": linux_url,
            },
            "sourcePageUrl": source_page_url,
        },
    )

    assert response.status_code == 202
    assert windows_url not in response.text
    assert linux_url not in response.text
    inspection = await internal_api.session.get(
        ManualInstallerInspection,
        UUID(response.json()["id"]),
    )
    assert inspection is not None
    assert inspection.installer_url_encrypted is None
    protector = UrlProtector(URL_SECRET)
    assert protector.reveal(inspection.windows_installer_url_encrypted) == windows_url
    assert protector.reveal(inspection.linux_installer_url_encrypted) == linux_url
    assert inspection.macos_installer_url_encrypted is None

    rejected = await internal_api.client.post(
        path,
        headers=headers,
        json={
            "installerUrls": {"windows": None, "macos": None, "linux": None},
            "sourcePageUrl": source_page_url,
        },
    )
    assert rejected.status_code == 422


@pytest.mark.asyncio
async def test_manual_apply_revalidates_and_persists_multiple_encrypted_candidates(
    internal_api: InternalApiFixture,
    monkeypatch,
) -> None:
    """Comprueba el escenario `manual_apply_revalidates_and_persists_multiple_encrypted_candidates`.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    app = await internal_api.add_unresolved_app()
    windows_url = "https://downloads.example.test/ManualSetup-1.2.0.exe"
    linux_url = "https://downloads.example.test/manual-setup-1.2.0.AppImage"
    source_page_url = "https://example.test/download"

    async def public_url(url: str) -> str:
        """Ejecuta la operación `public_url`.

        Args:
            url (str): URL del recurso que debe procesarse.

        Returns:
            str: Resultado producido por la operación.
        """
        return url

    monkeypatch.setattr(
        "app.scraper.manual_installer.validate_public_https_url",
        public_url,
    )
    headers = {INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN}
    create_response = await internal_api.client.post(
        f"/internal/v1/admin/apps/{app.id}/manual-installer-inspections",
        headers=headers,
        json={
            "installerUrls": {
                "windows": windows_url,
                "macos": None,
                "linux": linux_url,
            },
            "sourcePageUrl": source_page_url,
        },
    )
    inspection_id = UUID(create_response.json()["id"])
    inspection = await internal_api.session.get(ManualInstallerInspection, inspection_id)
    assert inspection is not None
    inspection.status = "ready"
    inspection.phase = "ready"
    inspection.result_json = {
        "suggestions": {
            "longDescription": {
                "value": "Generated long description",
                "source": "generated_ai",
            }
        },
        "installer": {
            "finalDomain": "example.test",
            "filename": "ManualSetup-1.2.0.exe",
            "extension": ".exe",
            "contentType": "application/x-msdownload",
            "sizeBytes": 4096,
            "version": "1.2.0",
            "operatingSystem": "windows",
            "architecture": "x86_64",
            "platformRequired": False,
        },
        "installers": [
            {
                "finalDomain": "example.test",
                "filename": "ManualSetup-1.2.0.exe",
                "extension": ".exe",
                "contentType": "application/x-msdownload",
                "sizeBytes": 4096,
                "version": "1.2.0",
                "operatingSystem": "windows",
                "architecture": "x86_64",
                "platformRequired": False,
            },
            {
                "finalDomain": "example.test",
                "filename": "manual-setup-1.2.0.AppImage",
                "extension": ".appimage",
                "contentType": "application/octet-stream",
                "sizeBytes": 8192,
                "version": "1.2.0",
                "operatingSystem": "linux",
                "architecture": "x86_64",
                "platformRequired": False,
            },
        ],
        "ai": {
            "status": "ready",
            "provider": "groq",
            "model": "model-test",
        },
    }
    await internal_api.session.commit()

    validation_calls: list[tuple[str, str | None]] = []

    async def validate_installer(
        _inspector,
        installer_url,
        _source_page_url,
        expected_operating_system=None,
    ):
        """Valida la operación `installer`.

        Args:
            _inspector (Any): Valor de `_inspector` utilizado por la operación.
            installer_url (Any): Dirección de `installer` que debe procesarse.
            _source_page_url (Any): Dirección de `_source_page` que debe procesarse.
            expected_operating_system (Any): Valor esperado de `operating_system`.
        """
        validation_calls.append((installer_url, expected_operating_system))
        is_linux = expected_operating_system == "linux"
        filename = (
            "manual-setup-1.2.0.AppImage"
            if is_linux
            else "ManualSetup-1.2.0.exe"
        )
        extension = ".appimage" if is_linux else ".exe"
        content_type = (
            "application/octet-stream"
            if is_linux
            else "application/x-msdownload"
        )
        size_bytes = 8192 if is_linux else 4096
        return ValidatedManualInstaller(
            result=ValidationResult(
                ok=True,
                url=installer_url,
                final_url=installer_url,
                final_domain="example.test",
                filename=filename,
                extension=extension,
                content_type=content_type,
                size_bytes=size_bytes,
                confidence=ValidationConfidence.VALIDATED,
            ),
            final_url=installer_url,
            version="1.2.0",
            operating_system=expected_operating_system,
            architecture="x86_64",
        )

    monkeypatch.setattr(
        "app.scraper.manual_installer.ManualInstallerInspector.validate_installer",
        validate_installer,
    )
    apply_payload = {
        "expectedAppVersion": inspection.captured_app_version,
        "name": "Manual App",
        "publisher": "Manual Vendor",
        "officialUrl": "https://example.test",
        "latestVersion": "1.2.0",
        "description": "Existing description",
        "longDescription": "Generated long description",
        "iconUrl": None,
        "operatingSystem": None,
    }
    apply_path = (
        f"/internal/v1/admin/apps/{app.id}/manual-installer-inspections/"
        f"{inspection_id}/apply"
    )
    response = await internal_api.client.post(
        apply_path,
        headers=headers,
        json=apply_payload,
    )

    assert response.status_code == 200
    assert response.json()["catalogStatus"] == "available"
    assert response.json()["warnings"] == []
    assert windows_url not in response.text
    assert linux_url not in response.text
    source_refs = [UUID(value) for value in response.json()["sourceRefs"]]
    assert len(source_refs) == 2
    assert response.json()["sourceRef"] == str(source_refs[0])
    resolved_sources = [
        await internal_api.session.get(ResolvedSource, source_ref)
        for source_ref in source_refs
    ]
    assert all(resolved is not None for resolved in resolved_sources)
    revealed_urls = {
        UrlProtector(URL_SECRET).reveal(resolved.resolved_url_encrypted)
        for resolved in resolved_sources
        if resolved is not None
    }
    assert revealed_urls == {windows_url, linux_url}
    download_sources = [
        await internal_api.session.get(DownloadSource, resolved.download_source_id)
        for resolved in resolved_sources
        if resolved is not None
    ]
    assert {source.operating_system for source in download_sources if source} == {
        "windows",
        "linux",
    }
    assert all(source.initial_url == source_page_url for source in download_sources if source)
    assert all(
        source.resolver_config == {"source": "admin_manual"}
        for source in download_sources
        if source
    )
    await internal_api.session.refresh(app)
    assert app.long_description_source == "groq"
    assert app.long_description_model == "model-test"
    await internal_api.session.refresh(inspection)
    assert inspection.applied_app_version == app.version
    assert validation_calls == [
        (windows_url, "windows"),
        (linux_url, "linux"),
    ]

    # SQLite no instala los disparadores de proyección de MySQL que ejercita la prueba
    # de integración; replica su estado final antes de comprobar la idempotencia.
    app.catalog_available_source_count = 2
    await internal_api.session.commit()
    await internal_api.session.refresh(app)
    assert app.catalog_status == "available"
    repeated = await internal_api.client.post(
        apply_path,
        headers=headers,
        json=apply_payload,
    )
    assert repeated.status_code == 200
    assert repeated.json()["sourceRefs"] == [str(value) for value in source_refs]
    assert repeated.json()["appVersion"] == app.version
    assert len(validation_calls) == 2


@pytest.mark.asyncio
async def test_website_discovery_is_idempotent_encrypted_and_url_free(
    internal_api: InternalApiFixture,
    monkeypatch,
) -> None:
    """Comprueba el escenario `website_discovery_is_idempotent_encrypted_and_url_free`.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    official_url = "https://example.test/product"
    windows_installer_url = "https://downloads.example.test/Product.exe"

    async def public_url(url: str) -> str:
        """Ejecuta la operación `public_url`.

        Args:
            url (str): URL del recurso que debe procesarse.

        Returns:
            str: Resultado producido por la operación.
        """
        return url

    monkeypatch.setattr(
        "app.scraper.website_discovery.validate_public_https_url",
        public_url,
    )
    headers = {INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN}
    path = "/internal/v1/admin/app-discoveries"
    first = await internal_api.client.post(
        path,
        headers=headers,
        json={
            "officialUrl": official_url,
            "installerUrls": {
                "windows": windows_installer_url,
                "macos": None,
                "linux": None,
            },
        },
    )
    repeated = await internal_api.client.post(
        path,
        headers=headers,
        json={
            "officialUrl": official_url,
            "installerUrls": {
                "windows": windows_installer_url,
                "macos": None,
                "linux": None,
            },
        },
    )

    assert first.status_code == 202
    assert repeated.status_code == 202
    assert repeated.json()["id"] == first.json()["id"]
    assert official_url not in first.text
    assert windows_installer_url not in first.text
    assert first.json()["providedInstallerPlatforms"] == ["windows"]

    discovery = await internal_api.session.scalar(select(WebsiteAppDiscovery))
    assert discovery is not None
    assert official_url not in discovery.official_url_encrypted
    assert UrlProtector(URL_SECRET).reveal(discovery.official_url_encrypted) == official_url
    assert windows_installer_url not in discovery.windows_installer_url_encrypted
    assert (
        UrlProtector(URL_SECRET).reveal(
            discovery.windows_installer_url_encrypted
        )
        == windows_installer_url
    )
    work_item = await internal_api.session.scalar(
        select(ScraperWorkItem).where(
            ScraperWorkItem.queue == "website_app_discovery"
        )
    )
    assert work_item is not None
    assert work_item.payload_json == {"discovery_id": str(discovery.id)}
    assert official_url not in str(work_item.payload_json)


@pytest.mark.asyncio
async def test_website_discovery_apply_creates_a_missing_app_when_no_installer_is_valid(
    internal_api: InternalApiFixture,
    monkeypatch,
) -> None:
    """Comprueba que el descubrimiento crea una aplicación ausente sin instalador válido.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    official_url = "https://example.test/product"

    async def public_url(url: str) -> str:
        """Ejecuta la operación `public_url`.

        Args:
            url (str): URL del recurso que debe procesarse.

        Returns:
            str: Resultado producido por la operación.
        """
        return url

    monkeypatch.setattr(
        "app.scraper.website_discovery.validate_public_https_url",
        public_url,
    )
    headers = {INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN}
    created = await internal_api.client.post(
        "/internal/v1/admin/app-discoveries",
        headers=headers,
        json={"officialUrl": official_url},
    )
    discovery = await internal_api.session.get(
        WebsiteAppDiscovery,
        UUID(created.json()["id"]),
    )
    assert discovery is not None
    discovery.status = "ready"
    discovery.phase = "ready"
    discovery.result_json = {
        "suggestions": {
            "name": {"value": "Example Desktop", "source": "json_ld"},
            "publisher": {"value": "Example Vendor", "source": "json_ld"},
            "officialUrl": {"value": official_url, "source": "source_page"},
            "latestVersion": {"value": "2.4.1", "source": "json_ld"},
            "description": {
                "value": "Cliente de escritorio para Example.",
                "source": "json_ld",
            },
            "longDescription": {
                "value": "DescripciÃ³n generada",
                "source": "generated_ai",
            },
            "iconUrl": {"value": None, "source": "unavailable"},
        },
        "ai": {
            "status": "ready",
            "provider": "groq",
            "model": "model-test",
        },
        "installerCount": 0,
    }
    await internal_api.session.commit()

    response = await internal_api.client.post(
        f"/internal/v1/admin/app-discoveries/{discovery.id}/apply",
        headers=headers,
        json={
            "name": "Example Desktop",
            "publisher": "Example Vendor",
            "officialUrl": official_url,
            "latestVersion": "2.4.1",
            "description": "Cliente de escritorio para Example.",
            "longDescription": "DescripciÃ³n generada",
            "iconUrl": None,
        },
    )

    assert response.status_code == 200
    assert response.json()["catalogStatus"] == "missing"
    assert response.json()["installerCount"] == 0
    assert official_url not in response.text
    app = await internal_api.session.get(
        SoftwareApp,
        UUID(response.json()["appId"]),
    )
    assert app is not None
    assert app.name == "Example Desktop"
    assert app.official_url == official_url
    assert app.long_description_source == "groq"
    assert app.winstall_id.startswith("manual.")
    source_ids = list(
        await internal_api.session.scalars(
            select(DownloadSource.id).where(
                DownloadSource.software_app_id == app.id
            )
        )
    )
    assert source_ids == []


@pytest.mark.asyncio
async def test_website_discovery_apply_revalidates_and_encrypts_found_installers(
    internal_api: InternalApiFixture,
    monkeypatch,
) -> None:
    """Comprueba el escenario `website_discovery_apply_revalidates_and_encrypts_found_installers`.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    official_url = "https://example.test/product"
    installer_url = "https://downloads.example.test/Example-3.1.0.exe"
    validation_calls = 0

    async def public_url(url: str) -> str:
        """Ejecuta la operación `public_url`.

        Args:
            url (str): URL del recurso que debe procesarse.

        Returns:
            str: Resultado producido por la operación.
        """
        return url

    async def validate_installer(_validator, candidate, *, require_signature=False):
        """Valida la operación `installer`.

        Args:
            _validator (Any): Valor de `_validator` utilizado por la operación.
            candidate (Any): Valor de `candidate` utilizado por la operación.
            require_signature (bool): Valor de `require_signature` utilizado por la operación.
        """
        nonlocal validation_calls
        validation_calls += 1
        assert require_signature is True
        assert candidate.url == installer_url
        return ValidationResult(
            ok=True,
            url=candidate.url,
            final_url=candidate.url,
            final_domain="example.test",
            filename="Example-3.1.0.exe",
            extension=".exe",
            content_type="application/x-msdownload",
            size_bytes=8192,
            confidence=ValidationConfidence.VALIDATED,
        )

    monkeypatch.setattr(
        "app.scraper.website_discovery.validate_public_https_url",
        public_url,
    )
    monkeypatch.setattr(
        "app.scraper.website_discovery.DownloadValidator.validate",
        validate_installer,
    )
    headers = {INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN}
    created = await internal_api.client.post(
        "/internal/v1/admin/app-discoveries",
        headers=headers,
        json={"officialUrl": official_url},
    )
    discovery = await internal_api.session.get(
        WebsiteAppDiscovery,
        UUID(created.json()["id"]),
    )
    assert discovery is not None
    discovery.status = "ready"
    discovery.phase = "ready"
    discovery.result_json = {
        "suggestions": {
            "name": {"value": "Example Desktop", "source": "json_ld"},
            "publisher": {"value": "Example Vendor", "source": "json_ld"},
            "officialUrl": {"value": official_url, "source": "source_page"},
            "latestVersion": {"value": "3.1.0", "source": "filename"},
            "description": {"value": None, "source": "unavailable"},
            "longDescription": {"value": None, "source": "unavailable"},
            "iconUrl": {"value": None, "source": "unavailable"},
        },
        "ai": {"status": "unavailable", "provider": None, "model": None},
        "installerCount": 1,
    }
    internal_api.session.add(
        WebsiteAppDiscoveryInstaller(
            discovery_id=discovery.id,
            installer_url_encrypted=UrlProtector(URL_SECRET).protect(installer_url),
            final_domain="example.test",
            filename="Example-3.1.0.exe",
            extension=".exe",
            content_type="application/x-msdownload",
            size_bytes=8192,
            version="3.1.0",
            operating_system="windows",
            architecture="x86_64",
            score=150,
        )
    )
    await internal_api.session.commit()
    internal_api.session.expire(discovery, ["installers"])

    response = await internal_api.client.post(
        f"/internal/v1/admin/app-discoveries/{discovery.id}/apply",
        headers=headers,
        json={
            "name": "Example Desktop",
            "publisher": "Example Vendor",
            "officialUrl": official_url,
            "latestVersion": "3.1.0",
            "description": None,
            "longDescription": None,
            "iconUrl": None,
        },
    )

    assert response.status_code == 200
    assert response.json()["installerCount"] == 1
    assert installer_url not in response.text
    app_id = UUID(response.json()["appId"])
    source = await internal_api.session.scalar(
        select(DownloadSource).where(DownloadSource.software_app_id == app_id)
    )
    assert source is not None
    assert source.initial_url == official_url
    resolved = await internal_api.session.scalar(
        select(ResolvedSource).where(
            ResolvedSource.download_source_id == source.id
        )
    )
    assert resolved is not None
    assert installer_url not in resolved.resolved_url_encrypted
    assert UrlProtector(URL_SECRET).reveal(resolved.resolved_url_encrypted) == installer_url
    assert resolved.validation_status == ValidationStatus.VALID.value
    assert validation_calls == 1

    repeated = await internal_api.client.post(
        f"/internal/v1/admin/app-discoveries/{discovery.id}/apply",
        headers=headers,
        json={
            "name": "Example Desktop",
            "publisher": "Example Vendor",
            "officialUrl": official_url,
            "latestVersion": "3.1.0",
            "description": None,
            "longDescription": None,
            "iconUrl": None,
        },
    )
    assert repeated.status_code == 200
    assert repeated.json()["appId"] == str(app_id)
    assert repeated.json()["installerCount"] == 1
    assert validation_calls == 1


@pytest.mark.asyncio
async def test_internal_resolution_returns_verified_source_without_logging_secrets(
    internal_api: InternalApiFixture,
    caplog,
) -> None:
    """Comprueba el escenario `internal_resolution_returns_verified_source_without_logging_secrets`.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
        caplog (Any): Capturador de registros proporcionado por pytest.
    """
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
    """Comprueba el escenario `internal_resolution_rejects_non_verified_sources`.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
    """
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
    """Comprueba que la resolución revalida un candidato caducado antes de mostrar su URL.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    app, resolved, download_url = await internal_api.add_source(
        confidence="validated",
        expires_in_hours=-1,
    )

    async def validate(_validator, candidate):
        """Ejecuta la operación `validate`.

        Args:
            _validator (Any): Valor de `_validator` utilizado por la operación.
            candidate (Any): Valor de `candidate` utilizado por la operación.
        """
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
    """Comprueba el escenario `internal_resolution_keeps_failed_revalidation_secret`.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    _app, resolved, download_url = await internal_api.add_source(
        confidence="validated",
        expires_in_hours=-1,
    )

    async def validate(_validator, candidate):
        """Ejecuta la operación `validate`.

        Args:
            _validator (Any): Valor de `_validator` utilizado por la operación.
            candidate (Any): Valor de `candidate` utilizado por la operación.
        """
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
async def test_internal_resolution_does_not_invalidate_on_transient_revalidation_failure(
    internal_api: InternalApiFixture,
    monkeypatch,
) -> None:
    """Comprueba que un fallo transitorio de revalidación no invalida la resolución.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    _app, resolved, _download_url = await internal_api.add_source(
        confidence="validated",
        expires_in_hours=-1,
    )
    previous_checked_at = resolved.checked_at
    previous_metadata = dict(resolved.metadata_json or {})

    async def validate(_validator, candidate):
        """Ejecuta la operación `validate`.

        Args:
            _validator (Any): Valor de `_validator` utilizado por la operación.
            candidate (Any): Valor de `candidate` utilizado por la operación.
        """
        return ValidationResult(ok=False, url=candidate.url, reason="http_503")

    monkeypatch.setattr("app.api.internal_routes.DownloadValidator.validate", validate)

    response = await internal_api.client.get(
        f"/internal/v1/sources/{resolved.id}/resolution",
        headers={INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN},
    )

    assert response.status_code == 503
    assert response.json() == {"detail": {"code": "source_revalidation_transient"}}
    assert resolved.validation_status == ValidationStatus.VALID.value
    assert resolved.checked_at == previous_checked_at
    assert resolved.metadata_json == previous_metadata


@pytest.mark.asyncio
async def test_internal_resolution_recovers_itch_from_official_windows_endpoint(
    internal_api: InternalApiFixture,
    monkeypatch,
) -> None:
    """Comprueba el escenario `internal_resolution_recovers_itch_from_official_windows_endpoint`.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    app, resolved, stale_url = await internal_api.add_source(
        confidence="validated",
        expires_in_hours=-1,
    )
    app.winstall_id = "ItchIo.Itch"
    app.latest_version = "26.5.0"
    official_endpoint = "https://itch.io/app/download?platform=windows"
    current_url = "https://cdn.example.test/itch-setup.exe"
    validation_urls: list[str] = []
    await internal_api.session.commit()

    async def validate(_validator, candidate):
        """Ejecuta la operación `validate`.

        Args:
            _validator (Any): Valor de `_validator` utilizado por la operación.
            candidate (Any): Valor de `candidate` utilizado por la operación.
        """
        validation_urls.append(candidate.url)
        if candidate.url == stale_url:
            return ValidationResult(
                ok=True,
                url=candidate.url,
                final_url=candidate.url,
                confidence=ValidationConfidence.ATTESTED,
            )
        assert candidate.url == official_endpoint
        return ValidationResult(
            ok=True,
            url=candidate.url,
            final_url=current_url,
            final_domain="example.test",
            filename="itch-setup.exe",
            extension=".exe",
            content_type="application/octet-stream",
            size_bytes=18_678_744,
            confidence=ValidationConfidence.VALIDATED,
        )

    monkeypatch.setattr("app.api.internal_routes.DownloadValidator.validate", validate)

    response = await internal_api.client.get(
        f"/internal/v1/sources/{resolved.id}/resolution",
        headers={INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN},
    )

    assert response.status_code == 200
    assert response.json()["url"] == current_url
    assert response.json()["expectedFilename"] == "itch-setup.exe"
    assert response.json()["expectedSizeBytes"] == 18_678_744
    assert validation_urls == [stale_url, official_endpoint]
    assert resolved.metadata_json["candidate_source"] == "official_known_endpoint"
    assert resolved.is_latest is True
    assert stale_url not in response.text


@pytest.mark.asyncio
async def test_internal_resolution_rechecks_candidate_after_acquiring_lock(
    internal_api: InternalApiFixture,
    monkeypatch,
) -> None:
    """Comprueba el escenario `internal_resolution_rechecks_candidate_after_acquiring_lock`.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
        monkeypatch (Any): Utilidad de pytest para sustituir dependencias durante la prueba.
    """
    _app, resolved, download_url = await internal_api.add_source(
        confidence="validated",
        expires_in_hours=-1,
    )

    async def lock_after_other_request_renewed(_catalog, _source_ref):
        """Ejecuta la operación `lock_after_other_request_renewed`.

        Args:
            _catalog (Any): Valor de `_catalog` utilizado por la operación.
            _source_ref (Any): Valor de `_source_ref` utilizado por la operación.
        """
        resolved.expires_at = utc_after(hours=1)
        return resolved

    async def validation_must_not_run(_validator, _candidate):
        """Ejecuta la operación `validation_must_not_run`.

        Args:
            _validator (Any): Valor de `_validator` utilizado por la operación.
            _candidate (Any): Valor de `_candidate` utilizado por la operación.

        Throws:
            AssertionError: Si no puede completarse la operación bajo las condiciones requeridas.
        """
        raise AssertionError("the refreshed candidate must be reused")

    monkeypatch.setattr(
        "app.api.internal_routes.CatalogRepository.get_resolved_source_by_ref_for_update",
        lock_after_other_request_renewed,
    )
    monkeypatch.setattr(
        "app.api.internal_routes.DownloadValidator.validate",
        validation_must_not_run,
    )

    response = await internal_api.client.get(
        f"/internal/v1/sources/{resolved.id}/resolution",
        headers={INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN},
    )

    assert response.status_code == 200
    assert response.json()["url"] == download_url


@pytest.mark.asyncio
async def test_internal_resolution_never_reveals_http_url(
    internal_api: InternalApiFixture,
) -> None:
    """Comprueba el escenario `internal_resolution_never_reveals_http_url`.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
    """
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
    assert resolved.validation_status == ValidationStatus.EXPIRED.value
    assert resolved.metadata_json["last_revalidation_error"] == "source_not_https"


@pytest.mark.asyncio
async def test_internal_resolution_invalidates_unreadable_encrypted_url(
    internal_api: InternalApiFixture,
) -> None:
    """Comprueba el escenario `internal_resolution_invalidates_unreadable_encrypted_url`.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
    """
    _app, resolved, _download_url = await internal_api.add_source(
        confidence="validated",
    )
    resolved.resolved_url_encrypted = "not-a-valid-encrypted-url"
    await internal_api.session.commit()

    response = await internal_api.client.get(
        f"/internal/v1/sources/{resolved.id}/resolution",
        headers={INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN},
    )

    assert response.status_code == 409
    assert response.json()["url"] is None
    assert resolved.validation_status == ValidationStatus.EXPIRED.value
    assert resolved.metadata_json["last_revalidation_error"] == "source_url_unreadable"


@pytest.mark.asyncio
async def test_internal_resolution_rejects_http_attested_candidate(
    internal_api: InternalApiFixture,
) -> None:
    """Comprueba el escenario `internal_resolution_rejects_http_attested_candidate`.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
    """
    _app, resolved, download_url = await internal_api.add_source(
        confidence="validated",
    )
    resolved.metadata_json = {
        **(resolved.metadata_json or {}),
        "transport_security": "http_winstall_verified",
    }
    await internal_api.session.commit()

    response = await internal_api.client.get(
        f"/internal/v1/sources/{resolved.id}/resolution",
        headers={INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN},
    )

    assert response.status_code == 409
    assert response.json()["trustStatus"] == "ATTESTED"
    assert response.json()["url"] is None
    assert download_url not in response.text


@pytest.mark.asyncio
async def test_internal_resolution_rechecks_parent_source_state(
    internal_api: InternalApiFixture,
) -> None:
    """Comprueba el escenario `internal_resolution_rechecks_parent_source_state`.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
    """
    app, resolved, download_url = await internal_api.add_source(
        confidence="validated",
    )
    source = app.sources[0]
    source.resolution_status = ResolutionStatus.BROKEN.value
    source.validation_status = ValidationStatus.INVALID.value
    await internal_api.session.commit()

    response = await internal_api.client.get(
        f"/internal/v1/sources/{resolved.id}/resolution",
        headers={INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN},
    )

    assert response.status_code == 409
    assert response.json()["trustStatus"] == "UNRESOLVED"
    assert response.json()["url"] is None
    assert download_url not in response.text


@pytest.mark.asyncio
async def test_internal_resolution_returns_not_found_for_unknown_reference(
    internal_api: InternalApiFixture,
) -> None:
    """Comprueba el escenario `internal_resolution_returns_not_found_for_unknown_reference`.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
    """
    unknown_ref = UUID(int=0)

    response = await internal_api.client.get(
        f"/internal/v1/sources/{unknown_ref}/resolution",
        headers={INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN},
    )

    assert response.status_code == 404


@pytest.mark.asyncio
async def test_semantic_feed_is_authenticated_canonical_and_paginated(
    internal_api: InternalApiFixture,
) -> None:
    """Comprueba el escenario `semantic_feed_is_authenticated_canonical_and_paginated`.

    Args:
        internal_api (InternalApiFixture): Valor de `internal_api` utilizado por la operación.
    """
    first = SoftwareApp(
        id=UUID(int=1),
        winstall_id="Vendor.Editor",
        slug="vendor-editor",
        name="Editor",
        normalized_name="editor",
        publisher="Vendor",
        description="Edita texto",
        long_description="Editor para desarrollo",
        official_url="https://downloads.vendor.example/full/path?token=secret",
        latest_version="2.0",
        operating_systems=["windows"],
        app_status="active",
    )
    first.tags = [
        SoftwareAppTag(
            id=uuid4(),
            software_app_id=first.id,
            tag="Desarrollo",
            normalized_tag="desarrollo",
        )
    ]
    first.sources = [
        DownloadSource(
            id=uuid4(),
            software_app_id=first.id,
            operating_system="windows",
            architecture="x86_64",
        )
    ]
    second = SoftwareApp(
        id=UUID(int=2),
        winstall_id="Vendor.Other",
        slug="vendor-other",
        name="Other",
        normalized_name="other",
        app_status="active",
    )
    internal_api.session.add_all([first, second])
    await internal_api.session.commit()

    response = await internal_api.client.get(
        "/internal/v1/semantic/documents",
        params={"limit": 1},
        headers={INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN},
    )

    assert response.status_code == 200
    body = response.json()
    assert len(body["documents"]) == 1
    assert body["nextAfterAppId"] == body["documents"][0]["appId"]
    document = body["documents"][0]
    assert len(document["contentHash"]) == 64
    assert "Package ID: Vendor.Editor" in document["content"]
    assert "Arquitecturas: x86_64" in document["content"]
    assert "/full/path" not in document["content"]
    assert "token=secret" not in response.text

    initial_hash = document["contentHash"]
    first.description = "Edita texto y código con búsqueda avanzada"
    await internal_api.session.commit()
    changed_response = await internal_api.client.get(
        "/internal/v1/semantic/documents",
        params={"limit": 1},
        headers={INTERNAL_SERVICE_TOKEN_HEADER: INTERNAL_TOKEN},
    )

    assert changed_response.status_code == 200
    assert changed_response.json()["documents"][0]["contentHash"] != initial_hash
