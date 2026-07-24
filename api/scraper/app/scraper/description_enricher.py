from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from typing import Any, Protocol
from urllib.parse import urlparse

import httpx
from selectolax.parser import HTMLParser

from app.api.app_mapper import best_resolved_source
from app.core.config import Settings
from app.core.cpu_pool import run_cpu_bound
from app.core.logging import get_logger
from app.db.enums import LongDescriptionStatus
from app.db.models import SoftwareApp
from app.repositories.catalog import CatalogRepository
from app.repositories.logs import ResolverLogRepository
from app.repositories.rate_limits import DatabaseLLMRateLimiter
from app.scraper.candidates import registered_domain
from app.scraper.llm import (
    TRANSIENT_HTTP_STATUSES,
    InMemoryModelCooldownStore,
    LLMGenerationError,
    LLMProviderConfig,
    LLMProviderName,
    ModelCooldownStore,
    NoLLMProviderConfigured,
    cooldown_from_headers,
    unique_model_ids,
)

logger = get_logger(__name__)


@dataclass(frozen=True)
class GeneratedDescription:
    description: str
    language: str
    provider: str
    model: str


@dataclass(frozen=True)
class EnrichmentResult:
    app_id: Any
    input_hash: str
    description: GeneratedDescription | None
    error: str | None = None
    provider: str | None = None
    model: str | None = None


@dataclass(frozen=True)
class DescriptionJobResult:
    app_id: Any
    status: str
    input_hash: str | None = None
    error: str | None = None
    provider: str | None = None
    model: str | None = None


class LLMRateLimiter(Protocol):
    async def wait_for_slot(self) -> Any: ...


