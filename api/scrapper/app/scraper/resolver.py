from __future__ import annotations

import uuid

import httpx

from app.core.config import Settings
from app.db.enums import ResolutionStatus, ValidationStatus
from app.db.models import DownloadSource
from app.repositories.catalog import CatalogRepository, ResolvedSourceCreate
from app.repositories.logs import ResolverLogRepository
from app.scraper.candidates import InstallerCandidate, extract_candidates, registered_domain, score_candidate
from app.scraper.playwright_fallback import PlaywrightCandidateCollector
from app.scraper.validator import DownloadValidator, ValidationResult
from app.scraper.winstall import WinstallApp


class InstallerResolver:
    def __init__(
        self,
        settings: Settings,
        catalog: CatalogRepository,
        logs: ResolverLogRepository,
        validator: DownloadValidator,
    ) -> None:
        self.settings = settings
        self.catalog = catalog
        self.logs = logs
        self.validator = validator
        self.playwright = PlaywrightCandidateCollector(settings)

    async def resolve(self, source: DownloadSource, app: WinstallApp) -> ResolutionStatus:
        allowed_domains = {domain.domain for domain in source.allowed_domains}
        official_url = source.initial_url or app.homepage

        if official_url:
            direct_status = await self._resolve_official_page(
                source.id,
                official_url,
                allowed_domains,
                app.latest_version,
            )
            if direct_status == ResolutionStatus.DIRECT:
                return direct_status

        fallback_status = await self._resolve_winstall_fallback(source.id, app, allowed_domains)
        if fallback_status == ResolutionStatus.FALLBACK:
            return fallback_status

        final_status = ResolutionStatus.REQUIRES_MANUAL_REVIEW if official_url else ResolutionStatus.MISSING
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
        allowed_domains: set[str],
        version: str | None,
    ) -> ResolutionStatus:
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
            candidates.extend(extract_candidates(html, official_url))
            await self.logs.add(
                phase="official_http",
                status="candidates",
                download_source_id=source_id,
                safe_metadata={"count": len(candidates)},
            )

        valid = await self._validate_candidates(
            source_id=source_id,
            candidates=candidates,
            allowed_domains=allowed_domains,
            status=ResolutionStatus.DIRECT,
            version=version,
        )
        if valid:
            return ResolutionStatus.DIRECT

        playwright_candidates = await self.playwright.collect(official_url)
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
                allowed_domains=allowed_domains,
                status=ResolutionStatus.DIRECT,
                version=version,
            )
            if valid:
                return ResolutionStatus.DIRECT

        return ResolutionStatus.REQUIRES_MANUAL_REVIEW

    async def _resolve_winstall_fallback(
        self,
        source_id: uuid.UUID,
        app: WinstallApp,
        allowed_domains: set[str],
    ) -> ResolutionStatus:
        fallback_candidates = [
            InstallerCandidate(url=url, source="winstall_fallback", label=app.name)
            for url in app.installer_urls
        ]
        fallback_allowed = set(allowed_domains)
        for candidate in fallback_candidates:
            domain = registered_domain(candidate.url)
            if domain:
                fallback_allowed.add(domain)

        valid = await self._validate_candidates(
            source_id=source_id,
            candidates=fallback_candidates,
            allowed_domains=fallback_allowed,
            status=ResolutionStatus.FALLBACK,
            version=app.latest_version,
        )
        return ResolutionStatus.FALLBACK if valid else ResolutionStatus.REQUIRES_MANUAL_REVIEW

    async def _validate_candidates(
        self,
        source_id: uuid.UUID,
        candidates: list[InstallerCandidate],
        allowed_domains: set[str],
        status: ResolutionStatus,
        version: str | None,
    ) -> bool:
        scored = sorted(
            (
                score_candidate(
                    candidate,
                    allowed_domains=allowed_domains,
                    preferred_os=self.settings.preferred_operating_system,
                    preferred_architecture=self.settings.preferred_architecture,
                )
                for candidate in candidates
            ),
            key=lambda candidate: candidate.score,
            reverse=True,
        )
        for candidate in scored[:12]:
            if candidate.score <= 0:
                continue
            try:
                result = await self.validator.validate(candidate, allowed_domains)
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
                await self._save_valid_candidate(source_id, candidate, result, status, version)
                return True
            await self.logs.add(
                phase="validate",
                status="rejected",
                download_source_id=source_id,
                safe_metadata={
                    "reason": result.reason,
                    "score": candidate.score,
                    "domain": registered_domain(candidate.url),
                    "source": candidate.source,
                },
            )
        return False

    async def _save_valid_candidate(
        self,
        source_id: uuid.UUID,
        candidate: InstallerCandidate,
        result: ValidationResult,
        status: ResolutionStatus,
        version: str | None,
    ) -> None:
        await self.catalog.save_resolved_source(
            ResolvedSourceCreate(
                source_id=source_id,
                url=result.final_url or candidate.url,
                final_domain=result.final_domain or registered_domain(result.final_url or candidate.url) or "",
                filename=result.filename,
                extension=result.extension,
                content_type=result.content_type,
                size_bytes=result.size_bytes,
                version=version,
                score=candidate.score,
                status=status,
                validation_status=ValidationStatus.VALID,
                metadata={"candidate_source": candidate.source},
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
            },
        )

    async def _fetch_html(self, url: str) -> str:
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
