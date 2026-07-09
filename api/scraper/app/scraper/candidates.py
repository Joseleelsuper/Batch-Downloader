from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import PurePosixPath
from urllib.parse import parse_qs, unquote, urljoin, urlparse, urlunparse

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
    ".appimage",
    ".dmg",
    ".pkg",
    ".tar.gz",
    ".jar",
)

WINDOWS_INSTALLER_EXTENSIONS = (".exe", ".msi", ".msix", ".appx")
MACOS_INSTALLER_EXTENSIONS = (".dmg", ".pkg")
LINUX_INSTALLER_EXTENSIONS = (".deb", ".rpm", ".appimage", ".tar.gz", ".jar")
UNSUPPORTED_DOWNLOAD_EXTENSIONS = (
    ".apk",
    ".asc",
    ".bib",
    ".checksum",
    ".css",
    ".html",
    ".json",
    ".pdf",
    ".sig",
    ".sha1",
    ".sha256",
    ".sha512",
    ".txt",
    ".xml",
    ".yml",
    ".yaml",
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
    "opengl",
    "noselfupdate",
)

URL_PATTERN = re.compile(
    r"https?://[^\s'\"<>\\]+|(?:(?:\.\./|\.\/|/)?[A-Za-z0-9._~!$&'()*+,;=:@%/-]+"
    r"(?:\.exe|\.msi|\.msix|\.appx|\.zip|\.deb|\.rpm|\.appimage|\.dmg|\.pkg|\.tar\.gz|\.jar)(?:\?[^\s'\"<>\\]*)?)",
    re.IGNORECASE,
)

# Scripts often contain arbitrary JavaScript fragments ending in ".exe" or ".deb".
# Only absolute URLs are trustworthy enough to extract from a script body; regular
# link and form attributes are still handled separately below.
ABSOLUTE_URL_PATTERN = re.compile(r"https?://[^\s'\"<>\\]+", re.IGNORECASE)

VERSION_PATTERN = re.compile(r"(?<!\d)v?(\d+(?:\.\d+){1,4})", re.I)


@dataclass(frozen=True)
class InstallerCandidate:
    url: str
    source: str
    label: str | None = None
    context: str | None = None
    score: int = 0
    asset_kind: str | None = None
    match_tokens: tuple[str, ...] = ()
    referer: str | None = None

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
                    referer=base_url,
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
                    referer=base_url,
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
                    referer=base_url,
                )
            )

    for node in parser.css("script"):
        for match in ABSOLUTE_URL_PATTERN.findall(node.text() or ""):
            candidates.append(
                InstallerCandidate(
                    url=match,
                    source="script",
                    context=match,
                    referer=base_url,
                )
            )

    for node in parser.css("meta"):
        content = node.attributes.get("content", "")
        for match in ABSOLUTE_URL_PATTERN.findall(content or ""):
            candidates.append(
                InstallerCandidate(
                    url=match,
                    source="meta",
                    context=match,
                    referer=base_url,
                )
            )

    deduped: dict[str, InstallerCandidate] = {}
    for candidate in candidates:
        normalized_url = candidate.url.strip()
        if normalized_url and normalized_url not in deduped:
            deduped[normalized_url] = candidate
    return list(deduped.values())


def score_candidate(
    candidate: InstallerCandidate,
    app_name: str | None = None,
    package_id: str | None = None,
    publisher: str | None = None,
    version: str | None = None,
) -> InstallerCandidate:
    text = normalize_text(f"{candidate.url} {candidate.label or ''} {candidate.context or ''}")
    score = 0
    extension = detect_extension(candidate.url)
    asset_kind = (
        "source_archive"
        if is_github_source_archive(candidate.url)
        else candidate.asset_kind or classify_asset(candidate.url)
    )
    if asset_kind == "source_archive":
        score -= 150
    if extension in (
        WINDOWS_INSTALLER_EXTENSIONS
        + MACOS_INSTALLER_EXTENSIONS
        + (".deb", ".rpm", ".appimage")
    ):
        score += 70
    elif extension == ".zip":
        score += 25
    elif extension in PREFERRED_EXTENSIONS:
        score += 50
    if extension == ".zip" and registered_domain(candidate.url) == "github.com":
        score += 10 if is_github_release_asset(candidate.url) else -90
    if any(
        keyword_present(text, keyword)
        for keyword in (
            "x64",
            "x86_64",
            "amd64",
            "64-bit",
            "64bit",
            "x86",
            "i386",
            "i686",
            "arm64",
            "aarch64",
            "apple silicon",
        )
    ):
        score += 15
    for keyword in POSITIVE_KEYWORDS:
        if keyword_present(text, keyword):
            score += 8 if keyword not in {"download", "descargar"} else 20
    for keyword in NEGATIVE_KEYWORDS:
        if keyword_present(text, keyword):
            score -= 50
    match_tokens = app_match_tokens(
        text=text,
        app_name=app_name,
        package_id=package_id,
        publisher=publisher,
        version=version,
    )
    score += len(match_tokens) * 12
    score += variant_score(text=text, app_name=app_name, package_id=package_id)
    return InstallerCandidate(
        url=candidate.url,
        source=candidate.source,
        label=candidate.label,
        context=candidate.context,
        score=score,
        asset_kind=asset_kind,
        match_tokens=tuple(match_tokens),
        referer=candidate.referer,
    )


