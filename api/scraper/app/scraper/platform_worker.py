"""Procesa aplicaciones del pipeline: recopila candidatos directos y Winstall, valida en
paralelo, filtra incompatibilidades y persiste resoluciones con diagnóstico.

See Also:
    app.scraper.installer_policy: Define identidad, ranking y publicación segura.
    app.scraper.pipeline_runtime: Coordina parada, pausa y contadores del pipeline.
"""

from __future__ import annotations

import asyncio
import uuid
from dataclasses import dataclass, field, replace
from typing import Any
from urllib.parse import urlparse

import httpx
from sqlalchemy.exc import (
    OperationalError,
)

from app.core.config import Settings
from app.core.cpu_pool import run_cpu_bound
from app.core.logging import get_logger
from app.core.url_protector import UrlProtector
from app.db.enums import (
    ResolutionStatus,
    ScrapeOutcome,
    ValidationStatus,
)
from app.db.models import ScraperWorkItem
from app.repositories.catalog import CatalogRepository
from app.repositories.catalog_rules import ResolvedSourceCreate
from app.repositories.logs import ResolverLogRepository
from app.repositories.pipeline import (
    QUEUE_FILTER_SCRAPER,
    PipelineRepository,
)
from app.repositories.runs import worker_id
from app.scraper.candidates import (
    InstallerCandidate,
    candidate_has_download_intent,
    candidate_variants,
    extract_candidates,
    infer_architecture,
    infer_operating_system,
    is_download_candidate,
    registered_domain,
    score_candidate,
)
from app.scraper.content_workers import enqueue_so_filter_for_app
from app.scraper.github import GitHubReleaseResolver, parse_github_repo
from app.scraper.icon_resolver import IconResolver
from app.scraper.installer_policy import (
    ValidInstaller,
    catalog_url_for_installer,
    dedupe_candidates,
    dedupe_valid_installers,
    fallback_candidates,
    github_collection_timeout_seconds,
    infer_validated_operating_system,
    installer_app_compatibility_reason,
    is_actionable_installer_candidate,
    is_catalog_publishable_installer,
    is_download_landing_page,
    is_windows_winstall_archive,
    known_official_candidates,
    persisted_installer_app_compatibility_reason,
    rank_installers,
    resolved_metadata,
    should_collect_official_installers,
    use_only_known_official_candidates,
    validated_installer_version,
    validated_installers_cover_latest_version,
)
from app.scraper.pipeline_runtime import (
    PipelineRuntime,
    async_session_local,
)
from app.scraper.pipeline_support import (
    claim_item,
    exception_detail,
    finish_item,
    parse_payload_app,
    payload_package_id,
    queue_has_active_work,
    set_current,
)
from app.scraper.playwright_fallback import PlaywrightCandidateCollector
from app.scraper.strategies import (
    CandidateResolverStrategy,
    CandidateResolverStrategyRegistry,
    ScrapeRuntime,
)
from app.scraper.validator import (
    DownloadValidator,
    domain_has_public_dns,
    is_sourceforge_download_url,
)
from app.scraper.winstall import (
    WinstallApp,
)
from app.scraper.winstall_candidates import (
    collect_winstall_github_candidates,
    collect_winstall_parent_index_candidates,
    read_limited_html,
)
from app.scraper.worker_recovery import recover_worker_failure

logger = get_logger(__name__)



@dataclass
class CandidateValidationDiagnostics:
    """Acumula contadores de descubrimiento, elegibilidad, intentos, éxitos y causas de descarte
    del grupo de candidatos.

    Attributes:
        discovered: Candidatos observados tras expansión.
        eligible: Candidatos con plataforma o intención descargable.
        attempted: Candidatos enviados a validación.
        valid: Validaciones aceptadas.
        skipped: Causas de omisión y sus recuentos.
        rejected: Causas de rechazo técnico.
        errors: Excepciones clasificadas por tipo.
    """

    discovered: int = 0

    eligible: int = 0

    attempted: int = 0

    valid: int = 0

    skipped: dict[str, int] = field(default_factory=dict)

    rejected: dict[str, int] = field(default_factory=dict)

    errors: dict[str, int] = field(default_factory=dict)


    def skip(self, reason: str) -> None:
        """Incrementa la causa de un candidato que no merece validación.

        Args:
            reason: Código estable del motivo del salto o rechazo.

        Returns:
            None.
        """
        self.skipped[reason] = self.skipped.get(reason, 0) + 1

    def reject(self, reason: str | None) -> None:
        """Incrementa la causa de un candidato cuya validación no puede publicarse.

        Args:
            reason: Código estable del motivo del salto o rechazo.
        """
        key = reason or "unknown"
        self.rejected[key] = self.rejected.get(key, 0) + 1

    def error(self, exc: Exception) -> None:
        """Cuenta una excepción sin conservar datos sensibles.

        Args:
            exc: Excepción capturada durante validación o ejecución.
        """
        key = exc.__class__.__name__
        self.errors[key] = self.errors.get(key, 0) + 1

    def as_metadata(self) -> dict[str, Any]:
        """Expone los contadores en un mapa persistible de diagnóstico.

        Returns:
            metadatos de validación.
        """
        return {
            "discovered": self.discovered,
            "eligible": self.eligible,
            "attempted": self.attempted,
            "valid": self.valid,
            "skipped": self.skipped,
            "rejected": self.rejected,
            "errors": self.errors,
        }