class AppDescriptionLLMClient:
    def __init__(
        self,
        settings: Settings,
        rate_limiter: LLMRateLimiter | None = None,
        cooldowns: ModelCooldownStore | None = None,
    ) -> None:
        self.settings = settings
        self.rate_limiter = rate_limiter or DatabaseLLMRateLimiter()
        self.cooldowns = cooldowns or InMemoryModelCooldownStore()

    def has_provider(self) -> bool:
        return bool(self.settings.llm_groq_api_key or self._deepseek().api_key)

    async def generate(self, evidence: dict[str, Any]) -> GeneratedDescription:
        groq_models = self._groq_models()
        deepseek = self._deepseek()
        if not groq_models and not deepseek.api_key:
            raise NoLLMProviderConfigured("llm_provider_not_configured")

        last_error: LLMGenerationError | None = None
        for groq in groq_models:
            if self._log_cooldown_skip(groq):
                continue
            try:
                return await self._call_provider(groq, evidence)
            except LLMGenerationError as exc:
                last_error = exc
                if not exc.retryable:
                    break
                self._start_cooldown(groq, exc)

        if deepseek.api_key and not self._log_cooldown_skip(deepseek):
            try:
                return await self._call_provider(deepseek, evidence)
            except LLMGenerationError as exc:
                if exc.retryable:
                    self._start_cooldown(deepseek, exc)
                raise

        if last_error:
            raise last_error
        raise LLMGenerationError("llm_models_cooling_down", retryable=True)

    async def _call_provider(
        self,
        provider: LLMProviderConfig,
        evidence: dict[str, Any],
    ) -> GeneratedDescription:
        url = f"{provider.base_url.rstrip('/')}/chat/completions"
        payload = {
            "model": provider.model,
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "Generas descripciones tecnicas y utiles de aplicaciones para un catalogo "
                        "de descargas. Responde solo JSON valido."
                    ),
                },
                {
                    "role": "user",
                    "content": build_description_prompt(evidence),
                },
            ],
            "temperature": 0.2,
            "max_tokens": 520,
            "response_format": {"type": "json_object"},
        }
        headers = {
            "Authorization": f"Bearer {provider.api_key}",
            "Content-Type": "application/json",
        }
        await self.rate_limiter.wait_for_slot()
        logger.info(
            "llm_request_started",
            provider=provider.name.value,
            model=provider.model,
        )
        try:
            async with httpx.AsyncClient(
                timeout=self.settings.llm_request_timeout_seconds
            ) as client:
                response = await client.post(url, headers=headers, json=payload)
        except httpx.TimeoutException as exc:
            logger.warning(
                "llm_request_failed",
                provider=provider.name.value,
                model=provider.model,
                reason="timeout",
            )
            raise LLMGenerationError(
                "timeout",
                provider.name.value,
                provider.model,
                retryable=True,
                cooldown_seconds=self.settings.llm_transient_cooldown_seconds,
            ) from exc
        except httpx.HTTPError as exc:
            logger.warning(
                "llm_request_failed",
                provider=provider.name.value,
                model=provider.model,
                reason=exc.__class__.__name__,
            )
            raise LLMGenerationError(
                exc.__class__.__name__,
                provider.name.value,
                provider.model,
                retryable=True,
                cooldown_seconds=self.settings.llm_transient_cooldown_seconds,
            ) from exc

        if response.status_code >= 400:
            logger.warning(
                "llm_request_failed",
                provider=provider.name.value,
                model=provider.model,
                status_code=response.status_code,
            )
            retryable = response.status_code in TRANSIENT_HTTP_STATUSES
            cooldown_seconds = None
            if response.status_code == 429:
                cooldown_seconds = cooldown_from_headers(
                    response.headers,
                    default_seconds=self.settings.llm_rate_limit_cooldown_seconds,
                )
            elif retryable:
                cooldown_seconds = self.settings.llm_transient_cooldown_seconds
            elif response.status_code == 400:
                retryable = True
                cooldown_seconds = self.settings.llm_model_error_cooldown_seconds
            raise LLMGenerationError(
                f"http_{response.status_code}",
                provider.name.value,
                provider.model,
                retryable=retryable,
                cooldown_seconds=cooldown_seconds,
            )

        try:
            body = response.json()
            content = body["choices"][0]["message"]["content"]
        except Exception as exc:
            raise LLMGenerationError(
                "invalid_llm_response",
                provider.name.value,
                provider.model,
            ) from exc

        try:
            description, language = parse_description_payload(content)
        except LLMGenerationError as exc:
            logger.warning(
                "llm_request_failed",
                provider=provider.name.value,
                model=provider.model,
                reason=exc.reason,
            )
            raise LLMGenerationError(
                exc.reason,
                provider.name.value,
                provider.model,
            ) from exc
        logger.info(
            "llm_request_completed",
            provider=provider.name.value,
            model=provider.model,
        )
        return GeneratedDescription(
            description=description,
            language=language,
            provider=provider.name.value,
            model=provider.model,
        )

    def _groq_models(self) -> tuple[LLMProviderConfig, ...]:
        if not self.settings.llm_groq_api_key:
            return ()
        model_ids = unique_model_ids(
            self.settings.llm_groq_model,
            self.settings.llm_groq_fallback_models,
        )
        return tuple(
            LLMProviderConfig(
                name=LLMProviderName.GROQ,
                api_key=self.settings.llm_groq_api_key,
                base_url=self.settings.llm_groq_base_url,
                model=model,
            )
            for model in model_ids
        )

    def _deepseek(self) -> LLMProviderConfig:
        return LLMProviderConfig(
            name=LLMProviderName.DEEPSEEK,
            api_key=self.settings.llm_deepseek_api_key,
            base_url=self.settings.llm_deepseek_base_url,
            model=self.settings.llm_deepseek_model,
        )

    def _log_cooldown_skip(self, provider: LLMProviderConfig) -> bool:
        cooldown = self.cooldowns.get(provider)
        if cooldown is None:
            return False
        logger.info(
            "llm_model_skipped",
            provider=cooldown.provider,
            model=cooldown.model,
            reason=cooldown.reason,
            remaining_seconds=round(cooldown.remaining_seconds, 3),
        )
        return True

    def _start_cooldown(
        self,
        provider: LLMProviderConfig,
        error: LLMGenerationError,
    ) -> None:
        seconds = error.cooldown_seconds or self.settings.llm_transient_cooldown_seconds
        self.cooldowns.start(provider, reason=error.reason, seconds=seconds)
        logger.warning(
            "llm_model_cooldown_started",
            provider=provider.name.value,
            model=provider.model,
            reason=error.reason,
            cooldown_seconds=seconds,
        )


