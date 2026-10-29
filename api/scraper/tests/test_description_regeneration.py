"""Verifica conservación de autoría y ediciones durante una regeneración asíncrona."""
from uuid import uuid4

import pytest
import pytest_asyncio
import respx
from sqlalchemy import update
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.core.config import Settings
from app.core.time import utc_now
from app.core.url_protector import UrlProtector
from app.db.base import Base
from app.db.models import SoftwareApp
from app.repositories.catalog import CatalogRepository
from app.repositories.catalog_rules import description_text_hash
from app.repositories.logs import ResolverLogRepository
from app.scraper.description_enricher import AppDescriptionEnricher, GeneratedDescription
from app.scraper.llm import LLMGenerationError


@pytest_asyncio.fixture
async def description_database():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    factory = async_sessionmaker(engine, expire_on_commit=False)
    async with factory() as session:
        app = SoftwareApp(
            id=uuid4(), winstall_id="Vendor.App", slug="vendor-app", name="Vendor App",
            normalized_name="vendor app", official_url="https://vendor.example/app",
            long_description="Texto original", long_description_en="Original English",
            long_description_source="admin_manual", long_description_status="completed",
            long_description_generated_at=utc_now(), long_description_input_hash="old-input",
        )
        session.add(app)
        await session.commit()
        yield session, factory, app
    await engine.dispose()


@pytest.mark.asyncio
@respx.mock
@pytest.mark.parametrize("edit", ["before", "during", "none"])
@pytest.mark.parametrize("fails", [False, True])
async def test_regeneration_never_overwrites_later_edits_or_loses_text_on_failure(
    description_database, edit, fails,
) -> None:
    session, factory, app = description_database
    expected = description_text_hash(app)
    generated_at = app.long_description_generated_at
    calls = []

    async def edit_english():
        async with factory() as other:
            await other.execute(update(SoftwareApp).where(SoftwareApp.id == app.id).values(
                long_description_en="Edited English", long_description_source="admin_manual",
            ))
            await other.commit()

    class LLM:
        def has_provider(self):
            return True

        async def generate(self, evidence):
            calls.append(evidence)
            if edit == "during":
                await edit_english()
            if fails:
                raise LLMGenerationError("insufficient_evidence", "openai", "gpt-6-luna")
            return GeneratedDescription("Texto nuevo", "New English", "es", "openai", "gpt-6-luna")

    if edit == "before":
        await edit_english()
        await session.refresh(app)
    result = await AppDescriptionEnricher(
        Settings(_env_file=None), CatalogRepository(session, UrlProtector("test-secret")),
        ResolverLogRepository(session), llm=LLM(),
    ).enrich_app(app.id, force=True, expected_description_hash=expected,
                 release_database_connection=session.commit)
    await session.commit()
    await session.refresh(app)
    assert len(respx.calls) == 0  # Ni búsqueda ni consulta a la página oficial.
    assert len(calls) == (0 if edit == "before" else 1)
    if edit != "none":
        assert result.status == "skipped"
        assert result.error == "description_changed"
        assert app.long_description == "Texto original"
        assert app.long_description_en == "Edited English"
        assert app.long_description_source == "admin_manual"
    elif fails:
        assert result.status == "failed"
        assert app.long_description == "Texto original"
        assert app.long_description_en == "Original English"
        assert app.long_description_source == "admin_manual"
        assert app.long_description_model is None
        assert app.long_description_generated_at == generated_at
        assert app.long_description_input_hash == "old-input"
    else:
        assert result.status == "completed"
        assert app.long_description == "Texto nuevo"
        assert app.long_description_en == "New English"
        assert app.long_description_source == "openai"
        assert app.long_description_model == "gpt-6-luna"