class PlatformScraperWorker:
    """Worker principal que reserva aplicaciones, coordina candidatos directos y fallback y
    conserva instaladores publicados cuando un reemplazo falla.
    """

    def __init__(
        self,
        settings: Settings,
        candidate_resolvers: CandidateResolverStrategyRegistry | None = None,
    ) -> None:
        """Configura validadores, resolutores GitHub/Playwright, protección de URLs y el registro
        de estrategias por proveedor.

        Args:
            settings: Configuración de red, tiempos y límites del scraper.
            candidate_resolvers: Registro inyectable de estrategias de recopilación por
                proveedor.
        """
        self.settings = settings

        self.worker_id = f"scraper:{worker_id()}"

        self.url_protector = UrlProtector(settings.url_protection_secret)

        self.validator = DownloadValidator(settings)

        self.playwright = PlaywrightCandidateCollector(settings)

        self.github = GitHubReleaseResolver(settings)

        self.icon_resolver = IconResolver(settings)

        self.candidate_resolvers = candidate_resolvers or CandidateResolverStrategyRegistry(
            (
                CandidateResolverStrategy(
                    name="github_releases",
                    predicate=lambda url: parse_github_repo(url) is not None,
                    callback=self._collect_github_official_candidates,
                ),
                CandidateResolverStrategy(
                    name="html_landing_playwright",
                    predicate=lambda _url: True,
                    callback=self._collect_html_official_candidates,
                ),
            )
        )


    async def run(self, runtime: PipelineRuntime) -> None:
        """Consume la cola hasta parada cooperativa, reintenta errores de reserva, aplica timeout
        por aplicación y clasifica cada resultado del scraper.

        Args:
            runtime: Estado compartido de la ejecución y sus workers.
        """
        logger.info("platform_scraper_worker_started", worker_id=self.worker_id)
        while not runtime.stop_event.is_set():
            if not await runtime.before_next_item():
                break
            try:
                item = await claim_item(
                    self.settings,
                    QUEUE_FILTER_SCRAPER,
                    self.worker_id,
                    run_id=runtime.run_id,
                )
            except OperationalError as exc:
                logger.warning(
                    "scraper_claim_retry",
                    worker_id=self.worker_id,
                    error="OperationalError",
                    detail=exception_detail(exc),
                )
                await asyncio.sleep(0.25)
                continue
            except Exception as exc:
                logger.warning(
                    "scraper_claim_retry",
                    worker_id=self.worker_id,
                    error=exc.__class__.__name__,
                    detail=exception_detail(exc),
                )
                await asyncio.sleep(1)
                continue
            if item is None:
                if (
                    runtime.searcher_done.is_set()
                    and runtime.filter_done.is_set()
                    and not await queue_has_active_work(
                        self.settings,
                        QUEUE_FILTER_SCRAPER,
                        runtime.run_id,
                    )
                ):
                    break
                await asyncio.sleep(1)
                continue
            try:
                async with asyncio.timeout(self.settings.scrape_app_timeout_seconds):
                    outcome = await self._scrape_item(runtime, item)
                await finish_item(self.settings, item, "complete", None)
                await count_scrape_outcome(runtime, outcome)
            except TimeoutError:
                reason = f"timeout_after_{self.settings.scrape_app_timeout_seconds:.0f}s"
                if item.attempts < 4:
                    await finish_item(
                        self.settings,
                        item,
                        "requeue",
                        reason,
                        delay_seconds=min(30, 2**item.attempts),
                    )
                else:
                    await finish_item(self.settings, item, "fail", reason)
                    await runtime.increment("apps_failed")
                    await runtime.increment("apps_transient_failed")
                logger.warning(
                    "scrape_app_timeout",
                    winstall_id=item.package_id,
                    timeout_seconds=self.settings.scrape_app_timeout_seconds,
                    attempts=item.attempts,
                    requeued=item.attempts < 4,
                )
            except Exception as exc:
                await recover_worker_failure(self.settings, runtime, item, exc, "scraper")

    async def _scrape_item(
        self,
        runtime: PipelineRuntime,
        item: ScraperWorkItem,
    ) -> ScrapeOutcome:
        """Actualiza la aplicación, valida fallback en paralelo con recopilación oficial, publica
        solo instaladores compatibles y conserva estado ante ausencia o fallo transitorio.

        Args:
            runtime: Estado compartido de la ejecución y sus workers.
            item: Trabajo de scraper reservado para una aplicación.

        Returns:
            ScrapeOutcome que distingue resuelto, ausente confirmado, revisión, omitido o
                fallo transitorio.
        """
        item_started_at = asyncio.get_running_loop().time()
        payload = item.payload_json or {}
        package_id = payload_package_id(payload, item)
        app = parse_payload_app(payload, package_id)
        official_url = payload.get("official_url") or app.homepage
        await set_current(
            self.settings,
            runtime.run_id,
            app.package_id,
            app.name,
            "scraper_upserting_app",
        )
        async with async_session_local()() as session:
            catalog = CatalogRepository(session, self.url_protector)
            software_app, _created = await catalog.winstall.upsert_winstall_app_with_created(app)
            software_app_id = software_app.id
            software_app_official_url = software_app.official_url
            software_app_icon_url = software_app.icon_url
            software_app_winstall_id = software_app.winstall_id
            await session.commit()

        await self._enrich_github_icon(
            software_app_id,
            software_app_official_url,
            software_app_icon_url,
            software_app_winstall_id,
            app,
        )

        direct_candidates: list[InstallerCandidate] = []
        fallback = fallback_candidates(payload, app)
        fallback_validation_task = asyncio.create_task(
            self._validate_candidate_group(
                app,
                fallback,
                ResolutionStatus.FALLBACK,
                max_candidates=48,
                max_valid=12,
            )
        )
        filter_info = payload.get("filter") or {}
        try:
            if should_collect_official_installers(
                app,
                official_url,
                use_official=bool(filter_info.get("use_official")),
                fallback=fallback,
            ):
                await set_current(
                    self.settings,
                    runtime.run_id,
                    app.package_id,
                    app.name,
                    "scraper_collecting_official_installers",
                )
                collection_budget = max(
                    8.0,
                    min(30.0, self.settings.scrape_app_timeout_seconds * 0.34),
                )
                try:
                    async with asyncio.timeout(collection_budget):
                        direct_candidates = await self._collect_official_candidates(
                            runtime,
                            app,
                            official_url or app.homepage or known_official_candidates(app)[0].url,
                        )
                except TimeoutError:
                    logger.warning(
                        "official_candidate_collection_timeout",
                        winstall_id=app.package_id,
                        timeout_seconds=collection_budget,
                    )

            direct_validation_task = asyncio.create_task(
                self._validate_candidate_group(
                    app,
                    direct_candidates,
                    ResolutionStatus.DIRECT,
                    max_candidates=96,
                    max_valid=12,
                )
            )
            (
                (direct_valid, direct_diagnostics),
                (
                    fallback_valid,
                    fallback_diagnostics,
                ),
            ) = await asyncio.gather(direct_validation_task, fallback_validation_task)
        finally:
            if not fallback_validation_task.done():
                fallback_validation_task.cancel()
                await asyncio.gather(fallback_validation_task, return_exceptions=True)

        valid_installers = dedupe_valid_installers([*direct_valid, *fallback_valid])
        validation_diagnostics = {
            "direct": direct_diagnostics.as_metadata(),
            "fallback": fallback_diagnostics.as_metadata(),
        }
        valid_installers = await self._refresh_missing_candidates(
            app, fallback, valid_installers, validation_diagnostics, item_started_at
        )

        valid_installers = publishable_app_installers(app, valid_installers, validation_diagnostics)

        await set_current(
            self.settings,
            runtime.run_id,
            app.package_id,
            app.name,
            "scraper_saving_installers",
        )
        async with async_session_local()() as session:
            catalog = CatalogRepository(session, self.url_protector)
            logs = ResolverLogRepository(session)
            pipeline = PipelineRepository(session)
            software_app = await catalog.winstall.upsert_winstall_app(app)
            expired_incompatible = await self._expire_incompatible_published_installers(
                catalog,
                logs,
                software_app.id,
                app,
            )
            if expired_incompatible:
                validation_diagnostics["publication"]["expired_incompatible"] = expired_incompatible
                await session.flush()
                await session.refresh(
                    software_app,
                    attribute_names=[
                        "catalog_available_source_count",
                        "catalog_review_source_count",
                        "catalog_status",
                    ],
                )
            if not valid_installers:
                if software_app.catalog_status == "available":
                    await logs.add(
                        phase="resolve",
                        status="transient_failed",
                        message=(
                            "No replacement was validated; the published installer was preserved."
                        ),
                        safe_metadata={
                            "winstall_id": app.package_id,
                            "candidate_diagnostics": validation_diagnostics,
                        },
                    )
                    await session.commit()
                    return ScrapeOutcome.TRANSIENT_FAILED
                source = await catalog.sources.default_source_for_app(software_app.id)
                verification = await catalog.sources.active_absence_verification(software_app.id)
                if source:
                    await catalog.sources.mark_source_status(
                        source.id,
                        ResolutionStatus.MISSING
                        if verification
                        else ResolutionStatus.REQUIRES_MANUAL_REVIEW,
                    )
                    await logs.add(
                        phase="resolve",
                        status=("confirmed_missing" if verification else "requires_manual_review"),
                        download_source_id=source.id,
                        message=(
                            "No safe installer candidate was found and active evidence exists."
                            if verification
                            else (
                                "No safe installer candidate was found; "
                                "manual evidence is required."
                            )
                        ),
                        safe_metadata={
                            "winstall_id": app.package_id,
                            "candidate_diagnostics": validation_diagnostics,
                        },
                    )
                await enqueue_so_filter_for_app(
                    pipeline,
                    runtime.run_id,
                    software_app,
                )
                await session.commit()
                return (
                    ScrapeOutcome.CONFIRMED_MISSING if verification else ScrapeOutcome.NEEDS_REVIEW
                )
            await self._save_valid_installers(
                catalog,
                logs,
                software_app.id,
                app,
                official_url,
                valid_installers,
            )
            await enqueue_so_filter_for_app(
                pipeline,
                runtime.run_id,
                software_app,
            )
            await session.commit()
            return ScrapeOutcome.RESOLVED

    async def _expire_incompatible_published_installers(
        self,
        catalog: CatalogRepository,
        logs: ResolverLogRepository,
        software_app_id: uuid.UUID,
        app: WinstallApp,
    ) -> int:
        """Revalida y expira resoluciones publicadas que ya no coinciden con la identidad o
        versión actual de Winstall.

        Args:
            catalog: Repositorio de catálogo de la sesión de guardado.
            logs: Repositorio de trazas de resolución.
            software_app_id: UUID de la aplicación que recibe instaladores.
            app: Aplicación Winstall normalizada.

        Returns:
            número de filas expiradas.
        """
        incompatible = []
        for resolved in await catalog.sources.valid_resolved_sources_for_app(software_app_id):
            url = catalog.reveal_url(resolved)
            if not url:
                continue
            reason = persisted_installer_app_compatibility_reason(
                app,
                url=url,
                filename=resolved.filename,
                version=resolved.version,
                metadata=resolved.metadata_json,
            )
            if reason is None:
                continue
            incompatible.append(resolved)
            await logs.add(
                phase="resolve",
                status="expired_incompatible",
                download_source_id=resolved.download_source_id,
                message="A previously published artifact no longer matches this app.",
                safe_metadata={
                    "winstall_id": app.package_id,
                    "reason": reason,
                    "filename": resolved.filename,
                    "version": resolved.version,
                },
            )
        await catalog.sources.expire_resolved_sources(incompatible)
        return len(incompatible)

    async def _enrich_github_icon(
        self,
        software_app_id: uuid.UUID,
        official_url: str | None,
        icon_url: str | None,
        winstall_id: str,
        app: WinstallApp,
    ) -> None:
        """Completa el icono ausente usando GitHub y actualiza la aplicación solo cuando la
        procedencia es segura.

        Args:
            software_app_id: UUID de la aplicación que recibe instaladores.
            official_url: Página oficial que sirve de origen o Referer.
            icon_url: URL de icono ya persistida, si existe.
            winstall_id: Identificador de Winstall de la aplicación.
            app: Aplicación Winstall normalizada.
        """
        from app.repositories.catalog_rules import is_github_homepage, is_replaceable_github_icon

        if not (is_github_homepage(official_url) and is_replaceable_github_icon(icon_url)):
            return
        try:
            result = await self.icon_resolver.resolve(
                replace(
                    app,
                    homepage=official_url,
                    icon_url=icon_url,
                )
            )
        except Exception as exc:
            logger.warning(
                "scraper_inline_icon_failed",
                winstall_id=winstall_id,
                error=exc.__class__.__name__,
            )
            return

        async with async_session_local()() as session:
            catalog = CatalogRepository(session, self.url_protector)
            logs = ResolverLogRepository(session)
            if result is None:
                await logs.add(
                    phase="icon",
                    status="discarded",
                    message="no_safe_github_image",
                    safe_metadata={"winstall_id": winstall_id},
                )
            else:
                updated = await catalog.update_icon_url(software_app_id, result.url)
                await logs.add(
                    phase="icon",
                    status="resolved" if updated else "skipped",
                    safe_metadata={
                        "winstall_id": winstall_id,
                        "source": result.source,
                        "domain": registered_domain(result.url),
                    },
                )
            await session.commit()

    async def _collect_official_candidates(
        self,
        runtime: PipelineRuntime,
        app: WinstallApp,
        official_url: str,
    ) -> list[InstallerCandidate]:
        """Selecciona la primera estrategia compatible y recoge candidatos oficiales; devuelve
        vacío si el proveedor no aporta estrategia.

        Args:
            runtime: Estado compartido de la ejecución y sus workers.
            app: Aplicación Winstall normalizada.
            official_url: Página oficial que sirve de origen o Referer.

        Returns:
            candidatos directos.
        """
        known_candidates = known_official_candidates(app)
        if use_only_known_official_candidates(app, known_candidates):
            return known_candidates
        strategy = self.candidate_resolvers.find(official_url)
        if strategy is None:
            return known_candidates
        collected = await strategy.collect(runtime, app, official_url)
        return dedupe_candidates([*known_candidates, *collected])

    async def _collect_github_official_candidates(
        self,
        _runtime: ScrapeRuntime,
        app: WinstallApp,
        official_url: str,
    ) -> list[InstallerCandidate]:
        """Recopila assets de release GitHub respetando un presupuesto de timeout y la versión
        latest.

        Args:
            _runtime: Contexto de ejecución no requerido por la estrategia GitHub.
            app: Aplicación Winstall normalizada.
            official_url: Página oficial que sirve de origen o Referer.

        Returns:
            candidatos GitHub.
        """
        try:
            async with asyncio.timeout(github_collection_timeout_seconds(self.settings)):
                return await self.github.collect(official_url, app.latest_version)
        except Exception:
            return []

    async def _collect_html_official_candidates(
        self,
        _runtime: ScrapeRuntime,
        _app: WinstallApp,
        official_url: str,
    ) -> list[InstallerCandidate]:
        """Lee HTML oficial, extrae enlaces y sigue hasta cuatro landing pages en paralelo.

        Args:
            _runtime: Contexto de ejecución no requerido por la estrategia HTML.
            _app: Aplicación no requerida por la estrategia HTML.
            official_url: Página oficial que sirve de origen o Referer.

        Returns:
            candidatos HTML y landing.
        """
        html = ""
        try:
            async with httpx.AsyncClient(
                timeout=self.settings.request_timeout_seconds,
                follow_redirects=True,
                headers={"User-Agent": "BatchDownloaderScraper/0.1"},
            ) as client:
                response = await client.get(official_url)
                response.raise_for_status()
                if "html" in response.headers.get("content-type", "").lower():
                    html = response.text
        except Exception:
            html = ""

        candidates = await run_cpu_bound(extract_candidates, html, official_url) if html else []
        candidates.extend(await self._collect_download_landing_candidates(official_url, candidates))
        if not any(is_actionable_installer_candidate(candidate) for candidate in candidates):
            try:
                candidates.extend(await self.playwright.collect(official_url))
            except Exception:
                pass
        return dedupe_candidates(candidates)

    async def _collect_download_landing_candidates(
        self,
        official_url: str,
        candidates: list[InstallerCandidate],
    ) -> list[InstallerCandidate]:
        """Descarga una página intermedia y convierte sus enlaces de instalación en candidatos
        con Referer.

        Args:
            official_url: Página oficial que sirve de origen o Referer.
            candidates: Candidatos que se recopilan, puntúan o validan.

        Returns:
            candidatos derivados.
        """
        official_domain = registered_domain(official_url)
        landing_pages = [
            candidate
            for candidate in candidates
            if is_download_landing_page(candidate, official_url, official_domain)
        ][:6]
        if not landing_pages:
            return []

        async def fetch_landing(
            client: httpx.AsyncClient,
            landing: InstallerCandidate,
        ) -> list[InstallerCandidate]:
            """Recupera la operación `landing`.

            Args:
                client (httpx.AsyncClient): Cliente utilizado para ejecutar el escenario.
                landing (InstallerCandidate): Valor de `landing` utilizado por la operación.

            Returns:
                list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
            """
            try:
                response = await client.get(landing.url)
            except Exception:
                return []
            if not response.is_success:
                return []
            if "html" not in response.headers.get("content-type", "").lower():
                return []
            base_url = str(response.url)
            return [
                InstallerCandidate(
                    url=candidate.url,
                    source="official_download_page",
                    label=candidate.label,
                    context=candidate.context,
                    referer=base_url,
                )
                for candidate in extract_candidates(response.text, base_url)
            ]

        nested: list[InstallerCandidate] = []
        try:
            async with httpx.AsyncClient(
                timeout=self.settings.request_timeout_seconds,
                follow_redirects=True,
                headers={"User-Agent": "Mozilla/5.0 BatchDownloaderScraper/0.1"},
            ) as client:
                tasks = [
                    asyncio.create_task(fetch_landing(client, landing)) for landing in landing_pages
                ]
                landing_budget = min(15.0, self.settings.request_timeout_seconds + 2.0)
                done, pending = await asyncio.wait(tasks, timeout=landing_budget)
                for task in pending:
                    task.cancel()
                if pending:
                    await asyncio.gather(*pending, return_exceptions=True)
                for task in done:
                    nested.extend(task.result())
        except Exception:
            return []
        return dedupe_candidates(nested)

    async def _collect_winstall_github_candidates(
        self,
        app: WinstallApp,
        candidates: list[InstallerCandidate],
    ) -> list[InstallerCandidate]:
        """Añade candidatos GitHub encontrados en URLs o referencias de Winstall sin sustituir la
        procedencia original.

        Args:
            app: Aplicación Winstall normalizada.
            candidates: Candidatos que se recopilan, puntúan o validan.

        Returns:
            candidatos Winstall/GitHub.
        """
        refreshed = self._collect_winstall_official_referer_candidates(app, candidates)
        refreshed.extend(
            await collect_winstall_github_candidates(
                self.settings,
                self.github,
                candidates,
                app.latest_version,
            )
        )
        refreshed.extend(await self._collect_winstall_sourceforge_candidates(app, candidates))
        refreshed.extend(await self._collect_winstall_landing_candidates(app, candidates))
        refreshed.extend(await self._collect_winstall_parent_index_candidates(candidates))
        return dedupe_candidates(refreshed)

    def _collect_winstall_official_referer_candidates(
        self,
        app: WinstallApp,
        candidates: list[InstallerCandidate],
    ) -> list[InstallerCandidate]:
        """Explora páginas oficiales enlazadas por Winstall y marca sus candidatos con la
        referencia estable del proveedor.

        Args:
            app: Aplicación Winstall normalizada.
            candidates: Candidatos que se recopilan, puntúan o validan.

        Returns:
            candidatos de referer.
        """
        homepage = getattr(app, "homepage", None)
        homepage_domain = registered_domain(homepage) if homepage else None
        if not homepage or not homepage_domain or urlparse(homepage).scheme != "https":
            return []

        refreshed: list[InstallerCandidate] = []
        for candidate in dedupe_candidates(candidates):
            if (
                registered_domain(candidate.url) != homepage_domain
                or urlparse(candidate.url).scheme != "https"
                or candidate.referer == homepage
            ):
                continue
            refreshed.append(
                replace(
                    candidate,
                    source="winstall_official_referer",
                    referer=homepage,
                )
            )
        return refreshed

    async def _collect_winstall_landing_candidates(
        self,
        app: WinstallApp,
        candidates: list[InstallerCandidate],
    ) -> list[InstallerCandidate]:
        """Consulta páginas de descarga de Winstall y conserva solo enlaces con extensión o
        intención de descarga.

        Args:
            app: Aplicación Winstall normalizada.
            candidates: Candidatos que se recopilan, puntúan o validan.

        Returns:
            candidatos de landing Winstall.
        """
        web_extensions = {None, ".htm", ".html", ".php", ".asp", ".aspx"}
        landing_pages = [
            candidate
            for candidate in dedupe_candidates(candidates)
            if (
                candidate.asset_kind == "winstall_download"
                and urlparse(candidate.url).scheme == "https"
                and candidate.extension in web_extensions
            )
        ][:6]
        if not landing_pages:
            return []

        try:
            async with httpx.AsyncClient(
                timeout=self.settings.request_timeout_seconds,
                follow_redirects=True,
                headers={"User-Agent": "Mozilla/5.0 BatchDownloaderScraper/0.1"},
            ) as client:
                batches = await asyncio.gather(
                    *(
                        self._fetch_winstall_landing(
                            client, landing, getattr(app, "homepage", None)
                        )
                        for landing in landing_pages
                    ),
                    return_exceptions=True,
                )
        except Exception:
            return []

        refreshed: list[InstallerCandidate] = []
        for batch in batches:
            if isinstance(batch, list):
                refreshed.extend(batch)
        return dedupe_candidates(refreshed)

    async def _fetch_winstall_landing(
        self,
        client: httpx.AsyncClient,
        landing: InstallerCandidate,
        homepage: str | None,
    ) -> list[InstallerCandidate]:
        """Lee HTML limitado de una landing Winstall y devuelve su URL base y contenido para
        extraer enlaces.

        Args:
            client: Cliente HTTP usado para la página de landing.
            landing: Candidato que apunta a una página intermedia de descarga.
            homepage: Página de inicio que sirve de base para enlaces de Winstall.

        Returns:
            respuesta o None.
        """
        parsed = urlparse(landing.url)
        if not parsed.hostname or not await domain_has_public_dns(parsed.hostname):
            return []
        headers: dict[str, str] = {}
        if homepage and registered_domain(homepage) == registered_domain(landing.url):
            headers["Referer"] = homepage
        try:
            async with client.stream("GET", landing.url, headers=headers) as response:
                page = await read_limited_html(response)
                if page is None:
                    return []
                html, base_url = page
        except Exception:
            return []

        landing_domain = registered_domain(base_url)
        nested: list[InstallerCandidate] = []
        for item in extract_candidates(html, base_url):
            if registered_domain(item.url) != landing_domain:
                continue
            if not item.extension and not candidate_has_download_intent(item):
                continue
            nested.append(
                InstallerCandidate(
                    url=item.url,
                    source="winstall_download_page",
                    label=item.label or landing.label,
                    context=landing.context,
                    asset_kind="winstall_download",
                    match_tokens=landing.match_tokens,
                    referer=base_url,
                )
            )
            if len(nested) >= 200:
                break
        return nested

    async def _collect_winstall_sourceforge_candidates(
        self,
        app: WinstallApp,
        candidates: list[InstallerCandidate],
    ) -> list[InstallerCandidate]:
        """Resuelve con Playwright el mirror dinámico de SourceForge y conserva la URL estable de
        Winstall como Referer.

        Args:
            app: Aplicación Winstall normalizada.
            candidates: Candidatos que se recopilan, puntúan o validan.

        Returns:
            candidatos temporales listos para validar.
        """
        sourceforge_candidates = [
            candidate
            for candidate in dedupe_candidates(candidates)
            if is_sourceforge_download_url(candidate.url)
        ]
        if not sourceforge_candidates:
            return []

        latest = [
            candidate
            for candidate in sourceforge_candidates
            if candidate.context == app.latest_version
        ]
        selected = (latest or sourceforge_candidates)[:1]
        refreshed: list[InstallerCandidate] = []
        for stable_candidate in selected:
            try:
                timeout_seconds = max(
                    5.0,
                    self.settings.playwright_timeout_ms / 1000 + 2.0,
                )
                async with asyncio.timeout(timeout_seconds):
                    browser_candidates = await self.playwright.collect(stable_candidate.url)
            except Exception:
                continue
            for candidate in browser_candidates:
                if candidate.source != "playwright_data_release_url":
                    continue
                refreshed.append(
                    replace(
                        candidate,
                        label=stable_candidate.label,
                        context=stable_candidate.context,
                        asset_kind="winstall_download",
                        referer=stable_candidate.url,
                    )
                )
        return dedupe_candidates(refreshed)

    async def _collect_winstall_parent_index_candidates(
        self,
        candidates: list[InstallerCandidate],
    ) -> list[InstallerCandidate]:
        """Delega la exploración de índices padre a la política compartida de Winstall.

        Args:
            candidates: Candidatos que se recopilan, puntúan o validan.

        Returns:
            candidatos derivados del índice.
        """
        return await collect_winstall_parent_index_candidates(self.settings, candidates)

    async def _validate_installers(
        self,
        *,
        app: WinstallApp,
        official_url: str | None,
        direct_candidates: list[InstallerCandidate],
        fallback_candidates: list[InstallerCandidate],
    ) -> tuple[list[ValidInstaller], dict[str, dict[str, Any]]]:
        # Una página oficial lenta no debe privar a un fallback válido de Winstall.
        # Ambos grupos son rutas de confianza independientes y se validan en paralelo.
        """Valida simultáneamente candidatos directos y fallback y deduplica los instaladores
        aceptados.

        Args:
            app: Aplicación Winstall normalizada.
            official_url: Página oficial que sirve de origen o Referer.
            direct_candidates: Candidatos descubiertos en la web oficial.
            fallback_candidates: Candidatos aportados por Winstall o sus páginas.

        Returns:
            instaladores y diagnósticos por vía.
        """
        (direct, direct_diagnostics), (fallback, fallback_diagnostics) = await asyncio.gather(
            self._validate_candidate_group(
                app,
                direct_candidates,
                ResolutionStatus.DIRECT,
                max_candidates=96,
                max_valid=12,
            ),
            self._validate_candidate_group(
                app,
                fallback_candidates,
                ResolutionStatus.FALLBACK,
                max_candidates=48,
                max_valid=12,
            ),
        )
        return dedupe_valid_installers([*direct, *fallback]), {
            "direct": direct_diagnostics.as_metadata(),
            "fallback": fallback_diagnostics.as_metadata(),
        }

    async def _validate_candidate_group(
        self,
        app: WinstallApp,
        candidates: list[InstallerCandidate],
        status: ResolutionStatus,
        max_candidates: int,
        max_valid: int,
    ) -> tuple[list[ValidInstaller], CandidateValidationDiagnostics]:
        """Expande y puntúa un grupo, valida lotes bajo presupuesto temporal, cancela pendientes
        y devuelve solo el máximo publicable.

        Args:
            app: Aplicación Winstall normalizada.
            candidates: Candidatos que se recopilan, puntúan o validan.
            status: Vía de resolución que se asignará al instalador.
            max_candidates: Límite de candidatos que se intentarán validar.
            max_valid: Máximo de validaciones aceptadas del grupo.

        Returns:
            instaladores válidos y CandidateValidationDiagnostics.
        """
        diagnostics = CandidateValidationDiagnostics()
        candidates_to_validate = score_validation_candidates(
            app, candidates, max_candidates, diagnostics
        )
        valid: list[ValidInstaller] = []

        loop = asyncio.get_running_loop()
        budget_seconds = max(
            5.0,
            min(40.0, self.settings.scrape_app_timeout_seconds * 0.45),
        )
        deadline = loop.time() + budget_seconds
        batch_size = 4

        async def validate_one(candidate: InstallerCandidate):
            """Valida un candidato con timeout individual y conserva la excepción para el
            diagnóstico.

            Args:
                candidate: Candidato con URL, procedencia y contexto.

            Returns:
                candidato, resultado o error.
            """
            timeout_seconds = min(
                max(5.0, self.settings.request_timeout_seconds + 2.0),
                budget_seconds,
            )
            try:
                async with asyncio.timeout(timeout_seconds):
                    return candidate, await self.validator.validate(candidate), None
            except Exception as exc:
                return candidate, None, exc

        processed = 0
        for offset in range(0, len(candidates_to_validate), batch_size):
            remaining = deadline - loop.time()
            if remaining <= 0:
                break
            batch = candidates_to_validate[offset : offset + batch_size]
            diagnostics.attempted += len(batch)
            tasks = [asyncio.create_task(validate_one(candidate)) for candidate in batch]
            done, pending = await asyncio.wait(tasks, timeout=remaining)
            for task in pending:
                task.cancel()
            if pending:
                await asyncio.gather(*pending, return_exceptions=True)
                diagnostics.errors["ValidationBudgetExceeded"] = diagnostics.errors.get(
                    "ValidationBudgetExceeded", 0
                ) + len(pending)
            processed = offset + len(batch)

            record_validation_results(done, valid, status, diagnostics)

            publishable_count = sum(
                1 for installer in valid if is_catalog_publishable_installer(installer)
            )
            if pending or publishable_count >= max_valid:
                break

        unprocessed = max(0, len(candidates_to_validate) - processed)
        if unprocessed:
            diagnostics.skipped["validation_budget_exhausted"] = (
                diagnostics.skipped.get("validation_budget_exhausted", 0) + unprocessed
            )
        valid.sort(key=is_catalog_publishable_installer, reverse=True)
        return valid[:max_valid], diagnostics

    async def _save_valid_installers(
        self,
        catalog: CatalogRepository,
        logs: ResolverLogRepository,
        software_app_id: uuid.UUID,
        app: WinstallApp,
        official_url: str | None,
        installers: list[ValidInstaller],
    ) -> None:
        """Ordena instaladores por plataforma y versión, expira resoluciones anteriores y guarda
        URL, metadatos, estado y trazas en el catálogo.

        Args:
            catalog: Repositorio de catálogo de la sesión de guardado.
            logs: Repositorio de trazas de resolución.
            software_app_id: UUID de la aplicación que recibe instaladores.
            app: Aplicación Winstall normalizada.
            official_url: Página oficial que sirve de origen o Referer.
            installers: Instaladores aceptados para persistir.
        """
        ranked = rank_installers(installers, app.latest_version)
        if validated_installers_cover_latest_version(app.latest_version, installers):
            await catalog.winstall.promote_winstall_latest_version(software_app_id)
        expired_sources: set[uuid.UUID] = set()
        for installer, release_rank, is_latest in ranked:
            catalog_url = catalog_url_for_installer(installer)
            source = await catalog.sources.ensure_download_source(
                software_app_id=software_app_id,
                app=app,
                operating_system=installer.operating_system,
                architecture=installer.architecture,
                initial_url=official_url or app.homepage,
            )
            if source.id not in expired_sources:
                await catalog.sources.expire_valid_resolved_sources(source.id)
                expired_sources.add(source.id)
            await catalog.sources.save_resolved_source(
                ResolvedSourceCreate(
                    source_id=source.id,
                    url=catalog_url,
                    final_domain=registered_domain(catalog_url)
                    or installer.result.final_domain
                    or "",
                    filename=installer.result.filename,
                    extension=installer.result.extension,
                    content_type=installer.result.content_type,
                    size_bytes=installer.result.size_bytes,
                    version=installer.version,
                    score=installer.candidate.score,
                    status=installer.status,
                    validation_status=ValidationStatus.VALID,
                    release_rank=release_rank,
                    is_latest=is_latest,
                    version_status="latest" if is_latest else "previous",
                    metadata=resolved_metadata(installer, is_latest),
                )
            )
            await logs.add(
                phase="resolve",
                status=installer.status.value,
                download_source_id=source.id,
                safe_metadata={
                    "winstall_id": app.package_id,
                    "os": installer.operating_system,
                    "architecture": installer.architecture,
                    "version": installer.version,
                    "is_latest": is_latest,
                },
            )
        await catalog.sources.refresh_source_statuses(expired_sources)

    async def _refresh_missing_candidates(
        self,
        app: WinstallApp,
        fallback: list[InstallerCandidate],
        valid_installers: list[ValidInstaller],
        validation_diagnostics: dict,
        item_started_at: float,
    ) -> list[ValidInstaller]:
        """Si no existe ningún instalador publicable compatible, intenta una recopilación
        Winstall/GitHub adicional usando el tiempo restante.

        Args:
            app: Aplicación Winstall normalizada.
            fallback: Candidatos de respaldo que pueden recopilarse de nuevo.
            valid_installers: Instaladores ya validados que se pueden ampliar.
            validation_diagnostics: Mapa mutable con contadores y motivos de validación.
            item_started_at: Tiempo monotónico en que comenzó el trabajo.

        Returns:
            lista ampliada de instaladores.
        """
        if not any(
            is_catalog_publishable_installer(installer)
            and installer_app_compatibility_reason(app, installer) is None
            for installer in valid_installers
        ):
            elapsed = asyncio.get_running_loop().time() - item_started_at
            remaining = self.settings.scrape_app_timeout_seconds - elapsed - 5.0
            if remaining > 5.0:
                try:
                    async with asyncio.timeout(min(25.0, remaining)):
                        refreshed_fallback = await self._collect_winstall_github_candidates(
                            app,
                            fallback,
                        )
                        (
                            refreshed_valid,
                            refreshed_diagnostics,
                        ) = await self._validate_candidate_group(
                            app,
                            refreshed_fallback,
                            ResolutionStatus.FALLBACK,
                            max_candidates=48,
                            max_valid=12,
                        )
                except TimeoutError:
                    validation_diagnostics["fallback_refresh"] = {
                        "errors": {"RefreshBudgetExceeded": 1}
                    }
                else:
                    valid_installers = dedupe_valid_installers(
                        [*valid_installers, *refreshed_valid]
                    )
                    validation_diagnostics["fallback_refresh"] = refreshed_diagnostics.as_metadata()
        return valid_installers