class AppDescriptionEnricher:
    def __init__(
        self,
        settings: Settings,
        catalog: CatalogRepository,
        logs: ResolverLogRepository,
        llm: AppDescriptionLLMClient | None = None,
    ) -> None:
        self.settings = settings
        self.catalog = catalog
        self.logs = logs
        self.llm = llm or AppDescriptionLLMClient(settings)

    async def enrich_app(
        self,
        software_app_id: Any,
        *,
        force: bool = False,
    ) -> DescriptionJobResult:
        apps = await self.catalog.apps_for_description_enrichment([software_app_id])
        if not apps:
            return DescriptionJobResult(app_id=software_app_id, status="missing")
        app = apps[0]
        input_hash = description_input_hash(app)
        if (
            not force
            and app.long_description_status == LongDescriptionStatus.COMPLETED.value
            and app.long_description_input_hash == input_hash
            and app.long_description
        ):
            return DescriptionJobResult(
                app_id=app.id,
                status="skipped",
                input_hash=input_hash,
            )
        if not self.llm.has_provider():
            return DescriptionJobResult(
                app_id=app.id,
                status="pending",
                input_hash=input_hash,
                error="llm_provider_not_configured",
            )

        metadata = await fetch_safe_page_metadata(
            app.official_url,
            timeout=self.settings.request_timeout_seconds,
        )
        try:
            description = await self.llm.generate(description_evidence(app, metadata))
        except LLMGenerationError as exc:
            await self.catalog.mark_long_description_failed(
                software_app_id=app.id,
                input_hash=input_hash,
                error=exc.reason,
                source=exc.provider,
                model=exc.model,
            )
            await self.logs.add(
                phase="descriptor",
                status="failed",
                message=exc.reason,
                safe_metadata={
                    "winstall_id": app.winstall_id,
                    "input_hash": input_hash,
                    "provider": exc.provider,
                    "model": exc.model,
                },
            )
            return DescriptionJobResult(
                app_id=app.id,
                status="failed",
                input_hash=input_hash,
                error=exc.reason,
                provider=exc.provider,
                model=exc.model,
            )

        await self.catalog.save_long_description(
            software_app_id=app.id,
            description=description.description,
            language=description.language,
            source=description.provider,
            model=description.model,
            input_hash=input_hash,
        )
        await self.logs.add(
            phase="descriptor",
            status="completed",
            safe_metadata={
                "winstall_id": app.winstall_id,
                "input_hash": input_hash,
                "provider": description.provider,
                "model": description.model,
            },
        )
        return DescriptionJobResult(
            app_id=app.id,
            status="completed",
            input_hash=input_hash,
            provider=description.provider,
            model=description.model,
        )

    async def enrich_pending(self, software_app_ids: list[Any] | None = None) -> int:
        if not self.llm.has_provider():
            logger.warning("description_enrichment_skipped", reason="llm_provider_not_configured")
            await self.logs.add(
                phase="description",
                status="skipped",
                message="llm_provider_not_configured",
            )
            return 0

        max_jobs = self.settings.llm_max_apps_per_run
        unlimited = max_jobs <= 0
        jobs = []
        for app in await self.catalog.apps_for_description_enrichment(software_app_ids):
            input_hash = description_input_hash(app)
            if (
                app.long_description_status == LongDescriptionStatus.COMPLETED.value
                and app.long_description_input_hash == input_hash
                and app.long_description
            ):
                continue
            jobs.append((app, input_hash))
            if not unlimited and len(jobs) >= max_jobs:
                break

        if not jobs:
            logger.info("description_enrichment_no_jobs")
            return 0

        logger.info(
            "description_enrichment_batch_started",
            jobs=len(jobs),
            max_apps_per_run=max_jobs,
            unlimited=unlimited,
        )
        completed = 0
        failed = 0
        for app, _input_hash in jobs:
            result = await self.enrich_app(app.id)
            if result.status == "completed":
                completed += 1
                continue
            if result.status == "failed":
                failed += 1
        logger.info(
            "description_enrichment_batch_finished",
            completed=completed,
            failed=failed,
        )
        return completed


def description_input_hash(app: SoftwareApp) -> str:
    payload = {
        "winstall_id": app.winstall_id,
        "name": app.name,
        "publisher": app.publisher,
        "description": app.description,
        "official_url": app.official_url,
        "latest_version": app.latest_version,
        "tags": sorted(tag.normalized_tag for tag in app.tags),
    }
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def description_evidence(app: SoftwareApp, page_metadata: dict[str, str]) -> dict[str, Any]:
    resolved = best_resolved_source(app)
    return {
        "name": app.name,
        "publisher": app.publisher,
        "short_description": app.description,
        "tags": sorted(tag.tag for tag in app.tags),
        "winstall_id": app.winstall_id,
        "latest_version": app.latest_version,
        "official_domain": registered_domain(app.official_url) if app.official_url else None,
        "installer": {
            "final_domain": resolved.final_domain if resolved else None,
            "filename": resolved.filename if resolved else None,
            "extension": resolved.extension if resolved else None,
            "status": resolved.status if resolved else None,
        },
        "official_page_metadata": page_metadata,
    }


def build_embedding_metadata(app: SoftwareApp) -> dict[str, Any]:
    """Build the allow-listed metadata shared by indexing and training.

    Relationship values are deliberately read from ``__dict__`` so callers that
    did not eagerly load them never trigger implicit async database I/O.
    """
    tags = sorted(
        tag.tag.strip()
        for tag in app.__dict__.get("tags", [])
        if tag.tag and tag.tag.strip()
    )
    sources = app.__dict__.get("sources", [])
    systems = sorted(
        {
            str(system).strip().lower()
            for system in (app.operating_systems or [])
            if str(system).strip()
        }
        | {
            source.operating_system.strip().lower()
            for source in sources
            if source.operating_system and source.operating_system.strip()
        }
    )
    architectures = sorted(
        {
            source.architecture.strip().lower()
            for source in sources
            if source.architecture and source.architecture.strip()
        }
    )
    return {
        "schemaVersion": 1,
        "name": app.name.strip(),
        "packageId": app.winstall_id.strip(),
        "publisher": (app.publisher or "").strip() or None,
        "tags": tags,
        "shortDescription": (app.description or "").strip() or None,
        "longDescription": (app.long_description or "").strip() or None,
        "operatingSystems": systems,
        "architectures": architectures,
        "version": (app.latest_version or "").strip() or None,
        "officialDomain": (
            registered_domain(app.official_url) if app.official_url else None
        ),
    }


