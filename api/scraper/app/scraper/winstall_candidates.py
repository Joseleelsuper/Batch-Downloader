"""Expansión segura de candidatos publicados en índices de Winstall."""

from __future__ import annotations

import asyncio
from urllib.parse import urlparse

import httpx

from app.core.config import Settings
from app.scraper.candidates import InstallerCandidate, extract_candidates, registered_domain
from app.scraper.installer_policy import dedupe_candidates, winstall_parent_index_url
from app.scraper.validator import domain_has_public_dns


async def collect_winstall_parent_index_candidates(
    settings: Settings,
    candidates: list[InstallerCandidate],
) -> list[InstallerCandidate]:
    """Explora índices padres públicos y devuelve instaladores del mismo dominio."""
    parent_pages: dict[str, InstallerCandidate] = {}
    for candidate in dedupe_candidates(candidates):
        parent_url = winstall_parent_index_url(candidate.url)
        if parent_url:
            parent_pages.setdefault(parent_url, candidate)
        if len(parent_pages) >= 6:
            break
    if not parent_pages:
        return []

    async def fetch_parent(
        client: httpx.AsyncClient,
        parent_url: str,
    ) -> list[InstallerCandidate]:
        """Recupera un índice padre aplicando límites de red y tamaño."""
        parsed = urlparse(parent_url)
        if not await domain_has_public_dns(parsed.hostname):
            return []
        try:
            async with client.stream("GET", parent_url) as response:
                if not response.is_success:
                    return []
                if "html" not in response.headers.get("content-type", "").lower():
                    return []
                content = bytearray()
                async for chunk in response.aiter_bytes():
                    remaining = 1_000_000 - len(content)
                    if remaining <= 0:
                        break
                    content.extend(chunk[:remaining])
                html = bytes(content).decode("utf-8", errors="ignore")
                base_url = str(response.url)
        except Exception:
            return []

        parent_domain = registered_domain(base_url)
        refreshed: list[InstallerCandidate] = []
        for item in extract_candidates(html, base_url):
            if not item.extension or registered_domain(item.url) != parent_domain:
                continue
            refreshed.append(
                InstallerCandidate(
                    url=item.url,
                    source="winstall_parent_index",
                    label=item.label,
                    context=item.context,
                    asset_kind="winstall_download",
                    referer=base_url,
                )
            )
            if len(refreshed) >= 500:
                break
        return refreshed

    try:
        async with httpx.AsyncClient(
            timeout=settings.request_timeout_seconds,
            follow_redirects=False,
            headers={"User-Agent": "Mozilla/5.0 BatchDownloaderScraper/0.1"},
        ) as client:
            batches = await asyncio.gather(
                *(fetch_parent(client, parent_url) for parent_url in parent_pages),
                return_exceptions=True,
            )
    except Exception:
        return []

    refreshed: list[InstallerCandidate] = []
    for batch in batches:
        if isinstance(batch, list):
            refreshed.extend(batch)
    return dedupe_candidates(refreshed)
