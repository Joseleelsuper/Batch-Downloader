"""Fixtures locales: no se descargan ni ejecutan instaladores de terceros."""
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import uuid

REPO = Path(__file__).resolve().parents[3]
RUNTIME = REPO / "services/download-worker/src/main/resources/linux-installer"
sys.path.insert(0, str(RUNTIME / "lib"))
import data
import runtime
import updates


class DataTests(unittest.TestCase):
    def test_declarative_validation_rejects_commands_and_paths(self):
        for profile in (
            {"schemaVersion": 1, "strategy": "deb", "command": "touch /tmp/owned"},
            {"schemaVersion": 1, "strategy": "tarball", "entrypoint": "../evil"},
            {"schemaVersion": 1, "strategy": "tarball"},
            {"schemaVersion": 1, "strategy": "deb", "systemPackages": {"apt": ["--help"]}},
            {"schemaVersion": 1, "strategy": "deb", "dependencies": "not-a-list"},
        ):
            with self.subTest(profile=profile), self.assertRaises(ValueError):
                data.validate_profile(profile)

    def test_cycle_and_missing_dependency(self):
        components = {
            "a": {"profile": {"dependencies": ["b"]}},
            "b": {"profile": {"dependencies": ["a"]}},
        }
        with self.assertRaisesRegex(ValueError, "dependency_cycle"):
            runtime.ordered(components, None)
        with self.assertRaisesRegex(ValueError, "missing_component"):
            runtime.ordered({"a": components["a"]}, None)
        components["b"]["profile"]["dependencies"] = []
        self.assertEqual(runtime.ordered(components, ["a"]), ["b", "a"])

    def test_platform_incompatibility(self):
        c = {"profile": {"strategy": "rpm"}, "architecture": "x86_64"}
        self.assertFalse(runtime.compatible(c, {"manager": "apt", "architecture": "x86_64"}))
        self.assertFalse(runtime.compatible(c, {"manager": "dnf", "architecture": "aarch64"}))

    def test_archive_escaping_and_devices_rejected_before_extraction(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name, type_, link in (
                ("../outside", tarfile.REGTYPE, ""),
                ("/absolute", tarfile.REGTYPE, ""),
                ("symlink", tarfile.SYMTYPE, "../../outside"),
                ("device", tarfile.CHRTYPE, ""),
            ):
                with self.subTest(name=name):
                    archive = root / "bad.tar.gz"
                    with tarfile.open(archive, "w:gz") as output:
                        member = tarfile.TarInfo(name)
                        member.type, member.linkname = type_, link
                        output.addfile(member)
                    destination = root / "destination"
                    destination.mkdir(exist_ok=True)
                    with self.assertRaises(ValueError):
                        data.extract(archive, destination)
                    self.assertEqual(list(destination.iterdir()), [])

    def test_download_rejects_private_dns_without_network(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(data.socket, "getaddrinfo",
                return_value=[(2, 1, 6, "", ("127.0.0.1", 443))]):
            with self.assertRaises(ValueError):
                data.fetch("https://allowed.example/file", ["allowed.example"], Path(tmp) / "payload")
            self.assertEqual(list(Path(tmp).iterdir()), [])

    def test_checksum_and_duplicate_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "file"
            path.write_text("fixture")
            with self.assertRaisesRegex(ValueError, "sha256_mismatch"):
                data.check_hash(path, "0" * 64)
            path.write_text('{"strategy":"deb","strategy":"manual"}')
            with self.assertRaisesRegex(ValueError, "duplicate_json_key"):
                data.read_json(path)

    def test_multiple_primary_gpg_keys_rejected(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(updates.shutil, "which", return_value="/gpg"), \
                patch.object(updates, "run") as command:
            command.return_value.stdout = "pub:::::::::\nfpr:::::::::AAAA:\npub:::::::::\nfpr:::::::::BBBB:\n"
            with self.assertRaisesRegex(ValueError, "publisher_fingerprint_mismatch"):
                updates.verify_gpg(Path(tmp) / "file", Path(tmp) / "signature",
                                   {"publicKey": "PUBLIC", "fingerprint": "AAAA"}, Path(tmp))
            self.assertEqual(command.call_count, 1)


@unittest.skipIf(os.geteuid() == 0, "Las pruebas portables requieren un usuario no root.")
class PortableTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="bd-tests-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.home = self.root / "home"
        self.home.mkdir()
        self.bundle = self.root / "bundle with spaces"
        shutil.copytree(RUNTIME, self.bundle, ignore=shutil.ignore_patterns("__pycache__"))
        self.env = {**os.environ, "HOME": str(self.home),
                    "XDG_STATE_HOME": str(self.home / "state"),
                    "XDG_DATA_HOME": str(self.home / "data"),
                    "PYTHONDONTWRITEBYTECODE": "1"}
        self.app = str(uuid.uuid4())
        self.bundle_id = str(uuid.uuid4())
        self.descriptors = self.bundle / "config/components"
        self.descriptors.mkdir()
        (self.bundle / "config/bundle.json").write_text(json.dumps({"schemaVersion": 1, "id": self.bundle_id}))
        (self.bundle / "config/packages.conf").write_text(self.app + "\n")
        self.write_component("1")

    def write_component(self, version):
        filename = "fixture-" + version + ".tar.gz"
        payload = self.bundle / filename
        with tarfile.open(payload, "w:gz") as archive:
            member = tarfile.TarInfo("app/run")
            content = ("#!/bin/sh\nprintf '%s\\n' " + version + "\n").encode()
            member.size, member.mode = len(content), 0o755
            archive.addfile(member, io.BytesIO(content))
        self.component = {
            "schemaVersion": 1, "id": self.app, "appId": self.app, "name": "Fixture",
            "version": version, "filename": filename, "sha256": data.digest(payload),
            "architecture": "any", "profile": {
                "schemaVersion": 1, "strategy": "tarball", "entrypoint": "app/run", "scope": "user",
            },
        }
        (self.descriptors / (self.app + ".json")).write_text(json.dumps(self.component))

    def command(self, action, *args, success=True, persisted=False):
        entry = self.home / ".local/bin/batch-linux-installer" if persisted else self.bundle / (action + ".sh")
        argv = ["bash", str(entry), *([action, self.bundle_id] if persisted else []), "--silent", *args]
        result = subprocess.run(argv, text=True, capture_output=True, env=self.env)
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout)
        return result

    def installed_version(self):
        launcher = self.home / ".local/bin" / ("batch-app-" + self.app)
        return subprocess.check_output([str(launcher)], env=self.env, text=True).strip()

    def test_dry_run_writes_nothing(self):
        before = {str(p): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        self.command("install", "--dry-run")
        after = {str(p): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        self.assertEqual(before, after)
        self.assertEqual(list(self.home.iterdir()), [])

    def test_install_incremental_rollback_and_uninstall_without_zip(self):
        self.command("install")
        self.assertEqual(self.installed_version(), "1")
        self.command("install")
        self.write_component("2")
        self.command("install")
        self.assertEqual(self.installed_version(), "2")
        self.command("rollback")
        self.assertEqual(self.installed_version(), "1")
        shutil.rmtree(self.bundle)
        self.command("uninstall", "--purge", persisted=True)
        self.assertFalse((self.home / ".local/bin" / ("batch-app-" + self.app)).exists())
        self.assertFalse((self.home / "state/batch-linux-installer").exists())
        self.assertFalse(any((self.home / "data/batch-linux-installer/apps").iterdir()))

    def test_shared_component_survives_first_bundle_removal(self):
        self.command("install")
        first_id = self.bundle_id
        self.bundle_id = str(uuid.uuid4())
        (self.bundle / "config/bundle.json").write_text(json.dumps({"schemaVersion": 1, "id": self.bundle_id}))
        self.command("install")
        self.command("uninstall")
        self.assertEqual(self.installed_version(), "1")
        self.bundle_id = first_id
        self.command("uninstall", persisted=True)

    def test_changed_hash_does_not_modify_installed_application(self):
        self.command("install")
        self.write_component("2")
        (self.bundle / self.component["filename"]).write_bytes(b"corrupted")
        self.command("install", success=False)
        self.assertEqual(self.installed_version(), "1")

    def test_failed_upgrade_restores_previous_launcher(self):
        self.command("install")
        self.write_component("2")
        self.component["profile"]["entrypoint"] = "missing"
        (self.descriptors / (self.app + ".json")).write_text(json.dumps(self.component))
        self.command("install", success=False)
        self.assertEqual(self.installed_version(), "1")
        self.assertFalse((self.home / "state/batch-linux-installer/journal.json").exists())


if __name__ == "__main__":
    unittest.main()