def build_embedding_text(app: SoftwareApp) -> str:
    metadata = build_embedding_metadata(app)
    parts = [
        f"Nombre: {metadata['name']}",
        f"Package ID: {metadata['packageId']}",
        f"Editor: {metadata['publisher'] or '-'}",
        f"Tags: {', '.join(metadata['tags']) or '-'}",
        f"Descripcion corta: {metadata['shortDescription'] or '-'}",
        "Descripcion larga: "
        f"{metadata['longDescription'] or metadata['shortDescription'] or '-'}",
        f"Sistemas: {', '.join(metadata['operatingSystems']) or '-'}",
        f"Arquitecturas: {', '.join(metadata['architectures']) or '-'}",
        f"Version: {metadata['version'] or '-'}",
        f"Dominio oficial: {metadata['officialDomain'] or '-'}",
    ]
    return "\n".join(parts)


def embedding_content_hash(app: SoftwareApp) -> str:
    canonical = {
        "content": build_embedding_text(app),
        "metadata": build_embedding_metadata(app),
    }
    raw = json.dumps(
        canonical,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def build_description_prompt(evidence: dict[str, Any]) -> str:
    return (
        "Crea una descripcion larga en espanol para esta aplicacion.\n"
        "Reglas:\n"
        "- 120 a 180 palabras.\n"
        "- Sin markdown.\n"
        "- No incluyas URLs.\n"
        "- No inventes funciones no apoyadas por la evidencia.\n"
        "- Si la evidencia es escasa, explica el proposito probable con cautela.\n"
        "Devuelve exactamente un JSON con esta forma: "
        '{"long_description":"...","language":"es"}.\n'
        f"Evidencia segura:\n{json.dumps(evidence, ensure_ascii=False, sort_keys=True)}"
    )


def parse_description_payload(content: str) -> tuple[str, str]:
    cleaned = content.strip()
    fenced = re.match(r"^```(?:json)?\s*(.*?)\s*```$", cleaned, re.DOTALL | re.IGNORECASE)
    if fenced:
        cleaned = fenced.group(1).strip()
    try:
        payload = json.loads(cleaned)
    except json.JSONDecodeError as exc:
        raise LLMGenerationError("invalid_json") from exc

    description = payload.get("long_description") or payload.get("description")
    language = payload.get("language") or "es"
    if not isinstance(description, str) or not description.strip():
        raise LLMGenerationError("missing_long_description")
    if not isinstance(language, str) or not language.strip():
        language = "es"
    return normalize_generated_description(description), language.strip()[:16]


def normalize_generated_description(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


async def fetch_safe_page_metadata(url: str | None, timeout: float) -> dict[str, str]:
    if not url or urlparse(url).scheme not in {"http", "https"}:
        return {}
    try:
        async with httpx.AsyncClient(
            timeout=timeout,
            follow_redirects=True,
            headers={"User-Agent": "BatchDownloaderScraper/0.1"},
        ) as client:
            response = await client.get(url)
    except httpx.HTTPError:
        return {}
    if not response.is_success:
        return {}
    content_type = response.headers.get("content-type", "")
    if content_type and "html" not in content_type:
        return {}
    return await run_cpu_bound(_parse_safe_page_metadata, response.text)


def _parse_safe_page_metadata(html: str) -> dict[str, str]:
    parser = HTMLParser(html)
    metadata: dict[str, str] = {}
    title = parser.css_first("title")
    if title:
        metadata["title"] = safe_text(title.text())
    for node in parser.css("meta"):
        key = (
            node.attributes.get("name")
            or node.attributes.get("property")
            or node.attributes.get("itemprop")
        )
        content = node.attributes.get("content")
        if not key or not content:
            continue
        normalized_key = key.lower()
        if normalized_key in {
            "description",
            "keywords",
            "og:title",
            "og:description",
            "twitter:title",
            "twitter:description",
        }:
            metadata[normalized_key] = safe_text(content)
    return {key: value for key, value in metadata.items() if value}


def safe_text(value: str, max_length: int = 500) -> str:
    return re.sub(r"\s+", " ", value).strip()[:max_length]
