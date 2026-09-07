"""Perfiles declarativos de instalación Linux. Ningún campo acepta comandos."""
from __future__ import annotations

import re
from typing import Literal
from urllib.parse import urlsplit
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

Target = Literal["apt", "dnf", "pacman", "zypper", "portable"]
Strategy = Literal["deb", "rpm", "arch", "appimage", "tarball", "jar", "manual"]

class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

class UpdatePolicy(StrictModel):
    provider: Literal["github", "official"]
    url: str | None = Field(default=None, max_length=2048)
    repository: str | None = Field(default=None, pattern=r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    asset_pattern: str | None = Field(default=None, alias="assetPattern", max_length=200)
    checksum_pattern: str | None = Field(default=None, alias="checksumPattern", max_length=200)
    signature_pattern: str | None = Field(default=None, alias="signaturePattern", max_length=200)
    allowed_hosts: list[str] = Field(alias="allowedHosts", min_length=1, max_length=12)

    @field_validator("allowed_hosts")
    @classmethod
    def hosts(cls, values: list[str]) -> list[str]:
        if any(not re.fullmatch(r"[a-z0-9][a-z0-9.-]+", v) for v in values):
            raise ValueError("invalid_update_host")
        return list(dict.fromkeys(values))

    @model_validator(mode="after")
    def provider_fields(self) -> UpdatePolicy:
        if self.provider == "github":
            if not self.repository or not self.asset_pattern or "api.github.com" not in self.allowed_hosts:
                raise ValueError("github_repository_asset_and_host_required")
        elif not self.url or not approved_url(self.url, self.allowed_hosts):
            raise ValueError("official_https_manifest_required")
        return self

def approved_url(value: str, hosts: list[str]) -> bool:
    try:
        url = urlsplit(value)
        return (url.scheme == "https" and url.hostname in hosts
                and url.port in (None, 443) and not url.username and not url.password
                and not url.fragment and not any(ord(c) < 32 for c in value))
    except ValueError:
        return False

class Verification(StrictModel):
    fingerprint: str = Field(pattern=r"^(?:[A-Fa-f0-9]{40}|[A-Fa-f0-9]{64})$")
    public_key: str = Field(alias="publicKey", max_length=65536)
    signature_url: str | None = Field(default=None, alias="signatureUrl", max_length=2048)

    @field_validator("public_key")
    @classmethod
    def public_only(cls, value: str) -> str:
        if "BEGIN PGP PUBLIC KEY BLOCK" not in value or "PRIVATE" in value:
            raise ValueError("public_key_required")
        return value

class LinuxInstallProfile(StrictModel):
    schema_version: Literal[1] = Field(default=1, alias="schemaVersion")
    strategy: Strategy
    scope: Literal["auto", "user", "system"] = "auto"
    entrypoint: str | None = Field(default=None, max_length=512)
    dependencies: list[UUID] = Field(default_factory=list, max_length=100)
    system_packages: dict[Target, list[str]] = Field(default_factory=dict, alias="systemPackages")
    linux_targets: list[Target] = Field(default_factory=list, alias="linuxTargets")
    desktop_name: str | None = Field(default=None, alias="desktopName", max_length=200)
    update: UpdatePolicy | None = None
    verification: Verification | None = None

    @field_validator("entrypoint")
    @classmethod
    def relative_entrypoint(cls, value: str | None) -> str | None:
        if value and (value.startswith("/") or "\\" in value or ":" in value
                      or ".." in value.split("/") or any(ord(c) < 32 for c in value)):
            raise ValueError("unsafe_entrypoint")
        return value

    @field_validator("system_packages")
    @classmethod
    def package_names(cls, values: dict[str, list[str]]) -> dict[str, list[str]]:
        if any(len(packages) > 50 or any(not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9+._:-]{0,127}", p)
                                        for p in packages) for packages in values.values()):
            raise ValueError("invalid_system_package")
        return values

    @field_validator("desktop_name")
    @classmethod
    def safe_name(cls, value: str | None) -> str | None:
        if value and any(ord(c) < 32 for c in value):
            raise ValueError("invalid_desktop_name")
        return value

    @model_validator(mode="after")
    def recipe(self) -> LinuxInstallProfile:
        if self.strategy in ("tarball", "jar") and not self.entrypoint:
            raise ValueError("recipe_entrypoint_required")
        if self.verification and self.verification.signature_url:
            if not self.update or not approved_url(self.verification.signature_url, self.update.allowed_hosts):
                raise ValueError("signature_origin_not_approved")
        return self

class ProfileWrite(StrictModel):
    expected_version: int = Field(alias="expectedVersion", ge=0)
    status: Literal["draft", "approved"]
    profile: LinuxInstallProfile

class DependenciesWrite(StrictModel):
    dependencies: list[UUID] = Field(max_length=99)
    expected_version: int = Field(alias="expectedVersion", ge=0)

FORMAT_STRATEGIES = {".deb": "deb", ".rpm": "rpm", ".pkg.tar.zst": "arch",
                     ".appimage": "appimage", ".tar.gz": "tarball", ".jar": "jar"}
FORMAT_TARGETS = {".deb": ["apt"], ".rpm": ["dnf", "zypper"],
                  ".pkg.tar.zst": ["pacman"], ".appimage": ["apt", "dnf", "pacman", "zypper", "portable"],
                  ".tar.gz": ["apt", "dnf", "pacman", "zypper", "portable"],
                  ".jar": ["apt", "dnf", "pacman", "zypper", "portable"]}

def default_profile(extension: str | None) -> dict:
    strategy = FORMAT_STRATEGIES.get((extension or "").lower(), "manual")
    if strategy in ("tarball", "jar"):
        strategy = "manual"
    return LinuxInstallProfile(strategy=strategy).model_dump(by_alias=True, mode="json", exclude_none=True)

