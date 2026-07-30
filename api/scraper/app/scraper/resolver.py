"""Implementa las responsabilidades del módulo `resolver`.
"""
from __future__ import annotations

import uuid

import httpx

from app.core.config import Settings
from app.core.cpu_pool import run_cpu_bound
from app.db.enums import ResolutionStatus, ValidationStatus
from app.db.models import DownloadSource
from app.repositories.catalog import CatalogRepository, ResolvedSourceCreate
from app.repositories.logs import ResolverLogRepository
from app.scraper.candidates import (
    InstallerCandidate,
    extract_candidates,
    registered_domain,
    score_candidate,
)
from app.scraper.github import GitHubReleaseResolver, parse_github_repo
from app.scraper.playwright_fallback import PlaywrightCandidateCollector
from app.scraper.strategies import CallbackResolverStrategy, ResolverStrategyRegistry
from app.scraper.validator import DownloadValidator, ValidationResult
from app.scraper.winstall import WinstallApp, WinstallClient


class InstallerResolver:
    """Representa el componente `InstallerResolver`.
    """
    def __init__(
        self,
        settings: Settings,
        catalog: CatalogRepository,
        logs: ResolverLogRepository,
        validator: DownloadValidator,
        strategies: ResolverStrategyRegistry | None = None,
    ) -> None:
        """Inicializa una instancia de `InstallerResolver`.

        Args:
            settings (Settings): Configuración del servicio.
            catalog (CatalogRepository): Valor de `catalog` utilizado por la operación.
            logs (ResolverLogRepository): Valor de `logs` utilizado por la operación.
            validator (DownloadValidator): Valor de `validator` utilizado por la operación.
            strategies (ResolverStrategyRegistry | None): Valor de `strategies` utilizado por la
                operación.
        """
        self.settings = settings
        """Estado de instancia asociado a `settings`.
        """
        self.catalog = catalog
        """Estado de instancia asociado a `catalog`.
        """
        self.logs = logs
        """Estado de instancia asociado a `logs`.
        """
        self.validator = validator
        """Estado de instancia asociado a `validator`.
        """
        self.playwright = PlaywrightCandidateCollector(settings)
        """Estado de instancia asociado a `playwright`.
        """
        self.github = GitHubReleaseResolver(settings)
        """Estado de instancia asociado a `github`.
        """
        self.strategies = strategies or ResolverStrategyRegistry(
            (
                CallbackResolverStrategy(
                    name="github_releases",
                    predicate=lambda url: parse_github_repo(url) is not None,
                    callback=self._resolve_github_releases,
                ),
                CallbackResolverStrategy(
                    name="html_playwright",
                    predicate=lambda _url: True,
                    callback=self._resolve_official_page,
                ),
            )
        )
        """Estado de instancia asociado a `strategies`.
        """

    async def resolve(self, source: DownloadSource, app: WinstallApp) -> ResolutionStatus:
        """Ejecuta `resolve` dentro de `InstallerResolver`.

        Args:
            source (DownloadSource): Fuente de descarga sobre la que se actúa.
            app (WinstallApp): Aplicación sobre la que se realiza la operación.

        Returns:
            ResolutionStatus: Resultado producido por la operación.
        """
        official_url = source.initial_url or app.homepage
        await self.catalog.expire_valid_resolved_sources(source.id)

        if official_url:
            strategy = self.strategies.find(official_url)
            if strategy:
                direct_status = await strategy.resolve(
                    source.id,
                    official_url,
                    app,
                )
                if direct_status == ResolutionStatus.DIRECT:
                    return direct_status

        fallback_status = await self._resolve_winstall_fallback(source.id, app)
        if fallback_status == ResolutionStatus.FALLBACK:
            return fallback_status

        final_status = (
            ResolutionStatus.REQUIRES_MANUAL_REVIEW
            if official_url
            else ResolutionStatus.MISSING
        )
        await self.catalog.mark_source_status(source.id, final_status, ValidationStatus.UNCHECKED)
        await self.logs.add(
            phase="resolve",
            status=final_status.value,
            download_source_id=source.id,
            message="No safe installer candidate was found.",
            safe_metadata={"winstall_id": app.package_id},
        )
        return final_status

    async def _resolve_official_page(
        self,
        source_id: uuid.UUID,
        official_url: str,
        app: WinstallApp,
    ) -> ResolutionStatus:
        """Ejecuta el paso interno `_resolve_official_page`.

        Args:
            source_id (uuid.UUID): Identificador de `source` utilizado por la operación.
            official_url (str): Dirección de `official` que debe procesarse.
            app (WinstallApp): Aplicación sobre la que se realiza la operación.

        Returns:
            ResolutionStatus: Resultado producido por la operación.
        """
        try:
            html = await self._fetch_html(official_url)
        except Exception as exc:
            await self.logs.add(
                phase="official_http",
                status="failed",
                download_source_id=source_id,
                message=exc.__class__.__name__,
            )
            html = None

        candidates: list[InstallerCandidate] = []
        if html:
            candidates.extend(
                await run_cpu_bound(extract_candidates, html, official_url)
            )
            await self.logs.add(
                phase="official_http",
                status="candidates",
                download_source_id=source_id,
                safe_metadata={"count": len(candidates)},
            )

        valid = await self._validate_candidates(
            source_id=source_id,
            candidates=candidates,
            status=ResolutionStatus.DIRECT,
            app=app,
        )
        if valid:
            return ResolutionStatus.DIRECT

        try:
            playwright_candidates = await self.playwright.collect(official_url)
        except Exception as exc:
            await self.logs.add(
                phase="official_playwright",
                status="failed",
                download_source_id=source_id,
                message=exc.__class__.__name__,
                safe_metadata={"domain": registered_domain(official_url)},
            )
            playwright_candidates = []
        if playwright_candidates:
            await self.logs.add(
                phase="official_playwright",
                status="candidates",
                download_source_id=source_id,
                safe_metadata={"count": len(playwright_candidates)},
            )
            valid = await self._validate_candidates(
                source_id=source_id,
                candidates=playwright_candidates,
                status=ResolutionStatus.DIRECT,
                app=app,
            )
            if valid:
                return ResolutionStatus.DIRECT

        return ResolutionStatus.REQUIRES_MANUAL_REVIEW

    async def _resolve_github_releases(
        self,
        source_id: uuid.UUID,
        official_url: str,
        app: WinstallApp,
    ) -> ResolutionStatus:
        """Ejecuta el paso interno `_resolve_github_releases`.

        Args:
            source_id (uuid.UUID): Identificador de `source` utilizado por la operación.
            official_url (str): Dirección de `official` que debe procesarse.
            app (WinstallApp): Aplicación sobre la que se realiza la operación.

        Returns:
            ResolutionStatus: Resultado producido por la operación.
        """
        if not parse_github_repo(official_url):
            return ResolutionStatus.REQUIRES_MANUAL_REVIEW

        try:
            candidates = await self.github.collect(official_url, app.latest_version)
        except Exception as exc:
            await self.logs.add(
                phase="github_releases",
                status="failed",
                download_source_id=source_id,
                message=exc.__class__.__name__,
            )
            return ResolutionStatus.REQUIRES_MANUAL_REVIEW

        if candidates:
            await self.logs.add(
                phase="github_releases",
                status="candidates",
                download_source_id=source_id,
                safe_metadata={"count": len(candidates)},
            )
        valid = await self._validate_candidates(
            source_id=source_id,
            candidates=candidates,
            status=ResolutionStatus.DIRECT,
            app=app,
        )
        return ResolutionStatus.DIRECT if valid else ResolutionStatus.REQUIRES_MANUAL_REVIEW

    async def _resolve_winstall_fallback(
        self,
        source_id: uuid.UUID,
        app: WinstallApp,
    ) -> ResolutionStatus:
        """Ejecuta el paso interno `_resolve_winstall_fallback`.

        Args:
            source_id (uuid.UUID): Identificador de `source` utilizado por la operación.
            app (WinstallApp): Aplicación sobre la que se realiza la operación.

        Returns:
            ResolutionStatus: Resultado producido por la operación.
        """
        fallback_candidates = []
        for version in app.versions:
            for url in version.installers:
                fallback_candidates.append(
                    InstallerCandidate(
                        url=url,
                        source="winstall_api",
                        label=f"{app.name} {version.installer_type or ''}".strip(),
                        context=version.version,
                        asset_kind="winstall_download",
                    )
                )

        try:
            async with WinstallClient(self.settings) as winstall:
                page_downloads = await winstall.get_downloads(app.package_id)
        except Exception as exc:
            await self.logs.add(
                phase="winstall_page",
                status="failed",
                download_source_id=source_id,
                message=exc.__class__.__name__,
                safe_metadata={"winstall_id": app.package_id},
            )
            page_downloads = []

        for download in page_downloads:
            fallback_candidates.append(
                InstallerCandidate(
                    url=download.url,
                    source="winstall_page",
                    label=download.label or app.name,
                    context=download.context,
                    asset_kind="winstall_download",
                )
            )

        fallback_candidates.extend(
            await self._latest_github_candidates_from_winstall(
                source_id,
                fallback_candidates,
            )
        )

        valid = await self._validate_candidates(
            source_id=source_id,
            candidates=fallback_candidates,
            status=ResolutionStatus.FALLBACK,
            app=app,
        )
        return ResolutionStatus.FALLBACK if valid else ResolutionStatus.REQUIRES_MANUAL_REVIEW

    async def _latest_github_candidates_from_winstall(
        self,
        source_id: uuid.UUID,
        candidates: list[InstallerCandidate],
    ) -> list[InstallerCandidate]:
        """Ejecuta el paso interno `_latest_github_candidates_from_winstall`.

        Args:
            source_id (uuid.UUID): Identificador de `source` utilizado por la operación.
            candidates (list[InstallerCandidate]): Valor de `candidates` utilizado por la operación.

        Returns:
            list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
        """
        repos = {}
        for candidate in candidates:
            if candidate.source not in {"winstall_api", "winstall_page"}:
                continue
            repo = parse_github_repo(candidate.url)
            if repo:
                repos[(repo.owner, repo.name)] = repo
        latest_candidates: list[InstallerCandidate] = []
        for repo in repos.values():
            try:
                collected = await self.github.collect(f"https://github.com/{repo.owner}/{repo.name}")
            except Exception as exc:
                await self.logs.add(
                    phase="winstall_github_latest",
                    status="failed",
                    download_source_id=source_id,
                    message=exc.__class__.__name__,
                    safe_metadata={"owner": repo.owner, "repo": repo.name},
                )
                continue
            latest_candidates.extend(
                InstallerCandidate(
                    url=candidate.url,
                    source=f"winstall_{candidate.source}",
                    label=candidate.label,
                    context=candidate.context,
                    asset_kind=candidate.asset_kind or "winstall_download",
                )
                for candidate in collected
            )
        if latest_candidates:
            await self.logs.add(
                phase="winstall_github_latest",
                status="candidates",
                download_source_id=source_id,
                safe_metadata={"count": len(latest_candidates), "repos": len(repos)},
            )
        return latest_candidates

    async def _validate_candidates(
        self,
        source_id: uuid.UUID,
        candidates: list[InstallerCandidate],
        status: ResolutionStatus,
        app: WinstallApp,
    ) -> bool:
        """Ejecuta el paso interno `_validate_candidates`.

        Args:
            source_id (uuid.UUID): Identificador de `source` utilizado por la operación.
            candidates (list[InstallerCandidate]): Valor de `candidates` utilizado por la operación.
            status (ResolutionStatus): Valor de `status` utilizado por la operación.
            app (WinstallApp): Aplicación sobre la que se realiza la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        scored = await run_cpu_bound(
            score_and_dedupe_candidates,
            candidates,
            app.name,
            app.package_id,
            app.publisher,
            app.latest_version,
        )
        valid_results: list[tuple[InstallerCandidate, ValidationResult]] = []
        for candidate in scored[:24]:
            if candidate.score <= 0:
                continue
            try:
                result = await self.validator.validate(candidate)
            except Exception as exc:
                await self.logs.add(
                    phase="validate",
                    status="failed",
                    download_source_id=source_id,
                    message=exc.__class__.__name__,
                    safe_metadata={
                        "score": candidate.score,
                        "domain": registered_domain(candidate.url),
                        "source": candidate.source,
                    },
                )
                continue
            if result.ok:
                valid_results.append((candidate, result))
                if len(valid_results) >= 5:
                    break
                continue
            await self.logs.add(
                phase="validate",
                status="rejected",
                download_source_id=source_id,
                safe_metadata={
                    "reason": result.reason,
                    "score": candidate.score,
                    "domain": registered_domain(candidate.url),
                    "source": candidate.source,
                    "asset_kind": candidate.asset_kind,
                },
            )
        for index, (candidate, result) in enumerate(valid_results):
            await self._save_valid_candidate(
                source_id,
                candidate,
                result,
                status,
                app.latest_version,
                is_primary=index == 0,
            )
        return bool(valid_results)

    async def _save_valid_candidate(
        self,
        source_id: uuid.UUID,
        candidate: InstallerCandidate,
        result: ValidationResult,
        status: ResolutionStatus,
        version: str | None,
        is_primary: bool,
    ) -> None:
        """Ejecuta el paso interno `_save_valid_candidate`.

        Args:
            source_id (uuid.UUID): Identificador de `source` utilizado por la operación.
            candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.
            result (ValidationResult): Resultado que debe procesarse.
            status (ResolutionStatus): Valor de `status` utilizado por la operación.
            version (str | None): Valor de `version` utilizado por la operación.
            is_primary (bool): Valor de `is_primary` utilizado por la operación.
        """
        await self.catalog.save_resolved_source(
            ResolvedSourceCreate(
                source_id=source_id,
                url=result.final_url or candidate.url,
                final_domain=(
                    result.final_domain
                    or registered_domain(result.final_url or candidate.url)
                    or ""
                ),
                filename=result.filename,
                extension=result.extension,
                content_type=result.content_type,
                size_bytes=result.size_bytes,
                version=version,
                score=candidate.score,
                status=status,
                validation_status=ValidationStatus.VALID,
                metadata=resolved_metadata(candidate, result, is_primary),
            )
        )
        await self.logs.add(
            phase="resolve",
            status=status.value,
            download_source_id=source_id,
            safe_metadata={
                "score": candidate.score,
                "domain": result.final_domain,
                "extension": result.extension,
                "asset_kind": candidate.asset_kind,
                "is_primary": is_primary,
            },
        )

    async def _fetch_html(self, url: str) -> str:
        """Ejecuta el paso interno `_fetch_html`.

        Args:
            url (str): URL del recurso que debe procesarse.

        Returns:
            str: Resultado producido por la operación.
        """
        async with httpx.AsyncClient(
            timeout=self.settings.request_timeout_seconds,
            follow_redirects=True,
            headers={"User-Agent": "BatchDownloaderScraper/0.1"},
        ) as client:
            response = await client.get(url)
            response.raise_for_status()
            content_type = response.headers.get("content-type", "")
            if "html" not in content_type and content_type:
                return ""
            return response.text


def score_and_dedupe_candidates(
    candidates: list[InstallerCandidate],
    app_name: str | None,
    package_id: str | None,
    publisher: str | None,
    version: str | None,
) -> list[InstallerCandidate]:
    """Ejecuta la operación `score_and_dedupe_candidates`.

    Args:
        candidates (list[InstallerCandidate]): Valor de `candidates` utilizado por la operación.
        app_name (str | None): Valor de `app_name` utilizado por la operación.
        package_id (str | None): Identificador de `package` utilizado por la operación.
        publisher (str | None): Valor de `publisher` utilizado por la operación.
        version (str | None): Valor de `version` utilizado por la operación.

    Returns:
        list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
    """
    deduped = {candidate.url: candidate for candidate in candidates if candidate.url}
    return sorted(
        (
            score_candidate(
                candidate,
                app_name=app_name,
                package_id=package_id,
                publisher=publisher,
                version=version,
            )
            for candidate in deduped.values()
        ),
        key=lambda candidate: candidate.score,
        reverse=True,
    )


def resolved_metadata(
    candidate: InstallerCandidate,
    result: ValidationResult,
    is_primary: bool,
) -> dict:
    """Ejecuta la operación `resolved_metadata`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.
        result (ValidationResult): Resultado que debe procesarse.
        is_primary (bool): Valor de `is_primary` utilizado por la operación.

    Returns:
        dict: Mapa con los datos producidos por la operación.
    """
    metadata = {
        "candidate_source": candidate.source,
        "candidate_label": candidate.label,
        "match_tokens": list(candidate.match_tokens),
        "is_primary": is_primary,
        "asset_kind": candidate.asset_kind or "installer",
        "validation_confidence": result.confidence.value,
    }
    if result.transport_security:
        metadata["transport_security"] = result.transport_security
    return metadata
