"""Conserva enlaces de release y recupera assets antiguos sin cambiar su identidad."""
from __future__ import annotations

import asyncio
import re
from collections.abc import Iterator
from urllib.parse import unquote, urlsplit

from app.scraper.http.fetchers import fetch_bounded_response
from app.scraper.http.public_transport import public_http_client


def stable_installer_url(candidate_url: str, final_url: str | None) -> str:
    """Evita persistir la redirección firmada de un asset concreto de GitHub."""
    parsed = urlsplit(candidate_url)
    if (parsed.scheme == "https" and parsed.netloc.lower() == "github.com"
            and re.fullmatch(r"/[^/]+/[^/]+/releases/download/[^/]+/.+", parsed.path)):
        return candidate_url
    return final_url or candidate_url


async def recover_github_asset_url(
    url: str, filename: str | None, *, timeout: float, known_urls: list[str] | None = None,
) -> str | None:
    """Busca el enlace público cuya redirección conserva exactamente la ruta CDN antigua.

    Nunca escoge latest ni acepta una coincidencia solo por nombre o versión. Limita
    páginas, comprobaciones y tiempo; cada conexión usa el transporte de IP públicas.
    """
    old = urlsplit(url)
    match = re.fullmatch(r"/github-production-release-asset/([0-9]+)/[^/]+", old.path)
    if (old.scheme != "https" or old.netloc != "release-assets.githubusercontent.com"
            or not match or not filename):
        return None
    async with asyncio.timeout(timeout):
        async with public_http_client(timeout=timeout, headers={
            "User-Agent": "BatchDownloaderScraper/0.1", "Accept": "application/vnd.github+json",
            "Cache-Control": "no-cache",
        }) as client:
            checked: set[str] = set()
            for candidate in known_urls or []:
                if (stable_installer_url(candidate, "invalid") != candidate
                        or unquote(urlsplit(candidate).path.rsplit("/", 1)[-1]) != filename):
                    continue
                if len(checked) >= 20:
                    break
                checked.add(candidate)
                if await _matches_asset(client, candidate, old):
                    return candidate
            return await _recover_from_releases(client, match[1], old, filename, checked, timeout)


async def _recover_from_releases(client, repository_id, old, filename, checked, timeout):
    for page in range(1, 4):
        response = await fetch_bounded_response(
            client, f"https://api.github.com/repositories/{repository_id}/releases",
            params={"per_page": 100, "page": page}, timeout=timeout,
            max_bytes=5_000_000, follow_redirects=False,
        )
        response.raise_for_status()
        releases = response.json()
        if not isinstance(releases, list):
            return None
        for candidate in _release_asset_urls(releases, filename):
            if candidate in checked:
                continue
            if len(checked) >= 20:
                return None
            checked.add(candidate)
            if await _matches_asset(client, candidate, old):
                return candidate
        if len(releases) < 100:
            break
    return None

def _release_asset_urls(releases: list, filename: str) -> Iterator[str]:
    """Filtra datos de GitHub antes de abrir conexiones, sin admitir otros hosts."""
    for release in releases:
        if not isinstance(release, dict) or release.get("draft"):
            continue
        for asset in release.get("assets") or []:
            if not isinstance(asset, dict) or asset.get("name") != filename:
                continue
            candidate = asset.get("browser_download_url")
            if not isinstance(candidate, str):
                continue
            if stable_installer_url(candidate, "invalid") != candidate:
                continue
            yield candidate


async def _matches_asset(client, candidate: str, old) -> bool:
    async with client.stream("HEAD", candidate) as redirect:
        destination = urlsplit(redirect.headers.get("location", ""))
        return (redirect.status_code in {301, 302, 303, 307, 308}
                and destination.scheme == "https"
                and destination.netloc == old.netloc
                and destination.path == old.path)
