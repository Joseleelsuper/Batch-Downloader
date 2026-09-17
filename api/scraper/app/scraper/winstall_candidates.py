"""Expansión segura de candidatos publicados en índices de Winstall."""

from __future__ import annotations

import asyncio
from urllib.parse import urlparse

import httpx

from app.core.config import Settings
from app.scraper.candidates import InstallerCandidate, extract_candidates, registered_domain
from app.scraper.github import GitHubReleaseResolver, parse_github_repo
from app.scraper.installer_policy import (
    dedupe_candidates,
    github_collection_timeout_seconds,
    winstall_parent_index_url,
)
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


    try:
        async with httpx.AsyncClient(
            timeout=settings.request_timeout_seconds,
            follow_redirects=False,
            headers={"User-Agent": "Mozilla/5.0 BatchDownloaderScraper/0.1"},
        ) as client:
            batches = await asyncio.gather(
                *(_fetch_parent(client, parent_url) for parent_url in parent_pages),
                return_exceptions=True,
            )
    except Exception:
        return []

    refreshed: list[InstallerCandidate] = []
    for batch in batches:
        if isinstance(batch, list):
            refreshed.extend(batch)
    return dedupe_candidates(refreshed)


async def collect_winstall_github_candidates(
    settings: Settings,
    github: GitHubReleaseResolver,
    candidates: list[InstallerCandidate],
    version: str | None,
) -> list[InstallerCandidate]:
    """Consulta cada repositorio una vez, conservando versión, orden y evidencia de origen.

    Un fallo o el agotamiento del tiempo de GitHub permite continuar con el siguiente proveedor.
    La deduplicación final corresponde al flujo que combina las estrategias de cada worker.
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
            async with asyncio.timeout(github_collection_timeout_seconds(settings)):
                release_candidates = await github.collect(
                    candidate.url,
                    version,
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
    return refreshed


async def _fetch_parent(
    client: httpx.AsyncClient,
    parent_url: str,
) -> list[InstallerCandidate]:
    """Recupera un índice padre aplicando límites de red y tamaño."""
    parsed = urlparse(parent_url)
    if not await domain_has_public_dns(parsed.hostname):
        return []
    try:
        async with client.stream("GET", parent_url) as response:
            page = await read_limited_html(response)
            if page is None:
                return []
            html, base_url = page
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


async def read_limited_html(response: httpx.Response) -> tuple[str, str] | None:
    """Lee como máximo 1 MB de una respuesta HTML correcta y devuelve su URL final.

    El cliente llamador conserva la política de red y redirecciones del proveedor.
    """
    if not response.is_success or "html" not in response.headers.get("content-type", "").lower():
        return None
    content = bytearray()
    async for chunk in response.aiter_bytes():
        remaining = 1_000_000 - len(content)
        if remaining <= 0:
            break
        content.extend(chunk[:remaining])
    return bytes(content).decode("utf-8", errors="ignore"), str(response.url)