async def count_scrape_outcome(runtime: PipelineRuntime, outcome: ScrapeOutcome) -> None:
    """Incrementa contadores específicos sin mezclar omitido, ausencia confirmada, revisión y
    fallo transitorio.

    Args:
        runtime: Estado compartido de la ejecución y sus workers.
        outcome: Resultado de negocio del scraping de una aplicación.
    """
    if outcome == ScrapeOutcome.RESOLVED:
        await runtime.increment("apps_resolved")
    elif outcome == ScrapeOutcome.CONFIRMED_MISSING:
        await runtime.increment("apps_confirmed_missing")
    elif outcome == ScrapeOutcome.NEEDS_REVIEW:
        await runtime.increment("apps_needs_review")
    elif outcome == ScrapeOutcome.TRANSIENT_FAILED:
        await runtime.increment("apps_failed")
        await runtime.increment("apps_transient_failed")
    elif outcome == ScrapeOutcome.SKIPPED_UNCHANGED:
        await runtime.increment("apps_skipped")
        await runtime.increment("apps_skipped_unchanged")


def publishable_app_installers(
    app: WinstallApp, valid_installers: list[ValidInstaller], validation_diagnostics: dict
) -> list[ValidInstaller]:
    """Elimina validaciones atestiguadas o incompatibles con la aplicación y registra los motivos
    de publicación.

    Args:
        app: Aplicación Winstall normalizada.
        valid_installers: Instaladores ya validados que se pueden ampliar.
        validation_diagnostics: Mapa mutable con contadores y motivos de validación.

    Returns:
        instaladores aptos para persistir.
    """
    observed_installers = valid_installers
    publishable_installers = [
        installer
        for installer in observed_installers
        if is_catalog_publishable_installer(installer)
    ]
    compatibility_rejections: dict[str, int] = {}
    valid_installers = []
    for installer in publishable_installers:
        reason = installer_app_compatibility_reason(app, installer)
        if reason is None:
            valid_installers.append(installer)
        else:
            compatibility_rejections[reason] = compatibility_rejections.get(reason, 0) + 1
    validation_diagnostics["publication"] = {
        "observed": len(observed_installers),
        "publishable": len(valid_installers),
        "attested_or_non_public": len(observed_installers) - len(publishable_installers),
        "incompatible": compatibility_rejections,
    }
    return valid_installers