def classify_asset(url: str) -> str:
    if is_github_source_archive(url):
        return "source_archive"
    if is_github_release_asset(url) and detect_extension(url) == ".zip":
        return "release_zip"
    if detect_extension(url) in WINDOWS_INSTALLER_EXTENSIONS:
        return "installer"
    if detect_extension(url) in MACOS_INSTALLER_EXTENSIONS:
        return "installer"
    if detect_extension(url) in LINUX_INSTALLER_EXTENSIONS:
        return "installer"
    if detect_extension(url) in PREFERRED_EXTENSIONS:
        return "archive"
    return "unknown"


def is_github_source_archive(url: str) -> bool:
    parsed = urlparse(url)
    host = parsed.netloc.lower()
    path = parsed.path.lower()
    if host == "codeload.github.com":
        return True
    if host.endswith("github.com") and any(
        marker in path for marker in ("/archive/", "/zipball/", "/tarball/", "/refs/heads/")
    ):
        return True
    filename = PurePosixPath(path).name
    return host.endswith("github.com") and filename in {"main.zip", "master.zip"}


def is_github_release_asset(url: str) -> bool:
    parsed = urlparse(url)
    return parsed.netloc.lower().endswith("github.com") and "/releases/download/" in parsed.path.lower()


def app_match_tokens(
    text: str,
    app_name: str | None,
    package_id: str | None,
    publisher: str | None,
    version: str | None,
) -> list[str]:
    raw = " ".join(value for value in (app_name, package_id, publisher, version) if value)
    tokens = product_tokens(raw)
    return [token for token in tokens if token in text]


def product_tokens(value: str) -> list[str]:
    normalized = normalize_text(value.replace(".", " ").replace("_", " ").replace("-", " "))
    stopwords = {
        "app",
        "application",
        "desktop",
        "for",
        "inc",
        "installer",
        "launcher",
        "llc",
        "software",
        "windows",
    }
    tokens = [
        token
        for token in re.findall(r"[a-z0-9]+", normalized)
        if len(token) >= 3 and token not in stopwords
    ]
    return list(dict.fromkeys(tokens))


def variant_score(text: str, app_name: str | None, package_id: str | None) -> int:
    app_text = normalize_text(f"{app_name or ''} {package_id or ''}")
    score = 0
    variants = {
        "graphing": ("graphing",),
        "geometry": ("geometry",),
        "cas": ("cas",),
        "suite": ("suite", "win-suite", "calculator-suite"),
        "classic": ("classic",),
    }
    for variant, aliases in variants.items():
        app_wants_variant = variant in app_text or (
            variant == "suite" and "calculator suite" in app_text
        )
        candidate_has_variant = any(alias in text for alias in aliases)
        if app_wants_variant and candidate_has_variant:
            score += 35
        elif not app_wants_variant and candidate_has_variant:
            score -= 25
    return score


def detect_extension(url: str) -> str | None:
    parsed = urlparse(url)
    path = unquote(parsed.path).lower()
    for segment in reversed([path, *path.split("/")]):
        if segment.endswith(".tar.gz"):
            return ".tar.gz"
        suffix = PurePosixPath(segment).suffix
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
    path = unquote(parsed.path)
    for name in [PurePosixPath(path).name, *reversed(path.split("/"))]:
        if name and "." in name and detect_extension(f"https://local.invalid/{name}"):
            return name[:255]
    query = parse_qs(parsed.query)
    for key in ("filename", "file", "download", "installer"):
        for value in query.get(key, []):
            name = PurePosixPath(unquote(value)).name
            if name and "." in name:
                return name[:255]
    return None


def candidate_text(candidate: InstallerCandidate) -> str:
    return normalize_text(f"{candidate.url} {candidate.label or ''} {candidate.context or ''}")


