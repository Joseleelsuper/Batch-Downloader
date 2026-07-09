from __future__ import annotations

import asyncio
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
from app.core.logging import get_logger
from app.db.enums import LongDescriptionStatus
from app.db.models import SoftwareApp
from app.repositories.catalog import CatalogRepository
from app.repositories.logs import ResolverLogRepository
from app.repositories.rate_limits import DatabaseLLMRateLimiter
from app.scraper.candidates import registered_domain

logger = get_logger(__name__)


@dataclass(frozen=True)
class LLMProviderConfig:
    name: str
    api_key: str
    base_url: str
    model: str


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


class LLMGenerationError(Exception):
    def __init__(self, reason: str, provider: str | None = None, model: str | None = None) -> None:
        super().__init__(reason)
        self.reason = reason
        self.provider = provider
        self.model = model


class NoLLMProviderConfigured(LLMGenerationError):
    pass


class AppDescriptionLLMClient:
    def __init__(
        self,
        settings: Settings,
        rate_limiter: LLMRateLimiter | None = None,
    ) -> None:
        self.settings = settings
        self.rate_limiter = rate_limiter or DatabaseLLMRateLimiter()

    def has_provider(self) -> bool:
        return bool(self._groq().api_key or self._deepseek().api_key)

    async def generate(self, evidence: dict[str, Any]) -> GeneratedDescription:
        groq = self._groq()
        deepseek = self._deepseek()
        if not groq.api_key and not deepseek.api_key:
            raise NoLLMProviderConfigured("llm_provider_not_configured")

        first_error: LLMGenerationError | None = None
        if groq.api_key:
            try:
                return await self._call_provider(groq, evidence)
            except LLMGenerationError as exc:
                first_error = exc

        if deepseek.api_key:
            try:
                return await self._call_provider(deepseek, evidence)
            except LLMGenerationError as exc:
                if groq.api_key:
                    try:
                        return await self._call_provider(groq, evidence)
                    except LLMGenerationError as retry_exc:
                        raise retry_exc from exc
                raise exc

        if first_error:
            raise first_error
        raise NoLLMProviderConfigured("llm_provider_not_configured")

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
            provider=provider.name,
            model=provider.model,
        )
        try:
            async with httpx.AsyncClient(timeout=self.settings.llm_request_timeout_seconds) as client:
                response = await client.post(url, headers=headers, json=payload)
        except httpx.TimeoutException as exc:
            logger.warning(
                "llm_request_failed",
                provider=provider.name,
                model=provider.model,
                reason="timeout",
            )
            raise LLMGenerationError("timeout", provider.name, provider.model) from exc
        except httpx.HTTPError as exc:
            logger.warning(
                "llm_request_failed",
                provider=provider.name,
                model=provider.model,
                reason=exc.__class__.__name__,
            )
            raise LLMGenerationError(exc.__class__.__name__, provider.name, provider.model) from exc

        if response.status_code >= 400:
            logger.warning(
                "llm_request_failed",
                provider=provider.name,
                model=provider.model,
                status_code=response.status_code,
            )
            raise LLMGenerationError(
                f"http_{response.status_code}",
                provider.name,
                provider.model,
            )

        try:
            body = response.json()
            content = body["choices"][0]["message"]["content"]
        except Exception as exc:
            raise LLMGenerationError("invalid_llm_response", provider.name, provider.model) from exc

        try:
            description, language = parse_description_payload(content)
        except LLMGenerationError as exc:
            logger.warning(
                "llm_request_failed",
                provider=provider.name,
                model=provider.model,
                reason=exc.reason,
            )
            raise LLMGenerationError(exc.reason, provider.name, provider.model) from exc
        logger.info(
            "llm_request_completed",
            provider=provider.name,
            model=provider.model,
        )
        return GeneratedDescription(
            description=description,
            language=language,
            provider=provider.name,
            model=provider.model,
        )

    def _groq(self) -> LLMProviderConfig:
        return LLMProviderConfig(
            name="groq",
            api_key=self.settings.llm_groq_api_key,
            base_url=self.settings.llm_groq_base_url,
            model=self.settings.llm_groq_model,
        )

    def _deepseek(self) -> LLMProviderConfig:
        return LLMProviderConfig(
            name="deepseek",
            api_key=self.settings.llm_deepseek_api_key,
            base_url=self.settings.llm_deepseek_base_url,
            model=self.settings.llm_deepseek_model,
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


def build_embedding_text(app: SoftwareApp) -> str:
    tags = ", ".join(sorted(tag.tag for tag in app.tags))
    parts = [
        f"Nombre: {app.name}",
        f"Editor: {app.publisher or '-'}",
        f"Tags: {tags or '-'}",
        f"Descripcion corta: {app.description or '-'}",
        f"Descripcion larga: {app.long_description or app.description or '-'}",
        f"Version: {app.latest_version or '-'}",
        f"Web oficial: {(registered_domain(app.official_url) if app.official_url else None) or '-'}",
    ]
    return "\n".join(parts)


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
    parser = HTMLParser(response.text)
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