def score_validation_candidates(
    app: WinstallApp,
    candidates: list[InstallerCandidate],
    max_candidates: int,
    diagnostics: CandidateValidationDiagnostics,
) -> list[InstallerCandidate]:
    """Deduplica y expande variantes, puntúa identidad y formato y descarta candidatos sin
    plataforma, intención o puntuación positiva.

    Args:
        app: Aplicación Winstall normalizada.
        candidates: Candidatos que se recopilan, puntúan o validan.
        max_candidates: Límite de candidatos que se intentarán validar.
        diagnostics: Diagnóstico mutable de la validación.

    Returns:
        candidatos acotados para validar.
    """
    scored = []
    expanded_candidates: list[InstallerCandidate] = []
    for candidate in dedupe_candidates(candidates):
        try:
            expanded_candidates.extend(candidate_variants(candidate))
        except (TypeError, ValueError) as exc:
            diagnostics.error(exc)
    for candidate in dedupe_candidates(expanded_candidates):
        diagnostics.discovered += 1
        try:
            scored_candidate = score_candidate(
                candidate,
                app_name=app.name,
                package_id=app.package_id,
                publisher=app.publisher,
                version=app.latest_version,
            )
            operating_system = infer_operating_system(candidate)
        except (TypeError, ValueError) as exc:
            diagnostics.error(exc)
            continue
        if (
            not operating_system
            and not is_windows_winstall_archive(scored_candidate)
            and not is_download_candidate(scored_candidate)
        ):
            diagnostics.skip("no_platform_or_download_intent")
            continue
        diagnostics.eligible += 1
        scored.append(scored_candidate)
    scored.sort(key=lambda candidate: candidate.score, reverse=True)

    candidates_to_validate = []
    for candidate in scored[:max_candidates]:
        if candidate.score <= 0:
            diagnostics.skip("non_positive_score")
            continue
        candidates_to_validate.append(candidate)
    return candidates_to_validate


def record_validation_results(
    done: set[asyncio.Task],
    valid: list[ValidInstaller],
    status: ResolutionStatus,
    diagnostics: CandidateValidationDiagnostics,
) -> None:
    """Convierte tareas terminadas en ValidInstaller, clasificando errores, rechazos y
    plataformas no resolubles en el diagnóstico.

    Args:
        done: Tareas de validación terminadas.
        valid: Lista mutable de instaladores válidos.
        status: Vía de resolución que se asignará al instalador.
        diagnostics: Diagnóstico mutable de la validación.
    """
    for task in done:
        candidate, result, error = task.result()
        if error is not None:
            diagnostics.error(error)
            continue
        if result is None or not result.ok:
            diagnostics.reject(result.reason if result else "unknown")
            continue
        operating_system = infer_validated_operating_system(candidate, result)
        if not operating_system:
            diagnostics.reject("operating_system_unresolved")
            continue
        valid.append(
            ValidInstaller(
                candidate=candidate,
                result=result,
                status=status,
                operating_system=operating_system,
                architecture=infer_architecture(candidate),
                version=validated_installer_version(candidate, result),
            )
        )
        diagnostics.valid += 1
