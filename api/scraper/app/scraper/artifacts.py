from __future__ import annotations

import re
from dataclasses import dataclass
from enum import StrEnum
from pathlib import PurePosixPath
from urllib.parse import parse_qs, unquote, urlparse


class ArtifactPlatform(StrEnum):
    WINDOWS = "windows"
    MACOS = "macos"
    LINUX = "linux"


class ArtifactArchitecture(StrEnum):
    X86_64 = "x86_64"
    X86 = "x86"
    AARCH64 = "aarch64"
    UNKNOWN = "unknown"


@dataclass(frozen=True)
class ArtifactFormat:
    extension: str
    platforms: tuple[ArtifactPlatform, ...]
    media_types: tuple[str, ...] = ()
    signatures: tuple[bytes, ...] = ()
    infer_from_signature: bool = False


class ArtifactFormatRegistry:
    """Single source of truth for supported installer artifact formats."""

    def __init__(self, formats: tuple[ArtifactFormat, ...]) -> None:
        if not formats:
            raise ValueError("artifact_format_registry_cannot_be_empty")
        self._formats = formats
        self._by_extension = {item.extension: item for item in formats}
        if len(self._by_extension) != len(formats):
            raise ValueError("artifact_format_extensions_must_be_unique")

    @property
    def extensions(self) -> tuple[str, ...]:
        return tuple(item.extension for item in self._formats)

    @property
    def binary_media_types(self) -> frozenset[str]:
        return frozenset(
            media_type
            for artifact_format in self._formats
            for media_type in artifact_format.media_types
        )

    def get(self, extension: str | None) -> ArtifactFormat | None:
        if not extension:
            return None
        return self._by_extension.get(extension.lower().strip())

    def extensions_for(self, platform: ArtifactPlatform | str) -> tuple[str, ...]:
        normalized = ArtifactPlatform(platform)
        return tuple(
            item.extension for item in self._formats if normalized in item.platforms
        )

    def platform_for(self, extension: str | None) -> ArtifactPlatform | None:
        artifact_format = self.get(extension)
        if artifact_format is None or len(artifact_format.platforms) != 1:
            return None
        return artifact_format.platforms[0]

    def detect_extension(self, value: str) -> str | None:
        try:
            parsed = urlparse(value)
        except ValueError:
            return None
        path = unquote(parsed.path).lower()
        for segment in reversed([path, *path.split("/")]):
            for extension in sorted(self.extensions, key=len, reverse=True):
                if segment.endswith(extension):
                    return extension
            suffix = PurePosixPath(segment).suffix
            if suffix in self._by_extension:
                return suffix
        for values in parse_qs(parsed.query).values():
            for nested_value in values:
                nested = self.detect_extension(nested_value)
                if nested:
                    return nested
        return None

    def matches_signature(self, extension: str, content: bytes) -> bool:
        artifact_format = self.get(extension)
        if artifact_format is None or not content:
            return False
        return any(content.startswith(signature) for signature in artifact_format.signatures)

    def infer_extension(self, content: bytes) -> str | None:
        for artifact_format in self._formats:
            if artifact_format.infer_from_signature and self.matches_signature(
                artifact_format.extension,
                content,
            ):
                return artifact_format.extension
        return None

    def infer_architecture(
        self,
        text: str,
        *,
        default: ArtifactArchitecture = ArtifactArchitecture.UNKNOWN,
    ) -> ArtifactArchitecture:
        normalized_text = text.casefold()
        token_groups = (
            (
                ArtifactArchitecture.AARCH64,
                ("aarch64", "arm64", "apple silicon", "m1", "m2", "m3"),
            ),
            (
                ArtifactArchitecture.X86_64,
                ("x86_64", "amd64", "x64", "win64", "64-bit", "64bit"),
            ),
            (
                ArtifactArchitecture.X86,
                ("i386", "i686", "x86", "win32", "32-bit", "32bit"),
            ),
        )
        for architecture, tokens in token_groups:
            if any(_has_token(normalized_text, token) for token in tokens):
                return architecture
        return default


def _has_token(text: str, token: str) -> bool:
    return re.search(rf"(?<![a-z0-9]){re.escape(token)}(?![a-z0-9])", text) is not None


DEFAULT_ARTIFACT_FORMAT_REGISTRY = ArtifactFormatRegistry(
    (
        ArtifactFormat(
            ".exe",
            (ArtifactPlatform.WINDOWS,),
            (
                "application/exe",
                "application/x-dosexec",
                "application/x-executable",
                "application/x-ms-dos-executable",
                "application/x-msdos-program",
                "application/x-msdownload",
                "application/vnd.microsoft.portable-executable",
            ),
            (b"MZ",),
            True,
        ),
        ArtifactFormat(
            ".msi",
            (ArtifactPlatform.WINDOWS,),
            (
                "application/x-msi",
                "application/x-ms-installer",
                "application/x-ole-storage",
            ),
            (b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1",),
            True,
        ),
        ArtifactFormat(
            ".msix",
            (ArtifactPlatform.WINDOWS,),
            ("application/msix", "application/zip"),
            (b"PK\x03\x04",),
        ),
        ArtifactFormat(
            ".msixbundle",
            (ArtifactPlatform.WINDOWS,),
            ("application/msixbundle", "application/zip"),
            (b"PK\x03\x04",),
        ),
        ArtifactFormat(
            ".appx",
            (ArtifactPlatform.WINDOWS,),
            ("application/appx", "application/zip"),
            (b"PK\x03\x04",),
        ),
        ArtifactFormat(
            ".appxbundle",
            (ArtifactPlatform.WINDOWS,),
            ("application/appxbundle", "application/zip"),
            (b"PK\x03\x04",),
        ),
        ArtifactFormat(
            ".zip",
            (),
            ("application/zip", "application/x-zip-compressed"),
            (b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08"),
            True,
        ),
        ArtifactFormat(
            ".deb",
            (ArtifactPlatform.LINUX,),
            ("application/x-debian-package",),
            (b"!<arch>\n",),
            True,
        ),
        ArtifactFormat(
            ".rpm",
            (ArtifactPlatform.LINUX,),
            ("application/x-rpm",),
            (b"\xed\xab\xee\xdb",),
            True,
        ),
        ArtifactFormat(
            ".appimage",
            (ArtifactPlatform.LINUX,),
            ("application/x-elf",),
            (b"\x7fELF",),
            True,
        ),
        ArtifactFormat(
            ".dmg",
            (ArtifactPlatform.MACOS,),
            ("application/x-apple-diskimage",),
        ),
        ArtifactFormat(
            ".pkg",
            (ArtifactPlatform.MACOS,),
            ("application/x-xar",),
            (b"xar!",),
            True,
        ),
        ArtifactFormat(
            ".tar.gz",
            (ArtifactPlatform.LINUX,),
            ("application/gzip", "application/x-gzip"),
            (b"\x1f\x8b",),
            True,
        ),
        ArtifactFormat(
            ".jar",
            (ArtifactPlatform.LINUX,),
            ("application/java-archive",),
            (b"PK\x03\x04",),
        ),
    )
)

GENERIC_BINARY_MEDIA_TYPES = frozenset({"application/octet-stream", "binary/octet-stream"})
