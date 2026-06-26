from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import PurePosixPath
from urllib.parse import parse_qs, unquote, urljoin, urlparse

from selectolax.parser import HTMLParser

from app.scraper.text import normalize_text

PREFERRED_EXTENSIONS = (
    ".exe",
    ".msi",
    ".msix",
    ".appx",
    ".zip",
    ".deb",
    ".rpm",
    ".dmg",
    ".pkg",
    ".tar.gz",
)

POSITIVE_KEYWORDS = (
    "download",
    "descargar",
    "installer",
    "instalador",
    "setup",
    "install",
    "windows",
    "win64",
    "x64",
    "offline",
    "standalone",
)

NEGATIVE_KEYWORDS = (
    "documentation",
    "docs",
    "release-notes",
    "source",
    "checksum",
    "signature",
    "torrent",
    "beta",
    "portable",
    "uninstall",
)

URL_PATTERN = re.compile(
    r"https?://[^\s'\"<>\\]+|(?:(?:\.\./|\.\/|/)?[A-Za-z0-9._~!$&'()*+,;=:@%/-]+"
    r"(?:\.exe|\.msi|\.msix|\.appx|\.zip|\.deb|\.rpm|\.dmg|\.pkg|\.tar\.gz)(?:\?[^\s'\"<>\\]*)?)",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class InstallerCandidate:
    url: str
    source: str
    label: str | None = None
    context: str | None = None
    score: int = 0

    @property
    def extension(self) -> str | None:
        return detect_extension(self.url)


def extract_candidates(html: str, base_url: str) -> list[InstallerCandidate]:
    parser = HTMLParser(html)
    candidates: list[InstallerCandidate] = []

    for node in parser.css("a, area"):
        href = node.attributes.get("href")
        if href:
            candidates.append(
                InstallerCandidate(
                    url=urljoin(base_url, href),
                    source="href",
                    label=node.text(separator=" ", strip=True),
                    context=node.html[:500],
                )
            )

    for node in parser.css("form"):
        action = node.attributes.get("action")
        if action:
            candidates.append(
                InstallerCandidate(
                    url=urljoin(base_url, action),
                    source="form",
                    label=node.attributes.get("aria-label") or node.text(separator=" ", strip=True),
                    context=node.html[:500],
                )
            )

    for node in parser.css("button, [role=button]"):
        values = " ".join(
            value for value in node.attributes.values() if isinstance(value, str)
        )
        text = f"{node.text(separator=' ', strip=True)} {values}"
        for match in URL_PATTERN.findall(text):
            candidates.append(
                InstallerCandidate(
                    url=urljoin(base_url, match),
                    source="button",
                    label=node.text(separator=" ", strip=True),
                    context=node.html[:500],
                )
            )

    for node in parser.css("script, meta"):
        content = node.text() if node.tag == "script" else node.attributes.get("content", "")
        for match in URL_PATTERN.findall(content or ""):
            candidates.append(
                InstallerCandidate(url=urljoin(base_url, match), source=node.tag, context=match)
            )

    deduped: dict[str, InstallerCandidate] = {}
    for candidate in candidates:
        normalized_url = candidate.url.strip()
        if normalized_url and normalized_url not in deduped:
            deduped[normalized_url] = candidate
    return list(deduped.values())


def score_candidate(
    candidate: InstallerCandidate,
    allowed_domains: set[str],
    preferred_os: str = "windows",
    preferred_architecture: str = "x86_64",
) -> InstallerCandidate:
    text = normalize_text(f"{candidate.url} {candidate.label or ''} {candidate.context or ''}")
    score = 0
    extension = detect_extension(candidate.url)
    if extension in PREFERRED_EXTENSIONS:
        score += 50
    if extension in {".exe", ".msi", ".msix", ".appx"} and preferred_os == "windows":
        score += 20
    if extension in {".dmg", ".pkg"} and preferred_os == "windows":
        score -= 35
    if extension in {".deb", ".rpm"} and preferred_os == "windows":
        score -= 30
    if preferred_architecture in {"x86_64", "amd64"} and any(
        keyword in text for keyword in ("x64", "x86_64", "amd64", "64-bit", "64bit")
    ):
        score += 15
    for keyword in POSITIVE_KEYWORDS:
        if keyword in text:
            score += 8 if keyword not in {"download", "descargar"} else 20
    for keyword in NEGATIVE_KEYWORDS:
        if keyword in text:
            score -= 50
    domain = registered_domain(candidate.url)
    if domain and domain in allowed_domains:
        score += 30
    return InstallerCandidate(
        url=candidate.url,
        source=candidate.source,
        label=candidate.label,
        context=candidate.context,
        score=score,
    )


def detect_extension(url: str) -> str | None:
    parsed = urlparse(url)
    path = unquote(parsed.path).lower()
    if path.endswith(".tar.gz"):
        return ".tar.gz"
    suffix = PurePosixPath(path).suffix
    if suffix in PREFERRED_EXTENSIONS:
        return suffix
    query = parse_qs(parsed.query)
    for values in query.values():
        for value in values:
            nested = detect_extension(value)
            if nested:
                return nested
    return None


def filename_from_url(url: str) -> str | None:
    parsed = urlparse(url)
    name = PurePosixPath(unquote(parsed.path)).name
    if name and "." in name:
        return name[:255]
    query = parse_qs(parsed.query)
    for key in ("filename", "file", "download", "installer"):
        for value in query.get(key, []):
            name = PurePosixPath(unquote(value)).name
            if name and "." in name:
                return name[:255]
    return None


def registered_domain(url: str) -> str | None:
    import tldextract

    extracted = tldextract.extract(url)
    if not extracted.domain or not extracted.suffix:
        return None
    return f"{extracted.domain}.{extracted.suffix}".lower()
