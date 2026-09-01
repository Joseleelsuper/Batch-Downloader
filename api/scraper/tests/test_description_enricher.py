"""Contiene las pruebas de `test_description_enricher`.
"""
import json
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
    parse_description_payload,
)
from app.scraper.llm import (
    InMemoryModelCooldownStore,
    cooldown_from_headers,
    parse_duration_seconds,
)


class NoopRateLimiter:
    """Agrupa los escenarios de prueba de `NoopRateLimiter`.
    """
    async def wait_for_slot(self):
        """Ejecuta `wait_for_slot` dentro de `NoopRateLimiter`.
        """
        return None


class FakeClock:
    """Agrupa los escenarios de prueba de `FakeClock`.
    """
    def __init__(self) -> None:
        """Inicializa una instancia de `FakeClock`.
        """
        self.now = 0.0
        """Estado de instancia asociado a `now`.
        """

    def __call__(self) -> float:
        """Ejecuta la instancia como una operación invocable.

        Returns:
            float: Resultado producido por la operación.
        """
        return self.now

    def advance(self, seconds: float) -> None:
        """Ejecuta `advance` dentro de `FakeClock`.

        Args:
            seconds (float): Valor de `seconds` utilizado por la operación.
        """
        self.now += seconds


def make_app(**overrides) -> SoftwareApp:
    """Construye la operación `app`.

    Args:
        **overrides (Any): Valor de `overrides` utilizado por la operación.

    Returns:
        SoftwareApp: Resultado producido por la operación.
    """
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
    """Comprueba el escenario `description_input_hash_changes_when_core_evidence_changes`.
    """
    baseline = description_input_hash(make_app())

    assert description_input_hash(make_app(tags=["productivity"])) != baseline
    assert description_input_hash(make_app(description="Different.")) != baseline
    assert description_input_hash(make_app(publisher="Other Vendor")) != baseline
    assert description_input_hash(make_app(official_url="https://example.org/app")) != baseline


@pytest.mark.asyncio
@respx.mock
async def test_llm_client_rotates_through_approved_groq_models_on_rate_limit() -> None:
    """Comprueba el escenario `llm_client_rotates_through_approved_groq_models_on_rate_limit`.
    """
    settings = Settings(
        llm_groq_api_key="groq-key",
        llm_deepseek_api_key="deepseek-key",
        llm_request_timeout_seconds=5,
    )
    groq_route = respx.post("https://api.groq.com/openai/v1/chat/completions").mock(
        side_effect=[
            httpx.Response(429, headers={"x-ratelimit-reset-requests": "2h"}),
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
    deepseek_route = respx.post("https://api.deepseek.com/chat/completions").mock(
        return_value=httpx.Response(500)
    )

    result = await AppDescriptionLLMClient(settings, rate_limiter=NoopRateLimiter()).generate(
        {"name": "Vendor App"}
    )

    assert result.provider == "groq"
    assert result.model == "qwen/qwen3-32b"
    assert result.description == "Descripcion larga valida para la app."
    assert [json.loads(call.request.content)["model"] for call in groq_route.calls] == [
        "llama-3.1-8b-instant",
        "qwen/qwen3-32b",
    ]
    assert not deepseek_route.called


@pytest.mark.asyncio
@respx.mock
async def test_llm_client_uses_deepseek_only_after_groq_models_are_unavailable() -> None:
    """Comprueba el escenario `llm_client_uses_deepseek_only_after_groq_models_are_unavailable`.
    """
    settings = Settings(
        llm_groq_api_key="groq-key",
        llm_deepseek_api_key="deepseek-key",
        llm_request_timeout_seconds=5,
    )
    groq_route = respx.post("https://api.groq.com/openai/v1/chat/completions").mock(
        return_value=httpx.Response(503)
    )
    deepseek_route = respx.post("https://api.deepseek.com/chat/completions").mock(
        return_value=httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": (
                                '{"long_description":"Descripcion final de DeepSeek.",'
                                '"language":"es"}'
                            )
                        }
                    }
                ]
            },
        )
    )

    result = await AppDescriptionLLMClient(
        settings,
        rate_limiter=NoopRateLimiter(),
    ).generate({"name": "Vendor App"})

    assert result.provider == "deepseek"
    assert [json.loads(call.request.content)["model"] for call in groq_route.calls] == [
        "llama-3.1-8b-instant",
        "qwen/qwen3-32b",
        "qwen/qwen3.6-27b",
        "meta-llama/llama-4-scout-17b-16e-instruct",
    ]
    assert json.loads(deepseek_route.calls[0].request.content)["thinking"] == {
        "type": "disabled"
    }
    assert all("thinking" not in json.loads(call.request.content) for call in groq_route.calls)


