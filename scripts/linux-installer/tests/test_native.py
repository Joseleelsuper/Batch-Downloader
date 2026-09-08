"""Paquetes sintéticos sin scripts de mantenimiento. Solo dentro del contenedor desechable."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import unittest
import uuid

import test_runtime

data = test_runtime.data


@unittest.skipUnless(
    os.geteuid() != 0 and shutil.which("sudo") and shutil.which("dpkg-deb"),
    "Requiere la imagen aislada de pruebas Debian.",
)
class NativeDebTests(unittest.TestCase):
    setUp = test_runtime.PortableTests.setUp
    command = test_runtime.PortableTests.command

    def write_component(self, version):
        self.package = "bd-fixture-" + self.app[:8]
        source = self.root / ("source-" + version)
        (source / "DEBIAN").mkdir(parents=True)
        (source / "DEBIAN/control").write_text(
            "Package: "
            + self.package
            + "\nVersion: "
            + version
            + "\nArchitecture: all\nMaintainer: Tests <tests@example.invalid>\n"
            "Description: disposable installer test fixture\n"
        )
        target = source / "usr/share" / self.package
        target.mkdir(parents=True)
        (target / "version").write_text(version)
        payload = self.bundle / (self.package + "-" + version + ".deb")
        subprocess.run(
            ["dpkg-deb", "--build", "--root-owner-group", str(source), str(payload)],
            check=True,
            capture_output=True,
        )
        self.component = {
            "schemaVersion": 1,
            "id": self.app,
            "appId": self.app,
            "name": "Native fixture",
            "version": version,
            "filename": payload.name,
            "sha256": data.digest(payload),
            "architecture": "any",
            "profile": {"schemaVersion": 1, "strategy": "deb", "scope": "auto"},
        }
        (self.descriptors / (self.app + ".json")).write_text(json.dumps(self.component))

    def version(self):
        result = subprocess.run(
            ["dpkg-query", "-W", "-f", "${Status}:${Version}", self.package],
            capture_output=True,
            text=True,
        )
        return result.stdout if result.returncode == 0 else ""

    def test_native_install_upgrade_rollback_and_remove(self):
        self.command("install")
        self.assertIn("installed:1", self.version())
        self.write_component("2")
        self.command("install")
        self.assertIn("installed:2", self.version())
        self.command("rollback")
        self.assertIn("installed:1", self.version())
        self.command("uninstall", "--purge", persisted=True)
        self.assertNotIn("installed:", self.version())
        self.assertFalse(
            any(Path("/var/lib/batch-linux-installer/transactions").glob("*.json"))
        )

    def test_preexisting_package_is_not_removed_or_upgraded(self):
        subprocess.run(
            ["sudo", "-n", "dpkg", "-i", str(self.bundle / self.component["filename"])],
            check=True,
            capture_output=True,
        )
        try:
            self.write_component("2")
            self.command("install")
            self.assertIn("installed:1", self.version())
            self.command("uninstall", "--purge")
            self.assertIn("installed:1", self.version())
        finally:
            subprocess.run(
                ["sudo", "-n", "dpkg", "-r", self.package],
                check=True,
                capture_output=True,
            )

    def test_native_component_shared_between_bundles(self):
        self.command("install")
        original = self.bundle_id
        self.bundle_id = str(uuid.uuid4())
        (self.bundle / "config/bundle.json").write_text(
            json.dumps({"schemaVersion": 1, "id": self.bundle_id})
        )
        self.command("install")
        self.command("uninstall")
        self.assertIn("installed:1", self.version())
        self.bundle_id = original
        self.command("uninstall", persisted=True)
        self.assertNotIn("installed:", self.version())


@unittest.skipUnless(
    os.geteuid() != 0
    and shutil.which("sudo")
    and shutil.which("rpmbuild")
    and data.detect()["manager"] in ("dnf", "zypper"),
    "Requiere una imagen aislada Fedora u openSUSE.",
)
class NativeRpmTests(unittest.TestCase):
    setUp = test_runtime.PortableTests.setUp
    command = test_runtime.PortableTests.command

    def write_component(self, version):
        self.package = "bd-fixture-" + self.app[:8]
        topdir = self.root / ("rpm-source-" + version)
        for name in ("BUILD", "BUILDROOT", "RPMS", "SOURCES", "SPECS", "SRPMS"):
            (topdir / name).mkdir(parents=True)
        spec = topdir / "SPECS" / (self.package + ".spec")
        spec.write_text(
            "Name: " + self.package + "\n"
            "Version: " + version + "\n"
            "Release: 1\n"
            "Summary: Disposable installer test fixture\n"
            "License: MIT\n"
            "BuildArch: noarch\n\n"
            "%description\nDisposable installer test fixture.\n\n"
            "%prep\n\n%build\n\n"
            "%install\n"
            "mkdir -p %{buildroot}/usr/share/" + self.package + "\n"
            "printf '%s\\n' '"
            + version
            + "' > %{buildroot}/usr/share/"
            + self.package
            + "/version\n\n"
            "%files\n/usr/share/" + self.package + "/version\n",
            encoding="utf-8",
        )
        subprocess.run(
            ["rpmbuild", "-bb", "--define", "_topdir " + str(topdir), str(spec)],
            check=True,
            capture_output=True,
            text=True,
        )
        built = next((topdir / "RPMS").rglob("*.rpm"))
        payload = self.bundle / built.name
        shutil.copyfile(built, payload)
        self.component = {
            "schemaVersion": 1,
            "id": self.app,
            "appId": self.app,
            "name": "Native RPM fixture",
            "version": version,
            "filename": payload.name,
            "sha256": data.digest(payload),
            "architecture": "any",
            "profile": {"schemaVersion": 1, "strategy": "rpm", "scope": "auto"},
        }
        (self.descriptors / (self.app + ".json")).write_text(json.dumps(self.component))

    def version(self):
        result = subprocess.run(
            ["rpm", "-q", "--qf", "%{VERSION}-%{RELEASE}", "--", self.package],
            capture_output=True,
            text=True,
        )
        return result.stdout if result.returncode == 0 else ""

    def test_native_install_upgrade_rollback_and_remove(self):
        self.command("install")
        self.assertEqual(self.version(), "1-1")
        self.write_component("2")
        self.command("install")
        self.assertEqual(self.version(), "2-1")
        self.command("rollback")
        self.assertEqual(self.version(), "1-1")
        self.command("uninstall", "--purge", persisted=True)
        self.assertEqual(self.version(), "")
        self.assertFalse(
            any(Path("/var/lib/batch-linux-installer/transactions").glob("*.json"))
        )


@unittest.skipUnless(
    os.geteuid() != 0
    and shutil.which("sudo")
    and shutil.which("makepkg")
    and data.detect()["manager"] == "pacman",
    "Requiere una imagen aislada Arch Linux.",
)
class NativeArchTests(unittest.TestCase):
    setUp = test_runtime.PortableTests.setUp
    command = test_runtime.PortableTests.command

    def write_component(self, version):
        self.package = "bd-fixture-" + self.app[:8]
        source = self.root / ("arch-source-" + version)
        source.mkdir()
        (source / "PKGBUILD").write_text(
            "pkgname=" + self.package + "\n"
            "pkgver=" + version + "\n"
            "pkgrel=1\n"
            "pkgdesc='Disposable installer test fixture'\n"
            "arch=('any')\n"
            "license=('MIT')\n"
            "package() {\n"
            '  install -d "$pkgdir/usr/share/' + self.package + '"\n'
            "  printf '%s\\n' '"
            + version
            + "' > \"$pkgdir/usr/share/"
            + self.package
            + '/version"\n'
            "}\n",
            encoding="utf-8",
        )
        environment = {**os.environ, "PKGDEST": str(self.bundle)}
        subprocess.run(
            ["makepkg", "--force", "--noconfirm", "--nodeps", "--cleanbuild"],
            cwd=source,
            env=environment,
            check=True,
            capture_output=True,
            text=True,
        )
        payload = next(
            self.bundle.glob(self.package + "-" + version + "-*.pkg.tar.zst")
        )
        self.component = {
            "schemaVersion": 1,
            "id": self.app,
            "appId": self.app,
            "name": "Native Arch fixture",
            "version": version,
            "filename": payload.name,
            "sha256": data.digest(payload),
            "architecture": "any",
            "profile": {"schemaVersion": 1, "strategy": "arch", "scope": "auto"},
        }
        (self.descriptors / (self.app + ".json")).write_text(json.dumps(self.component))

    def version(self):
        result = subprocess.run(
            ["pacman", "-Q", "--", self.package], capture_output=True, text=True
        )
        return (
            result.stdout.strip().split(maxsplit=1)[1] if result.returncode == 0 else ""
        )

    def test_native_install_upgrade_rollback_and_remove(self):
        self.command("install")
        self.assertEqual(self.version(), "1-1")
        self.write_component("2")
        self.command("install")
        self.assertEqual(self.version(), "2-1")
        self.command("rollback")
        self.assertEqual(self.version(), "1-1")
        self.command("uninstall", "--purge", persisted=True)
        self.assertEqual(self.version(), "")
        self.assertFalse(
            any(Path("/var/lib/batch-linux-installer/transactions").glob("*.json"))
        )
