#!/usr/bin/env python3
"""Operaciones de datos para Bash: JSON, archivos y HTTPS sin ejecutar contenido."""
from __future__ import annotations

import hashlib
import http.client
import ipaddress
import json
import os
from pathlib import Path, PurePosixPath
import re
import socket
import ssl
import struct
import sys
import tarfile
import time
from urllib.parse import urljoin, urlsplit
import zipfile

ID = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$")
PACKAGE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9+._:-]{0,127}$")
SHA = re.compile(r"^[0-9a-fA-F]{64}$")
TARGETS = ("apt", "dnf", "pacman", "zypper", "portable")
STRATEGIES = ("deb", "rpm", "arch", "appimage", "tarball", "jar", "manual")
ARCHES = {"amd64": "x86_64", "x86_64": "x86_64", "i386": "x86",
          "i686": "x86", "x86": "x86", "arm64": "aarch64",
          "aarch64": "aarch64", "all": "any", "noarch": "any", "any": "any", "universal": "any"}
PROFILE_KEYS = {"schemaVersion", "strategy", "scope", "entrypoint", "dependencies",
                "systemPackages", "update", "verification", "desktopName", "linuxTargets"}

def require(condition, code):
    if not condition:
        raise ValueError(code)

def relative(value):
    require(isinstance(value, str) and bool(value) and "\\" not in value
            and not any(ord(c) < 32 for c in value), "invalid_relative_path")
    p = PurePosixPath(value)
    require(not p.is_absolute() and not any(v in ("..", "") for v in p.parts)
            and p.parts and ":" not in value, "unsafe_relative_path")
    return value

def child(root, value):
    relative(value)
    root = Path(root).resolve()
    result = root / value
    require(result.resolve().is_relative_to(root) and result != root, "path_escape")
    return result

def read_json(path):
    path = Path(path)
    require(path.stat().st_size <= 2 * 1024 * 1024, "json_too_large")
    def pairs(values):
        result = {}
        for k, v in values:
            require(k not in result, "duplicate_json_key")
            result[k] = v
        return result
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=pairs)

def atomic_json(path, value):
    path = Path(path)
    require(not path.is_symlink(), "state_symlink")
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as out:
        json.dump(value, out, ensure_ascii=False, indent=2)
        out.flush()
        os.fsync(out.fileno())
    os.replace(tmp, path)
    directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)

def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

def check_hash(path, expected):
    require(isinstance(expected, str) and SHA.fullmatch(expected), "invalid_sha256")
    require(digest(path) == expected.lower(), "sha256_mismatch")

def detect():
    values = {}
    p = Path("/etc/os-release")
    if p.exists():
        for line in p.read_text().splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                values[k] = v.strip('"').strip("'")
    tokens = [values.get("ID", ""), *values.get("ID_LIKE", "").split()]
    manager = "portable"
    for token in tokens:
        if token in ("ubuntu", "debian", "linuxmint", "pop"): manager = "apt"; break
        if token in ("fedora", "rhel", "centos", "rocky", "almalinux"): manager = "dnf"; break
        if token in ("arch", "manjaro", "endeavouros"): manager = "pacman"; break
        if token.startswith(("opensuse", "sles", "suse")): manager = "zypper"; break
    return {"manager": manager, "architecture": ARCHES.get(os.uname().machine, "unknown"),
            "name": values.get("PRETTY_NAME", "Linux")}