@pytest.mark.asyncio
@respx.mock
@pytest.mark.parametrize("status_code", [400, 404])
async def test_unavailable_groq_model_cools_down_and_tries_next_model(
    status_code: int,
) -> None:
    """Un modelo incompatible o ausente no bloquea los fallbacks de Groq."""
    settings = Settings(
        llm_groq_api_key="groq-key",
        llm_deepseek_api_key="deepseek-key",
        llm_request_timeout_seconds=5,
        llm_groq_fallback_models=("qwen/qwen3-32b",),
    )
    groq_route = respx.post("https://api.groq.com/openai/v1/chat/completions").mock(
        side_effect=[
            httpx.Response(status_code),
            httpx.Response(
                200,
                json={
                    "choices": [
                        {
                            "message": {
                                "content": (
                                    '{"long_description":"Descripcion alternativa valida.",'
                                    '"language":"es"}'
                                )
                            }
                        }
                    ]
                },
            ),
        ]
    )
    deepseek_route = respx.post("https://api.deepseek.com/chat/completions").mock(
        return_value=httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": (
                                '{"long_description":"Descripcion alternativa valida.",'
                                '"language":"es"}'
                            )
                        }
                    }
                ]
            },
        )
    )

    result = await AppDescriptionLLMClient(
        settings,
        rate_limiter=NoopRateLimiter(),
    ).generate({"name": "Vendor App"})

    assert result.provider == "groq"
    assert result.model == "qwen/qwen3-32b"
    assert [json.loads(call.request.content)["model"] for call in groq_route.calls] == [
        "llama-3.1-8b-instant",
        "qwen/qwen3-32b",
    ]
    assert not deepseek_route.called


def test_parse_description_payload_accepts_json_wrapped_in_provider_text() -> None:
    """Tolera razonamiento o texto de cortesía sin relajar la forma del resultado."""
    description, language = parse_description_payload(
        '<think>Comprobando evidencia.</think>\n'
        '{"long_description":"Descripción válida del proveedor.","language":"es"}\n'
        "Fin."
    )

    assert description == "Descripción válida del proveedor."
    assert language == "es"


@pytest.mark.asyncio
@respx.mock
async def test_rate_limited_model_is_not_retried_until_its_cooldown_expires() -> None:
    """Comprueba el escenario `rate_limited_model_is_not_retried_until_its_cooldown_expires`.
    """
    clock = FakeClock()
    cooldowns = InMemoryModelCooldownStore(monotonic=clock)
    settings = Settings(
        llm_groq_api_key="groq-key",
        llm_groq_fallback_models=("qwen/qwen3-32b",),
        llm_request_timeout_seconds=5,
    )
    valid_response = httpx.Response(
        200,
        json={
            "choices": [
                {
                    "message": {
                        "content": (
                            '{"long_description":"Descripcion generada correctamente.",'
                            '"language":"es"}'
                        )
                    }
                }
            ]
        },
    )
    groq_route = respx.post("https://api.groq.com/openai/v1/chat/completions").mock(
        side_effect=[
            httpx.Response(429, headers={"retry-after": "60"}),
            valid_response,
            valid_response,
            valid_response,
        ]
    )
    client = AppDescriptionLLMClient(
        settings,
        rate_limiter=NoopRateLimiter(),
        cooldowns=cooldowns,
    )

    first = await client.generate({"name": "First"})
    second = await client.generate({"name": "Second"})
    clock.advance(61)
    third = await client.generate({"name": "Third"})

    assert first.model == "qwen/qwen3-32b"
    assert second.model == "qwen/qwen3-32b"
    assert third.model == "llama-3.1-8b-instant"
    assert [json.loads(call.request.content)["model"] for call in groq_route.calls] == [
        "llama-3.1-8b-instant",
        "qwen/qwen3-32b",
        "qwen/qwen3-32b",
        "llama-3.1-8b-instant",
    ]


def test_groq_reset_header_duration_supports_compound_units() -> None:
    """Comprueba el escenario `groq_reset_header_duration_supports_compound_units`.
    """
    assert parse_duration_seconds("1h2m3.5s") == 3723.5
    assert cooldown_from_headers(
        {"Retry-After": "60", "X-RateLimit-Reset-Requests": "2m"},
        default_seconds=30,
    ) == 120


