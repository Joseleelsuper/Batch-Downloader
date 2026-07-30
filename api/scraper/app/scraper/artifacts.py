"""Implementa las responsabilidades del módulo `artifacts`.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from enum import StrEnum
from pathlib import PurePosixPath
from urllib.parse import parse_qs, unquote, urlparse


class ArtifactPlatform(StrEnum):
    """Enumera los valores admitidos por `ArtifactPlatform`.
    """
    WINDOWS = "windows"
    """Constante que define `WINDOWS`.
    """
    MACOS = "macos"
    """Constante que define `MACOS`.
    """
    LINUX = "linux"
    """Constante que define `LINUX`.
    """


class ArtifactArchitecture(StrEnum):
    """Enumera los valores admitidos por `ArtifactArchitecture`.
    """
    X86_64 = "x86_64"
    """Constante que define `X86_64`.
    """
    X86 = "x86"
    """Constante que define `X86`.
    """
    AARCH64 = "aarch64"
    """Constante que define `AARCH64`.
    """
    UNKNOWN = "unknown"
    """Constante que define `UNKNOWN`.
    """


@dataclass(frozen=True)
class ArtifactFormat:
    """Representa el componente `ArtifactFormat`.
    """
    extension: str
    """Atributo de clase `extension` de `ArtifactFormat`.
    """
    platforms: tuple[ArtifactPlatform, ...]
    """Atributo de clase `platforms` de `ArtifactFormat`.
    """
    media_types: tuple[str, ...] = ()
    """Atributo de clase `media_types` de `ArtifactFormat`.
    """
    signatures: tuple[bytes, ...] = ()
    """Atributo de clase `signatures` de `ArtifactFormat`.
    """
    infer_from_signature: bool = False
    """Atributo de clase `infer_from_signature` de `ArtifactFormat`.
    """


class ArtifactFormatRegistry:
    """Representa el componente `ArtifactFormatRegistry`.
    """

    def __init__(self, formats: tuple[ArtifactFormat, ...]) -> None:
        """Inicializa una instancia de `ArtifactFormatRegistry`.

        Args:
            formats (tuple[ArtifactFormat, ...]): Valor de `formats` utilizado por la operación.

        Throws:
            ValueError: Si los datos recibidos no cumplen las restricciones requeridas.
        """
        if not formats:
            raise ValueError("artifact_format_registry_cannot_be_empty")
        self._formats = formats
        """Estado de instancia asociado a `_formats`.
        """
        self._by_extension = {item.extension: item for item in formats}
        """Estado de instancia asociado a `_by_extension`.
        """
        if len(self._by_extension) != len(formats):
            raise ValueError("artifact_format_extensions_must_be_unique")

    @property
    def extensions(self) -> tuple[str, ...]:
        """Ejecuta `extensions` dentro de `ArtifactFormatRegistry`.

        Returns:
            tuple[str, ...]: Resultado producido por la operación.
        """
        return tuple(item.extension for item in self._formats)

    @property
    def binary_media_types(self) -> frozenset[str]:
        """Ejecuta `binary_media_types` dentro de `ArtifactFormatRegistry`.

        Returns:
            frozenset[str]: Resultado producido por la operación.
        """
        return frozenset(
            media_type
            for artifact_format in self._formats
            for media_type in artifact_format.media_types
        )

    def get(self, extension: str | None) -> ArtifactFormat | None:
        """Ejecuta `get` dentro de `ArtifactFormatRegistry`.

        Args:
            extension (str | None): Valor de `extension` utilizado por la operación.

        Returns:
            ArtifactFormat | None: Resultado producido por la operación.
        """
        if not extension:
            return None
        return self._by_extension.get(extension.lower().strip())

    def extensions_for(self, platform: ArtifactPlatform | str) -> tuple[str, ...]:
        """Ejecuta `extensions_for` dentro de `ArtifactFormatRegistry`.

        Args:
            platform (ArtifactPlatform | str): Valor de `platform` utilizado por la operación.

        Returns:
            tuple[str, ...]: Resultado producido por la operación.
        """
        normalized = ArtifactPlatform(platform)
        return tuple(
            item.extension for item in self._formats if normalized in item.platforms
        )

    def platform_for(self, extension: str | None) -> ArtifactPlatform | None:
        """Ejecuta `platform_for` dentro de `ArtifactFormatRegistry`.

        Args:
            extension (str | None): Valor de `extension` utilizado por la operación.

        Returns:
            ArtifactPlatform | None: Resultado producido por la operación.
        """
        artifact_format = self.get(extension)
        if artifact_format is None or len(artifact_format.platforms) != 1:
            return None
        return artifact_format.platforms[0]

    def detect_extension(self, value: str) -> str | None:
        """Ejecuta `detect_extension` dentro de `ArtifactFormatRegistry`.

        Args:
            value (str): Valor que debe procesarse.

        Returns:
            str | None: Resultado producido por la operación.
        """
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
        """Ejecuta `matches_signature` dentro de `ArtifactFormatRegistry`.

        Args:
            extension (str): Valor de `extension` utilizado por la operación.
            content (bytes): Contenido que debe procesarse.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        artifact_format = self.get(extension)
        if artifact_format is None or not content:
            return False
        return any(content.startswith(signature) for signature in artifact_format.signatures)

    def infer_extension(self, content: bytes) -> str | None:
        """Ejecuta `infer_extension` dentro de `ArtifactFormatRegistry`.

        Args:
            content (bytes): Contenido que debe procesarse.

        Returns:
            str | None: Resultado producido por la operación.
        """
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
        """Ejecuta `infer_architecture` dentro de `ArtifactFormatRegistry`.

        Args:
            text (str): Valor de `text` utilizado por la operación.
            default (ArtifactArchitecture): Valor de `default` utilizado por la operación.

        Returns:
            ArtifactArchitecture: Resultado producido por la operación.
        """
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
    """Ejecuta el paso interno `_has_token`.

    Args:
        text (str): Valor de `text` utilizado por la operación.
        token (str): Token utilizado para autorizar o correlacionar la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
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
"""Constante que define `DEFAULT_ARTIFACT_FORMAT_REGISTRY`.
"""

GENERIC_BINARY_MEDIA_TYPES = frozenset({"application/octet-stream", "binary/octet-stream"})
"""Constante que define `GENERIC_BINARY_MEDIA_TYPES`.
"""