def validate_profile(p):
    require(isinstance(p, dict) and not set(p) - PROFILE_KEYS, "unknown_profile_field")
    require(p.get("schemaVersion") == 1 and p.get("strategy") in STRATEGIES, "invalid_profile")
    require(p.get("scope", "auto") in ("auto", "user", "system"), "invalid_scope")
    if p.get("entrypoint"):
        relative(p["entrypoint"])
    if p["strategy"] in ("tarball", "jar"):
        require(bool(p.get("entrypoint")), "recipe_entrypoint_required")
    require(isinstance(p.get("dependencies", []), list), "invalid_dependencies")
    require(isinstance(p.get("systemPackages", {}), dict), "invalid_system_packages")
    require(isinstance(p.get("linuxTargets", []), list), "invalid_linux_targets")
    for dep in p.get("dependencies", []):
        require(isinstance(dep, str) and ID.fullmatch(dep), "invalid_dependency")
    require(len(p.get("dependencies", [])) <= 100, "too_many_dependencies")
    for manager, packages in p.get("systemPackages", {}).items():
        require(manager in TARGETS and isinstance(packages, list) and len(packages) <= 50,
                "invalid_system_packages")
        for pkg in packages:
            require(isinstance(pkg, str) and PACKAGE.fullmatch(pkg), "invalid_package")
    for target in p.get("linuxTargets", []):
        require(target in TARGETS, "invalid_linux_target")
    title = p.get("desktopName", "")
    require(isinstance(title, str) and len(title) <= 200
            and not any(ord(c) < 32 for c in title), "invalid_desktop_name")
    update = p.get("update")
    if update:
        require(set(update) <= {"provider", "url", "repository", "assetPattern",
                                "checksumPattern", "signaturePattern", "allowedHosts"},
                "unknown_update_field")
        require(update.get("provider") in ("github", "official"), "invalid_update_provider")
        hosts = update.get("allowedHosts", [])
        require(isinstance(hosts, list) and 0 < len(hosts) <= 12, "update_hosts_required")
        for host in hosts:
            require(isinstance(host, str) and re.fullmatch(r"[a-z0-9][a-z0-9.-]+", host),
                    "invalid_update_host")
        if update["provider"] == "github":
            require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+",
                                 update.get("repository", "")), "invalid_repository")
            require("api.github.com" in hosts and update.get("assetPattern"),
                    "github_asset_required")
        else:
            validate_url(update.get("url", ""), hosts)
    verification = p.get("verification")
    if verification:
        require(set(verification) <= {"fingerprint", "publicKey", "signatureUrl"},
                "unknown_verification_field")
        require(re.fullmatch(r"[0-9A-Fa-f]{40}|[0-9A-Fa-f]{64}",
                             verification.get("fingerprint", "")), "invalid_fingerprint")
        require(isinstance(verification.get("publicKey"), str)
                and "BEGIN PGP PUBLIC KEY BLOCK" in verification["publicKey"]
                and len(verification["publicKey"]) <= 65536, "invalid_public_key")
        if verification.get("signatureUrl"):
            require(update, "signature_hosts_required")
            validate_url(verification["signatureUrl"], update["allowedHosts"])
    return p

def validate_component(c):
    require(isinstance(c, dict) and c.get("schemaVersion") == 1, "invalid_component_schema")
    for key in ("name", "version"):
        value = c.get(key, "")
        require(isinstance(value, str) and len(value) <= 200
                and not any(ord(v) < 32 or ord(v) == 127 for v in value), "invalid_component_text")
    require(ID.fullmatch(c.get("id", "")), "invalid_component_id")
    require(ID.fullmatch(c.get("appId", "")), "invalid_app_id")
    relative(c.get("filename", ""))
    require(SHA.fullmatch(c.get("sha256", "")), "invalid_component_hash")
    c["architecture"] = ARCHES.get(c.get("architecture"), c.get("architecture"))
    require(c.get("architecture") in (*ARCHES.values(), "unknown", "UNKNOWN"),
            "invalid_architecture")
    validate_profile(c["profile"])
    return c

def inspect(strategy, path):
    path = Path(path)
    with path.open("rb") as f:
        header = f.read(64)
    if strategy == "deb":
        require(header.startswith(b"!<arch>\n"), "invalid_deb")
    elif strategy == "rpm":
        require(header.startswith(b"\xed\xab\xee\xdb"), "invalid_rpm")
    elif strategy == "appimage":
        require(header.startswith(b"\x7fELF") and header[8:11] in (b"AI\x01", b"AI\x02"),
                "invalid_appimage")
    elif strategy == "tarball":
        require(tarfile.is_tarfile(path), "invalid_tarball")
    elif strategy == "jar":
        require(zipfile.is_zipfile(path), "invalid_jar")
    elif strategy == "arch":
        require(path.name.endswith(".pkg.tar.zst"), "invalid_arch_package")
    return True

def elf_arch(path):
    with open(path, "rb") as f:
        header = f.read(64)
    if header[:4] != b"\x7fELF" or len(header) < 20:
        return None
    machine = struct.unpack(("<" if header[5] == 1 else ">") + "H", header[18:20])[0]
    return {3: "x86", 62: "x86_64", 183: "aarch64"}.get(machine, "unknown")