def keyword_present(text: str, keyword: str) -> bool:
    """Match semantic keywords, without treating `sourceforge` as `source`."""
    normalized = normalize_text(keyword)
    pattern = re.escape(normalized).replace(r"\ ", r"\s+")
    return re.search(rf"(?<![a-z0-9]){pattern}(?![a-z0-9])", text) is not None


def candidate_has_download_intent(candidate: InstallerCandidate) -> bool:
    text = candidate_text(candidate)
    if candidate.asset_kind == "winstall_download":
        return True
    return any(
        keyword_present(text, keyword)
        for keyword in ("download", "descargar", "installer", "instalador", "setup", "install")
    )


def is_download_candidate(candidate: InstallerCandidate) -> bool:
    """Whether a candidate is worth validating before its final URL reveals an OS."""
    return bool(detect_extension(candidate.url)) or candidate_has_download_intent(candidate)


def candidate_variants(candidate: InstallerCandidate) -> list[InstallerCandidate]:
    """Return safe equivalent URLs for infrastructure-specific malformed hosts."""
    variant = s3_path_style_variant(candidate)
    return [candidate, variant] if variant else [candidate]


def s3_path_style_variant(candidate: InstallerCandidate) -> InstallerCandidate | None:
    """Repair S3 virtual-host URLs whose bucket name cannot be validated as TLS DNS.

    S3 bucket names containing an underscore are valid legacy bucket names but not
    valid host labels. The path-style S3 endpoint keeps TLS hostname validation intact.
    """
    parsed = urlparse(candidate.url)
    host = (parsed.hostname or "").lower()
    suffix = ".s3.amazonaws.com"
    if not host.endswith(suffix):
        return None
    bucket = host.removesuffix(suffix)
    if not bucket or "_" not in bucket:
        return None
    url = urlunparse(
        (
            "https",
            "s3.amazonaws.com",
            f"/{bucket}{parsed.path}",
            "",
            parsed.query,
            "",
        )
    )
    return InstallerCandidate(
        url=url,
        source=f"{candidate.source}_s3_path_style",
        label=candidate.label,
        context=candidate.context,
        asset_kind=candidate.asset_kind,
        referer=candidate.referer,
    )


def infer_operating_system(candidate: InstallerCandidate) -> str | None:
    extension = candidate.extension
    text = candidate_text(candidate)
    if extension == ".tar.gz":
        if any(token in text for token in ("macos", "mac os", "darwin", "apple silicon")):
            return "macos"
        if any(token in text for token in ("linux", "ubuntu", "debian", "fedora")):
            return "linux"
        return "linux"
    operating_system = operating_system_for_extension(extension)
    if operating_system:
        return operating_system
    if any(token in text for token in ("windows", "win64", "win32", "x64.exe")):
        return "windows"
    if any(token in text for token in ("macos", "mac os", "darwin", "dmg", "apple silicon")):
        return "macos"
    if any(token in text for token in ("linux", "ubuntu", "debian", "fedora", "appimage")):
        return "linux"
    return None


def operating_system_for_extension(extension: str | None) -> str | None:
    if not extension:
        return None
    normalized = extension.lower().strip()
    if normalized in WINDOWS_INSTALLER_EXTENSIONS:
        return "windows"
    if normalized in MACOS_INSTALLER_EXTENSIONS:
        return "macos"
    if normalized in LINUX_INSTALLER_EXTENSIONS:
        return "linux"
    return None


def infer_architecture(candidate: InstallerCandidate) -> str:
    text = candidate_text(candidate)
    if any(
        has_architecture_token(text, token)
        for token in ("aarch64", "arm64", "apple silicon", "m1", "m2", "m3")
    ):
        return "aarch64"
    if any(
        has_architecture_token(text, token)
        for token in ("x86_64", "amd64", "x64", "win64", "64-bit", "64bit")
    ):
        return "x86_64"
    if any(
        has_architecture_token(text, token)
        for token in ("i386", "i686", "x86", "win32", "32-bit", "32bit")
    ):
        return "x86"
    return "x86_64"


def has_architecture_token(text: str, token: str) -> bool:
    return re.search(rf"(?<![a-z0-9]){re.escape(token)}(?![a-z0-9])", text) is not None


def extract_version(candidate: InstallerCandidate) -> str | None:
    raw = " ".join(value for value in (candidate.url, candidate.label, candidate.context) if value)
    matches = VERSION_PATTERN.findall(raw)
    if not matches:
        return None
    return matches[-1]


def registered_domain(url: str) -> str | None:
    import tldextract

    extracted = tldextract.extract(url)
    if not extracted.domain or not extracted.suffix:
        return None
    return f"{extracted.domain}.{extracted.suffix}".lower()