@pytest.mark.asyncio
async def test_enricher_marks_invalid_llm_response_as_failed() -> None:
    """Comprueba el escenario `enricher_marks_invalid_llm_response_as_failed`.
    """
    class BadLLM:
        """Agrupa los escenarios de prueba de `BadLLM`.
        """
        def has_provider(self) -> bool:
            """Indica si existe la operación `provider`.

            Returns:
                bool: Indica si se cumple la condición evaluada.
            """
            return True

        async def generate(self, _evidence):
            """Ejecuta `generate` dentro de `BadLLM`.

            Args:
                _evidence (Any): Valor de `_evidence` utilizado por la operación.

            Throws:
                LLMGenerationError: Si no puede completarse la operación bajo las condiciones
                    requeridas.
            """
            raise LLMGenerationError("invalid_json", "groq", "test-model")

    class FakeCatalog:
        """Agrupa los escenarios de prueba de `FakeCatalog`.
        """
        def __init__(self) -> None:
            """Inicializa una instancia de `FakeCatalog`.
            """
            self.app = make_app(official_url=None)
            """Estado de instancia asociado a `app`.
            """
            self.failed = []
            """Estado de instancia asociado a `failed`.
            """

        async def apps_for_description_enrichment(self, _software_app_ids=None):
            """Ejecuta `apps_for_description_enrichment` dentro de `FakeCatalog`.

            Args:
                _software_app_ids (Any): Colección de identificadores de `_software_app`.
            """
            return [self.app]

        async def save_long_description(self, **_kwargs):
            """Guarda la operación `long_description`.

            Args:
                **_kwargs (Any): Valor de `_kwargs` utilizado por la operación.

            Throws:
                AssertionError: Si no puede completarse la operación bajo las condiciones
                    requeridas.
            """
            raise AssertionError("invalid responses must not be saved")

        async def mark_long_description_failed(self, **kwargs):
            """Marca la operación `long_description_failed`.

            Args:
                **kwargs (Any): Valor de `kwargs` utilizado por la operación.
            """
            self.failed.append(kwargs)

    class FakeLogs:
        """Agrupa los escenarios de prueba de `FakeLogs`.
        """
        def __init__(self) -> None:
            """Inicializa una instancia de `FakeLogs`.
            """
            self.entries = []
            """Estado de instancia asociado a `entries`.
            """

        async def add(self, **kwargs):
            """Ejecuta `add` dentro de `FakeLogs`.

            Args:
                **kwargs (Any): Valor de `kwargs` utilizado por la operación.
            """
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
    """Comprueba el escenario `enricher_treats_zero_max_apps_as_unlimited`.
    """
    class GoodLLM:
        """Agrupa los escenarios de prueba de `GoodLLM`.
        """
        def has_provider(self) -> bool:
            """Indica si existe la operación `provider`.

            Returns:
                bool: Indica si se cumple la condición evaluada.
            """
            return True

        async def generate(self, _evidence):
            """Ejecuta `generate` dentro de `GoodLLM`.

            Args:
                _evidence (Any): Valor de `_evidence` utilizado por la operación.
            """
            return GeneratedDescription(
                description="Descripcion larga generada por IA para pruebas.",
                language="es",
                provider="groq",
                model="test-model",
            )

    class FakeCatalog:
        """Agrupa los escenarios de prueba de `FakeCatalog`.
        """
        def __init__(self) -> None:
            """Inicializa una instancia de `FakeCatalog`.
            """
            self.apps = [
                make_app(winstall_id=f"Vendor.App{i}", name=f"Vendor App {i}", official_url=None)
                for i in range(3)
            ]
            """Estado de instancia asociado a `apps`.
            """
            self.saved = []
            """Estado de instancia asociado a `saved`.
            """

        async def apps_for_description_enrichment(self, _software_app_ids=None):
            """Ejecuta `apps_for_description_enrichment` dentro de `FakeCatalog`.

            Args:
                _software_app_ids (Any): Colección de identificadores de `_software_app`.
            """
            return self.apps

        async def save_long_description(self, **kwargs):
            """Guarda la operación `long_description`.

            Args:
                **kwargs (Any): Valor de `kwargs` utilizado por la operación.
            """
            self.saved.append(kwargs)

        async def mark_long_description_failed(self, **_kwargs):
            """Marca la operación `long_description_failed`.

            Args:
                **_kwargs (Any): Valor de `_kwargs` utilizado por la operación.

            Throws:
                AssertionError: Si no puede completarse la operación bajo las condiciones
                    requeridas.
            """
            raise AssertionError("valid responses must not be marked failed")

    class FakeLogs:
        """Agrupa los escenarios de prueba de `FakeLogs`.
        """
        async def add(self, **_kwargs):
            """Ejecuta `add` dentro de `FakeLogs`.

            Args:
                **_kwargs (Any): Valor de `_kwargs` utilizado por la operación.
            """
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


@pytest.mark.asyncio
async def test_enrich_app_releases_database_before_calling_llm() -> None:
    """La petición externa no mantiene ocupada la conexión del catálogo."""
    events: list[str] = []

    class GoodLLM:
        def has_provider(self) -> bool:
            return True

        async def generate(self, _evidence):
            events.append("llm")
            return GeneratedDescription(
                description="Descripción generada sin retener la conexión SQL.",
                language="es",
                provider="groq",
                model="test-model",
            )

    class FakeCatalog:
        def __init__(self) -> None:
            self.app = make_app(official_url=None)

        async def apps_for_description_enrichment(self, _software_app_ids=None):
            return [self.app]

        async def save_long_description(self, **_kwargs):
            events.append("save")

        async def mark_long_description_failed(self, **_kwargs):
            raise AssertionError("valid responses must not be marked failed")

    class FakeLogs:
        async def add(self, **_kwargs):
            events.append("log")

    async def release_database_connection() -> None:
        events.append("release")

    result = await AppDescriptionEnricher(
        Settings(),
        FakeCatalog(),
        FakeLogs(),
        llm=GoodLLM(),
    ).enrich_app(
        uuid4(),
        release_database_connection=release_database_connection,
    )

    assert result.status == "completed"
    assert events == ["release", "llm", "save", "log"]
