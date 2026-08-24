"""Filtrado de aplicaciones antes de su resolución de instaladores."""

from __future__ import annotations

import asyncio
from typing import Any

import httpx

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
from app.scraper.github import GitHubReleaseResolver, parse_github_repo
from app.scraper.installer_policy import (
    dedupe_candidates,
    fallback_candidates,
    github_collection_timeout_seconds,
    prepare_scored_candidates,
)
from app.scraper.pipeline_runtime import (
    PipelineRuntime,
    async_session_local,
)
from app.scraper.pipeline_support import (
    claim_item,
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
from app.scraper.winstall_candidates import collect_winstall_parent_index_candidates

logger = get_logger(__name__)
"""Estado global asociado a `logger`.
"""


class FilterWorker:
    """Ejecuta el procesamiento en segundo plano de `Filter`."""

    def __init__(self, settings: Settings) -> None:
        """Inicializa una instancia de `FilterWorker`.

        Args:
            settings (Settings): Configuración del servicio.
        """
        self.settings = settings
        """Estado de instancia asociado a `settings`.
        """
        self.worker_id = f"filter:{worker_id()}"
        """Estado de instancia asociado a `worker_id`.
        """
        self.validator = DownloadValidator(settings)
        """Estado de instancia asociado a `validator`.
        """
        self.github = GitHubReleaseResolver(settings)
        """Estado de instancia asociado a `github`.
        """

    async def run(self, runtime: PipelineRuntime) -> None:
        """Ejecuta `run` dentro de `FilterWorker`.

        Args:
            runtime (PipelineRuntime): Valor de `runtime` utilizado por la operación.
        """
        while not runtime.stop_event.is_set():
            if not await runtime.before_next_item():
                break
            item = await claim_item(
                self.settings,
                QUEUE_SEARCHER_FILTER,
                self.worker_id,
                run_id=runtime.run_id,
            )
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
                    if not await catalog.should_scrape_winstall_package(
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
                    await pipeline.save_snapshot(
                        run_id=runtime.run_id,
                        worker_id=self.worker_id,
                        stage="filter",
                        package_id=app.package_id,
                        app_name=app.name,
                        url=official_url,
                        html=None,
                    )
                    await session.commit()
                await finish_item(self.settings, item, "complete", None)
            except Exception as exc:
                await finish_item(self.settings, item, "fail", exc.__class__.__name__)
                await runtime.increment("apps_failed")
                await runtime.increment("apps_transient_failed")
                logger.warning(
                    "filter_app_failed",
                    winstall_id=item.package_id,
                    error=exc.__class__.__name__,
                )
        runtime.filter_done.set()

    async def _official_page_valid(self, url: str | None) -> bool:
        """Ejecuta el paso interno `_official_page_valid`.

        Args:
            url (str | None): URL del recurso que debe procesarse.

        Returns:
            bool: Indica si se cumple la condición evaluada.
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
        """Ejecuta el paso interno `_fallback_download_valid`.

        Args:
            payload (dict[str, Any]): Carga de datos recibida por la operación.
            app (WinstallApp): Aplicación sobre la que se realiza la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
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
        """Ejecuta el paso interno `_candidate_group_has_valid_download`.

        Args:
            app (WinstallApp): Aplicación sobre la que se realiza la operación.
            candidates (list[InstallerCandidate]): Valor de `candidates` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
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
        """Ejecuta el paso interno `_collect_winstall_github_candidates`.

        Args:
            app (WinstallApp): Aplicación sobre la que se realiza la operación.
            candidates (list[InstallerCandidate]): Valor de `candidates` utilizado por la operación.

        Returns:
            list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
        """
        refreshed: list[InstallerCandidate] = []
        seen_repositories: set[tuple[str, str]] = set()
        for candidate in candidates:
            repo = parse_github_repo(candidate.url)
            if not repo:
                continue
            repo_key = (repo.owner.lower(), repo.name.lower())
            if repo_key in seen_repositories:
                continue
            seen_repositories.add(repo_key)
            try:
                async with asyncio.timeout(github_collection_timeout_seconds(self.settings)):
                    release_candidates = await self.github.collect(
                        candidate.url,
                        app.latest_version,
                    )
            except Exception:
                continue
            for release_candidate in release_candidates:
                refreshed.append(
                    InstallerCandidate(
                        url=release_candidate.url,
                        source=f"winstall_{release_candidate.source}",
                        label=release_candidate.label or candidate.label,
                        context=release_candidate.context or candidate.context,
                        asset_kind=release_candidate.asset_kind or candidate.asset_kind,
                        referer=candidate.referer,
                    )
                )
        refreshed.extend(await self._collect_winstall_parent_index_candidates(candidates))
        return dedupe_candidates(refreshed)

    async def _collect_winstall_parent_index_candidates(
        self,
        candidates: list[InstallerCandidate],
    ) -> list[InstallerCandidate]:
        """Explora índices padres mediante la política compartida de Winstall."""
        return await collect_winstall_parent_index_candidates(self.settings, candidates)
