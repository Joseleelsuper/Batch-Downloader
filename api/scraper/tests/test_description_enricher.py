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
    build_embedding_text,
    description_input_hash,
    embedding_content_hash,
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


def test_english_description_is_indexed_for_semantic_search() -> None:
    app = make_app()
    baseline = embedding_content_hash(app)
    app.long_description_en = "English description of this application."

    assert "English description of this application." in build_embedding_text(app)
    assert embedding_content_hash(app) != baseline


def completion_response(
    content: str = '{"long_description_es":"Descripción válida.",'
                   '"long_description_en":"Valid description."}',
) -> httpx.Response:
    return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})


@pytest.mark.asyncio
@respx.mock
async def test_openai_responses_is_first_and_has_no_tools_or_web_requests() -> None:
    route = respx.post("https://api.openai.com/v1/responses").mock(return_value=httpx.Response(
        200, json={"status": "completed", "output": [
            {"type": "reasoning", "summary": []},
            {"type": "message", "content": [{"type": "output_text", "text":
                '{"long_description_es":"Descripción válida.",'
                '"long_description_en":"Valid description."}'}]},
        ]},
    ))
    result = await AppDescriptionLLMClient(Settings(
        _env_file=None, llm_openai_api_key="openai-key", llm_deepseek_api_key="deepseek-key",
        llm_groq_api_key="groq-key",
    ), rate_limiter=NoopRateLimiter()).generate({"name": "Vendor App", "publisher": "Vendor"})
    assert result.provider == "openai"
    assert result.model == "gpt-6-luna"
    payload = json.loads(route.calls[0].request.content)
    assert payload["model"] == "gpt-6-luna"
    assert payload["store"] is False
    assert "tools" not in payload
    assert "probable con cautela" not in payload["input"]
    assert "sin mínimo obligatorio" in payload["input"]
    assert "insufficient_evidence" in payload["input"]
    assert len(respx.calls) == 1


@pytest.mark.asyncio
@respx.mock
@pytest.mark.parametrize("failure", [401, 402, 403, 429, 503, "timeout", "network",
                                    "incomplete", "invalid_json", "missing_english",
                                    "insufficient_evidence", "empty"])
async def test_provider_failures_continue_in_order(failure) -> None:
    if isinstance(failure, int):
        response = httpx.Response(failure)
    else:
        content = {
            "invalid_json": "invalid", "missing_english": '{"long_description_es":"Solo ES"}',
            "insufficient_evidence": '{"error":"insufficient_evidence"}', "empty": "",
        }.get(failure, "{}")
        response = httpx.Response(200, json={
            "status": "incomplete" if failure == "incomplete" else "completed",
            "output": [{"type": "message", "content": [{"type": "output_text", "text": content}]}],
        })
    kwargs = {"return_value": response}
    if failure in {"timeout", "network"}:
        kwargs = {"side_effect": httpx.ReadTimeout("timeout") if failure == "timeout"
                  else httpx.ConnectError("network")}
    respx.post("https://api.openai.com/v1/responses").mock(**kwargs)
    respx.post("https://api.deepseek.com/chat/completions").mock(
        return_value=httpx.Response(503))
    groq = respx.post("https://api.groq.com/openai/v1/chat/completions").mock(
        side_effect=[httpx.Response(429), completion_response()])
    result = await AppDescriptionLLMClient(Settings(
        _env_file=None, llm_openai_api_key="openai-key", llm_deepseek_api_key="deepseek-key",
        llm_groq_api_key="groq-key",
    ), rate_limiter=NoopRateLimiter()).generate({"name": "Vendor App"})
    assert result.model == "qwen/qwen3.8-27b"
    assert [json.loads(call.request.content)["model"] for call in respx.calls] == [
        "gpt-6-luna", "deepseek-flash", "openai/gpt-oss-120b", "qwen/qwen3.8-27b",
    ]
    assert json.loads(groq.calls[0].request.content)["reasoning_effort"] == "low"
    assert json.loads(groq.calls[1].request.content)["reasoning_effort"] == "none"


@pytest.mark.asyncio
@respx.mock
async def test_deepseek_is_used_before_groq_without_web_search() -> None:
    route = respx.post("https://api.deepseek.com/chat/completions").mock(
        return_value=completion_response())
    result = await AppDescriptionLLMClient(Settings(
        _env_file=None, llm_groq_api_key="groq-key", llm_deepseek_api_key="deepseek-key",
    ), rate_limiter=NoopRateLimiter()).generate({"name": "Vendor App", "publisher": "Vendor"})
    assert result.provider == "deepseek"
    assert json.loads(route.calls[0].request.content)["thinking"] == {"type": "disabled"}
    assert len(respx.calls) == 1


