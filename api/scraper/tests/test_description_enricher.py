from uuid import uuid4

import httpx
import pytest
import respx

from app.core.config import Settings
from app.db.models import SoftwareApp, SoftwareAppTag
from app.scraper.description_enricher import (
    AppDescriptionEnricher,
    AppDescriptionLLMClient,
    GeneratedDescription,
    LLMGenerationError,
    description_input_hash,
)


def make_app(**overrides) -> SoftwareApp:
    app = SoftwareApp(
        id=uuid4(),
        winstall_id=overrides.get("winstall_id", "Vendor.App"),
        slug="vendor-app",
        name=overrides.get("name", "Vendor App"),
        normalized_name="vendor app",
        description=overrides.get("description", "Short description."),
        publisher=overrides.get("publisher", "Vendor"),
        official_url=overrides.get("official_url", "https://example.com/app"),
        latest_version="1.0.0",
        app_status="active",
        long_description_status="pending",
    )
    app.tags = [
        SoftwareAppTag(
            id=uuid4(),
            software_app_id=app.id,
            tag=tag,
            normalized_tag=tag,
            source="winstall",
        )
        for tag in overrides.get("tags", ["utility"])
    ]
    app.sources = []
    return app


def test_description_input_hash_changes_when_core_evidence_changes() -> None:
    baseline = description_input_hash(make_app())

    assert description_input_hash(make_app(tags=["productivity"])) != baseline
    assert description_input_hash(make_app(description="Different.")) != baseline
    assert description_input_hash(make_app(publisher="Other Vendor")) != baseline
    assert description_input_hash(make_app(official_url="https://example.org/app")) != baseline


@pytest.mark.asyncio
@respx.mock
async def test_llm_client_falls_back_to_deepseek_and_retries_groq() -> None:
    settings = Settings(
        llm_groq_api_key="groq-key",
        llm_deepseek_api_key="deepseek-key",
        llm_request_timeout_seconds=5,
    )
    respx.post("https://api.groq.com/openai/v1/chat/completions").mock(
        side_effect=[
            httpx.Response(429),
            httpx.Response(
                200,
                json={
                    "choices": [
                        {
                            "message": {
                                "content": (
                                    '{"long_description":"Descripcion larga valida para la app.",'
                                    '"language":"es"}'
                                )
                            }
                        }
                    ]
                },
            ),
        ]
    )
    respx.post("https://api.deepseek.com/chat/completions").mock(
        return_value=httpx.Response(500)
    )

    result = await AppDescriptionLLMClient(settings).generate({"name": "Vendor App"})

    assert result.provider == "groq"
    assert result.description == "Descripcion larga valida para la app."


@pytest.mark.asyncio
async def test_enricher_marks_invalid_llm_response_as_failed() -> None:
    class BadLLM:
        def has_provider(self) -> bool:
            return True

        async def generate(self, _evidence):
            raise LLMGenerationError("invalid_json", "groq", "test-model")

    class FakeCatalog:
        def __init__(self) -> None:
            self.app = make_app(official_url=None)
            self.failed = []

        async def apps_for_description_enrichment(self):
            return [self.app]

        async def save_long_description(self, **_kwargs):
            raise AssertionError("invalid responses must not be saved")

        async def mark_long_description_failed(self, **kwargs):
            self.failed.append(kwargs)

    class FakeLogs:
        def __init__(self) -> None:
            self.entries = []

        async def add(self, **kwargs):
            self.entries.append(kwargs)

    catalog = FakeCatalog()
    logs = FakeLogs()
    enriched = await AppDescriptionEnricher(
        Settings(llm_max_apps_per_run=1),
        catalog,
        logs,
        llm=BadLLM(),
    ).enrich_pending()

    assert enriched == 0
    assert catalog.failed[0]["error"] == "invalid_json"
    assert logs.entries[0]["status"] == "failed"


@pytest.mark.asyncio
async def test_enricher_treats_zero_max_apps_as_unlimited() -> None:
    class GoodLLM:
        def has_provider(self) -> bool:
            return True

        async def generate(self, _evidence):
            return GeneratedDescription(
                description="Descripcion larga generada por IA para pruebas.",
                language="es",
                provider="groq",
                model="test-model",
            )

    class FakeCatalog:
        def __init__(self) -> None:
            self.apps = [
                make_app(winstall_id=f"Vendor.App{i}", name=f"Vendor App {i}", official_url=None)
                for i in range(3)
            ]
            self.saved = []

        async def apps_for_description_enrichment(self):
            return self.apps

        async def save_long_description(self, **kwargs):
            self.saved.append(kwargs)

        async def mark_long_description_failed(self, **_kwargs):
            raise AssertionError("valid responses must not be marked failed")

    class FakeLogs:
        async def add(self, **_kwargs):
            return None

    catalog = FakeCatalog()
    enriched = await AppDescriptionEnricher(
        Settings(llm_max_apps_per_run=0),
        catalog,
        FakeLogs(),
        llm=GoodLLM(),
    ).enrich_pending()

    assert enriched == 3
    assert len(catalog.saved) == 3
