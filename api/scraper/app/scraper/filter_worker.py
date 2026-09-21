"""Filtra trabajos del buscador antes de activar resolución de instaladores."""

from __future__ import annotations

import asyncio
from typing import Any

import httpx
from sqlalchemy.exc import OperationalError
from sqlalchemy.exc import TimeoutError as SQLAlchemyTimeoutError

from app.core.config import Settings
from app.core.cpu_pool import run_cpu_bound
from app.core.logging import get_logger
from app.core.url_protector import UrlProtector
from app.repositories.catalog import CatalogRepository
from app.repositories.pipeline import (
    QUEUE_FILTER_SCRAPER,
    QUEUE_SEARCHER_FILTER,
    PipelineRepository,
)
from app.repositories.runs import worker_id
from app.scraper.candidates import (
    InstallerCandidate,
    registered_domain,
)
from app.scraper.github import GitHubReleaseResolver
from app.scraper.installer_policy import (
    dedupe_candidates,
    fallback_candidates,
    prepare_scored_candidates,
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
from app.scraper.validator import (
    DownloadValidator,
    domain_has_public_dns,
)
from app.scraper.winstall import (
    WinstallApp,
)
from app.scraper.winstall_candidates import (
    collect_winstall_github_candidates,
    collect_winstall_parent_index_candidates,
)
from app.scraper.worker_recovery import recover_worker_failure

logger = get_logger(__name__)



class FilterWorker:
    """Valida si una aplicación tiene al menos un candidato descargable antes de enviarla al
    scraper.
    """

    def __init__(self, settings: Settings) -> None:
        """Configura validador y resolutor GitHub para el filtro.

        Args:
            settings: Configuración del servicio y sus límites.
        """
        self.settings = settings

        self.worker_id = f"filter:{worker_id()}"

        self.validator = DownloadValidator(settings)

        self.github = GitHubReleaseResolver(settings)


    async def run(self, runtime: PipelineRuntime) -> None:
        """Consume la cola del buscador, clasifica errores de reserva y finaliza cuando el
        searcher termina sin trabajo activo.

        Args:
            runtime: Estado compartido del pipeline.
        """
        while not runtime.stop_event.is_set():
            if not await runtime.before_next_item():
                break
            try:
                item = await claim_item(
                    self.settings,
                    QUEUE_SEARCHER_FILTER,
                    self.worker_id,
                    run_id=runtime.run_id,
                )
            except (SQLAlchemyTimeoutError, OperationalError) as exc:
                logger.warning(
                    "filter_claim_retry",
                    worker_id=self.worker_id,
                    error=exc.__class__.__name__,
                    detail=exception_detail(exc),
                )
                await asyncio.sleep(0.5)
                continue
            except Exception as exc:
                logger.warning(
                    "filter_claim_retry",
                    worker_id=self.worker_id,
                    error=exc.__class__.__name__,
                    detail=exception_detail(exc),
                )
                await asyncio.sleep(1)
                continue
            if item is None:
                if runtime.searcher_done.is_set() and not await queue_has_active_work(
                    self.settings,
                    QUEUE_SEARCHER_FILTER,
                    runtime.run_id,
                ):
                    break
                await asyncio.sleep(1)
                continue
            try:
                payload = item.payload_json or {}
                package_id = payload_package_id(payload, item)
                app = parse_payload_app(payload, package_id)
                await set_current(
                    self.settings,
                    runtime.run_id,
                    app.package_id,
                    app.name,
                    "filter_validating_app",
                )
                async with async_session_local()() as session:
                    catalog = CatalogRepository(
                        session,
                        UrlProtector(self.settings.url_protection_secret),
                    )
                    if not await catalog.winstall.should_scrape_winstall_package(
                        app.package_id,
                        force_refresh=bool(payload.get("force_refresh")),
                    ):
                        await finish_item(self.settings, item, "discard", "already_exists")
                        await runtime.increment("apps_skipped")
                        await runtime.increment("apps_skipped_unchanged")
                        continue

                official_url = payload.get("official_url") or app.homepage
                official_valid = await self._official_page_valid(official_url)
                fallback_valid = False
                if not official_valid:
                    fallback_valid = await self._fallback_download_valid(payload, app)
                payload["filter"] = {
                    "official_valid": official_valid,
                    "fallback_valid": fallback_valid,
                    "use_official": official_valid,
                }
                async with async_session_local()() as session:
                    pipeline = PipelineRepository(session)
                    await pipeline.enqueue(
                        QUEUE_FILTER_SCRAPER,
                        app.package_id,
                        app.name,
                        payload,
                        runtime.run_id,
                    )
                    await session.commit()
                await finish_item(self.settings, item, "complete", None)
            except Exception as exc:
                await recover_worker_failure(self.settings, runtime, item, exc, "filter")
        runtime.filter_done.set()

    async def _official_page_valid(self, url: str | None) -> bool:
        """Consulta DNS público de la página oficial para descartar destinos claramente no
        accesibles.

        Args:
            url: URL oficial o recurso que se comprueba.

        Returns:
            True si el dominio resuelve y la respuesta es utilizable.
        """
        if not url:
            return False
        parsed_domain = registered_domain(url)
        if not parsed_domain:
            return False
        try:
            host = httpx.URL(url).host
        except Exception:
            return False
        if not await domain_has_public_dns(host):
            return False
        try:
            async with httpx.AsyncClient(
                timeout=self.settings.request_timeout_seconds,
                follow_redirects=True,
                headers={"User-Agent": "BatchDownloaderScraper/0.1"},
            ) as client:
                response = await client.get(url)
        except Exception:
            return False
        if response.status_code >= 400:
            return False
        content_type = response.headers.get("content-type", "").lower()
        return not content_type or "html" in content_type

    async def _fallback_download_valid(self, payload: dict[str, Any], app: WinstallApp) -> bool:
        """Puntúa candidatos Winstall y valida hasta 48 para comprobar que existe un binario
        descargable.

        Args:
            payload: Payload JSON asociado al trabajo.
            app: Aplicación Winstall utilizada para puntuar o completar candidatos.

        Returns:
            True al primer candidato validado.
        """
        candidates = fallback_candidates(payload, app)
        if await self._candidate_group_has_valid_download(app, candidates):
            return True
        refreshed = await self._collect_winstall_github_candidates(app, candidates)
        return await self._candidate_group_has_valid_download(app, refreshed)

    async def _candidate_group_has_valid_download(
        self,
        app: WinstallApp,
        candidates: list[InstallerCandidate],
    ) -> bool:
        """Expande, puntúa y valida un grupo de candidatos sin modificar el catálogo.

        Args:
            app: Aplicación Winstall utilizada para puntuar o completar candidatos.
            candidates: Candidatos que se validan o enriquecen.

        Returns:
            True si algún candidato es aceptable.
        """
        scored = await run_cpu_bound(
            prepare_scored_candidates,
            candidates,
            app.name,
            app.package_id,
            app.publisher,
            app.latest_version,
        )
        for candidate in scored[:48]:
            if candidate.score <= 0:
                continue
            try:
                result = await self.validator.validate(candidate)
            except Exception:
                continue
            if result.ok:
                return True
        return False

    async def _collect_winstall_github_candidates(
        self,
        app: WinstallApp,
        candidates: list[InstallerCandidate],
    ) -> list[InstallerCandidate]:
        """Completa fallback con releases GitHub y con índices padres de Winstall.

        Args:
            app: Aplicación Winstall utilizada para puntuar o completar candidatos.
            candidates: Candidatos que se validan o enriquecen.

        Returns:
            candidatos deduplicados.
        """
        refreshed: list[InstallerCandidate] = []
        refreshed.extend(
            await collect_winstall_github_candidates(
                self.settings,
                self.github,
                candidates,
                app.latest_version,
            )
        )
        refreshed.extend(await self._collect_winstall_parent_index_candidates(candidates))
        return dedupe_candidates(refreshed)

    async def _collect_winstall_parent_index_candidates(
        self,
        candidates: list[InstallerCandidate],
    ) -> list[InstallerCandidate]:
        """Delega la exploración de índices padre al colector compartido de Winstall.

        Args:
            candidates: Candidatos que se validan o enriquecen.

        Returns:
            candidatos derivados.
        """
        return await collect_winstall_parent_index_candidates(self.settings, candidates)