def extract(path, destination, max_bytes=4 * 1024 ** 3):
    """Valida TODO el tar antes de extraer. No dispositivos ni enlaces externos."""
    destination = Path(destination).resolve()
    with tarfile.open(path) as archive:
        members = archive.getmembers()
        members = [m for m in members if not (m.isdir() and PurePosixPath(m.name) == PurePosixPath("."))]
        require(len(members) <= 100000, "too_many_archive_entries")
        require(sum(m.size for m in members) <= max_bytes, "archive_too_large")
        seen = set()
        for member in members:
            target = child(destination, member.name)
            normalized = str(PurePosixPath(member.name))
            require(normalized not in seen, "duplicate_archive_entry")
            seen.add(normalized)
            require(member.isfile() or member.isdir() or member.issym() or member.islnk(),
                    "unsafe_archive_entry")
            if member.issym() or member.islnk():
                require(not PurePosixPath(member.linkname).is_absolute(), "unsafe_archive_link")
                base = target.parent if member.issym() else destination
                require((base / member.linkname).resolve().is_relative_to(destination),
                        "unsafe_archive_link")
            member.mode &= 0o777
            member.uid = member.gid = 0
            member.uname = member.gname = ""
        # Enlaces al final para impedir que redirijan escrituras durante la extracción.
        regular = [m for m in members if not (m.issym() or m.islnk())]
        links = [m for m in members if m.issym() or m.islnk()]
        for member in regular + links:
            archive.extract(member, destination, set_attrs=False)
            target = child(destination, member.name)
            if not member.issym():
                target.chmod(member.mode & 0o777)

def validate_url(url, hosts):
    p = urlsplit(url)
    require(p.scheme == "https" and p.hostname in hosts and p.port in (None, 443)
            and not p.username and not p.password and not p.fragment,
            "unapproved_update_url")
    require(not any(ord(c) < 32 for c in url), "invalid_url")
    return p

class PinnedConnection(http.client.HTTPSConnection):
    def __init__(self, hostname, address):
        super().__init__(hostname, timeout=30, context=ssl.create_default_context())
        self.address = address

    def connect(self):
        sock = socket.create_connection((self.address, 443), timeout=self.timeout)
        self.sock = self._context.wrap_socket(sock, server_hostname=self.host)

def fetch(url, hosts, output, max_bytes=2 * 1024 ** 3):
    """Conecta a una IP pública ya validada; comprueba cada redirect."""
    output = Path(output)
    require(not output.is_symlink(), "download_symlink")
    partial = output.with_name(output.name + ".part")
    for attempt in range(3):
        current = url
        try:
            for _ in range(6):
                parsed = validate_url(current, hosts)
                addresses = {entry[4][0] for entry in socket.getaddrinfo(parsed.hostname, 443,
                                                                         type=socket.SOCK_STREAM)}
                require(addresses and all(ipaddress.ip_address(a).is_global for a in addresses),
                        "non_public_update_address")
                conn = PinnedConnection(parsed.hostname, sorted(addresses)[0])
                try:
                    conn.request("GET", parsed.path + ("?" + parsed.query if parsed.query else ""),
                                 headers={"User-Agent": "BatchLinuxInstaller/1.0", "Accept": "*/*"})
                    response = conn.getresponse()
                    if response.status in (301, 302, 303, 307, 308):
                        current = urljoin(current, response.getheader("Location", ""))
                        continue
                    if response.status == 429 or response.status >= 500:
                        raise OSError("update_source_unavailable")
                    require(response.status == 200, "update_download_failed")
                    size = 0
                    fd = os.open(partial, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
                    with os.fdopen(fd, "wb") as stream:
                        while block := response.read(1024 * 1024):
                            size += len(block)
                            require(size <= max_bytes, "update_too_large")
                            stream.write(block)
                    os.replace(partial, output)
                    return output
                finally:
                    conn.close()
            raise ValueError("too_many_redirects")
        except OSError:
            partial.unlink(missing_ok=True)
            if attempt == 2:
                raise
            time.sleep(attempt + 1)
        except BaseException:
            partial.unlink(missing_ok=True)
            raise

def main():
    command, *args = sys.argv[1:]
    if command == "detect": print(json.dumps(detect()))
    elif command == "inspect": inspect(*args)
    elif command == "download": fetch(args[0], args[2].split(","), args[1])
    else: raise ValueError("unknown_operation")

if __name__ == "__main__":
    main()
