"""Actualizaciones desde orígenes aprobados, hashes y claves GPG fijadas."""
from __future__ import annotations
import copy
import fnmatch
from pathlib import Path
import shutil
import subprocess
from data import SHA, check_hash, digest, fetch, read_json, require

def run(argv):
    return subprocess.run(argv, check=True, text=True, capture_output=True)

def verify_gpg(file, signature, verification, work):
    require(shutil.which("gpg") and shutil.which("gpgv"), "gpg_and_gpgv_required")
    key = work / "publisher.asc"
    key.write_text(verification["publicKey"], encoding="ascii")
    home = work / "gnupg"
    home.mkdir(mode=0o700)
    info = run(["gpg", "--homedir", str(home), "--batch", "--with-colons",
                "--import-options", "show-only", "--import", str(key)]).stdout
    fingerprints = [line.split(":")[9] for line in info.splitlines() if line.startswith("fpr:")]
    primary_keys = [line for line in info.splitlines() if line.startswith("pub:")]
    require(len(primary_keys) == 1 and fingerprints
            and fingerprints[0].upper() == verification["fingerprint"].upper(),
            "publisher_fingerprint_mismatch")
    ring = work / "publisher.gpg"
    run(["gpg", "--homedir", str(home), "--batch", "--dearmor", "--output", str(ring), str(key)])
    run(["gpgv", "--homedir", str(home), "--keyring", str(ring), str(signature), str(file)])

def update_candidate(c, work):
    update = c["profile"].get("update")
    require(update, "update_requires_new_bundle")
    hosts = update["allowedHosts"]
    info = github_candidate(update, hosts, work) if update["provider"] == "github" \
        else official_candidate(update, hosts, work)
    require(isinstance(info.get("version"), str) and len(info["version"]) <= 100,
            "invalid_update_version")
    name = info.get("filename", Path(c["filename"]).name)
    require(Path(name).name == name and name not in (".", "..")
            and not any(ord(v) < 32 for v in name), "invalid_asset_name")
    expected = info.get("sha256")
    if info.get("checksumUrl"):
        checksums = fetch(info["checksumUrl"], hosts, work / "SHA256SUMS", 1024 ** 2).read_text()
        matches = [line[:64] for line in checksums.splitlines() if checksum_matches(line, name)]
        require(len(matches) == 1, "checksum_asset_missing")
        expected = matches[0]
    verification = c["profile"].get("verification")
    require(expected or verification, "unverifiable_update_requires_new_bundle")
    if expected and expected.lower() == c["sha256"].lower() and info["version"] == c["version"]:
        return None
    payload = fetch(info["url"], hosts, work / name)
    if expected: check_hash(payload, expected)
    if verification:
        require(info.get("signatureUrl"), "update_signature_required")
        signature = fetch(info["signatureUrl"], hosts, work / "signature.asc", 1024 ** 2)
        verify_gpg(payload, signature, verification, work)
    updated = copy.deepcopy(c)
    updated.update(filename=name, sha256=digest(payload), version=info["version"])
    return updated, payload

def github_candidate(update, hosts, work):
    url = "https://api.github.com/repos/" + update["repository"] + "/releases/latest"
    release = read_json(fetch(url, hosts, work / "release.json", 2 * 1024 ** 2))
    require(not release.get("draft") and not release.get("prerelease"), "unstable_release")
    assets = release.get("assets", [])
    selected = release_asset(assets, update["assetPattern"], True)
    checksum = release_asset(assets, update.get("checksumPattern"))
    signature = release_asset(assets, update.get("signaturePattern"))
    info = {"version": release["tag_name"], "url": selected["browser_download_url"],
            "filename": selected["name"], "checksumUrl": checksum.get("browser_download_url"),
            "signatureUrl": signature.get("browser_download_url")}
    github_digest = selected.get("digest") or ""
    if github_digest.startswith("sha256:"):
        info["sha256"] = github_digest[7:]
    return info

def release_asset(assets, pattern, required=False):
    matches = [asset for asset in assets
               if pattern and fnmatch.fnmatchcase(asset.get("name", ""), pattern)]
    require(len(matches) <= 1 and (not required or len(matches) == 1), "ambiguous_release_asset")
    return matches[0] if matches else {}

def official_candidate(update, hosts, work):
    info = read_json(fetch(update["url"], hosts, work / "release.json", 2 * 1024 ** 2))
    require(set(info) <= {"version", "url", "filename", "sha256", "signatureUrl", "checksumUrl"},
            "invalid_official_manifest")
    return info

def checksum_matches(line, name):
    if len(line) < 66 or not SHA.fullmatch(line[:64]):
        return False
    return line[64:66] in ("  ", " *") and line[66:] == name