@pytest.mark.asyncio
@respx.mock
async def test_groq_uses_all_three_models_and_rejects_truncated_answers() -> None:
    truncated = completion_response()
    body = truncated.json()
    body["choices"][0]["finish_reason"] = "length"
    route = respx.post("https://api.groq.com/openai/v1/chat/completions").mock(side_effect=[
        httpx.Response(200, json=body), httpx.Response(404), completion_response(),
    ])
    result = await AppDescriptionLLMClient(Settings(
        _env_file=None, llm_groq_api_key="groq-key",
    ), rate_limiter=NoopRateLimiter()).generate({"name": "Vendor App"})
    assert result.model == "openai/gpt-oss-20b"
    assert [json.loads(call.request.content)["model"] for call in route.calls] == [
        "openai/gpt-oss-120b", "qwen/qwen3.8-27b", "openai/gpt-oss-20b",
    ]


@pytest.mark.asyncio
async def test_missing_provider_and_all_models_cooling_down() -> None:
    settings = Settings(_env_file=None)
    client = AppDescriptionLLMClient(settings, rate_limiter=NoopRateLimiter())
    assert not client.has_provider()
    with pytest.raises(LLMGenerationError, match="llm_provider_not_configured"):
        await client.generate({"name": "App"})
    settings.llm_openai_api_key = "test-key"
    client.cooldowns.start(client._openai(), reason="http_429", seconds=60)
    assert client.has_provider()
    with pytest.raises(LLMGenerationError, match="llm_models_cooling_down"):
        await client.generate({"name": "App"})


def test_parse_description_payload_rejects_insufficient_evidence() -> None:
    with pytest.raises(LLMGenerationError, match="insufficient_evidence"):
        parse_description_payload('{"error":"insufficient_evidence"}')


def test_parse_description_payload_accepts_json_wrapped_in_provider_text() -> None:
    """Tolera razonamiento o texto de cortesía sin relajar la forma del resultado."""
    description, description_en = parse_description_payload(
        '<think>Comprobando evidencia.</think>\n'
        '{"long_description_es":"Descripción válida del proveedor.",'
        '"long_description_en":"Valid provider description."}\n'
        "Fin."
    )

    assert description == "Descripción válida del proveedor."
    assert description_en == "Valid provider description."


def test_parse_description_payload_rejects_missing_english_version() -> None:
    with pytest.raises(LLMGenerationError, match="missing_long_description_en"):
        parse_description_payload('{"long_description_es":"Descripción solamente."}')


@pytest.mark.asyncio
@respx.mock
async def test_rate_limited_model_is_not_retried_until_its_cooldown_expires() -> None:
    """Comprueba el escenario `rate_limited_model_is_not_retried_until_its_cooldown_expires`.
    """
    clock = FakeClock()
    cooldowns = InMemoryModelCooldownStore(monotonic=clock)
    settings = Settings(
        _env_file=None,
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
                            '{"long_description_es":"Descripcion generada correctamente.",'
                            '"long_description_en":"Description generated correctly."}'
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
    assert third.model == "openai/gpt-oss-120b"
    assert [json.loads(call.request.content)["model"] for call in groq_route.calls] == [
        "openai/gpt-oss-120b",
        "qwen/qwen3-32b",
        "qwen/qwen3-32b",
        "openai/gpt-oss-120b",
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
            """Prepara el recurso
            `test_enricher_marks_invalid_llm_response_as_failed.BadLLM.has_provider` usado por
            las pruebas para aislar el escenario `test enricher marks invalid llm response as
            failed.BadLLM.has provider` y conservar sus datos de entrada.
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

            self.failed = []


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
            """Prepara el recurso
            `test_enricher_treats_zero_max_apps_as_unlimited.GoodLLM.has_provider` usado por
            las pruebas para aislar el escenario `test enricher treats zero max apps as
            unlimited.GoodLLM.has provider` y conservar sus datos de entrada.
            """
            return True

        async def generate(self, _evidence):
            """Ejecuta `generate` dentro de `GoodLLM`.

            Args:
                _evidence (Any): Valor de `_evidence` utilizado por la operación.
            """
            return GeneratedDescription(
                description="Descripcion larga generada por IA para pruebas.",
                description_en="Long AI generated description for tests.",
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

            self.saved = []


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
    assert all(item["description_en"] == "Long AI generated description for tests."
               for item in catalog.saved)


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
                description_en="Description generated after releasing the SQL connection.",
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
