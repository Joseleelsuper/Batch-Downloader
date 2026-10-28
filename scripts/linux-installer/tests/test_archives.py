"""Mantiene los límites del tar antes de asignar metadatos o escribir fuera del destino."""
import io
import os
from pathlib import Path
import stat
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[3]
                      / "services/download-worker/src/main/resources/linux-installer/lib"))
import data  # noqa: E402
from portable import prepare_portable  # noqa: E402


class ArchiveTests(unittest.TestCase):
    def test_system_payload_normalizes_implicit_directories_with_permissive_umask(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "program.tar"
            with tarfile.open(archive, "w") as output:
                entry = tarfile.TarInfo("bin/run")
                entry.size, entry.mode = 3, 0o777
                output.addfile(entry, io.BytesIO(b"run"))
            stage = root / "stage"
            stage.mkdir()
            original = os.umask(0)
            try:
                prepare_portable({"profile": {"strategy": "tarball", "entrypoint": "bin/run"}},
                                 archive, stage, root / "installed", 0o755)
            finally:
                os.umask(original)
            for path in (stage / "payload", stage / "payload/bin", stage / "payload/bin/run"):
                self.assertEqual(stat.S_IMODE(path.stat().st_mode) & 0o022, 0)

    def test_regular_files_internal_links_and_permissions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "app.tar.gz"
            with tarfile.open(archive, "w:gz") as output:
                entry = tarfile.TarInfo("app/run")
                entry.size, entry.mode = 3, 0o777
                output.addfile(entry, io.BytesIO(b"run"))
                link = tarfile.TarInfo("app/copy")
                link.type, link.linkname, link.mode = tarfile.LNKTYPE, "app/run", 0o777
                output.addfile(link)
                symlink = tarfile.TarInfo("entry")
                symlink.type, symlink.linkname = tarfile.SYMTYPE, "app/run"
                output.addfile(symlink)
            data.extract(archive, root / "stage")
            self.assertEqual((root / "stage/app/copy").read_bytes(), b"run")
            self.assertEqual((root / "stage/entry").read_bytes(), b"run")
            self.assertEqual(stat.S_IMODE((root / "stage/app/run").stat().st_mode), 0o755)

    def test_hardlink_cannot_follow_a_previously_created_alias_outside(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            victim = root / "victim"
            victim.write_bytes(b"private")
            victim.chmod(0o600)
            destination = root / "work/stage"
            destination.mkdir(parents=True)
            archive = root / "attack.tar"
            with tarfile.open(archive, "w") as output:
                alias = tarfile.TarInfo("a")
                alias.type, alias.linkname = tarfile.SYMTYPE, "."
                output.addfile(alias)
                link = tarfile.TarInfo("leaked")
                link.type, link.linkname, link.mode = tarfile.LNKTYPE, "a/a/../../victim", 0o777
                output.addfile(link)
            with patch.object(tarfile.TarFile, "extraction_filter", staticmethod(tarfile.fully_trusted_filter)):
                with self.assertRaises(ValueError):
                    data.extract(archive, destination)
            self.assertEqual(stat.S_IMODE(victim.stat().st_mode), 0o600)
            self.assertEqual(victim.read_bytes(), b"private")

    def test_large_initial_pax_metadata_is_rejected_during_inspection(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "metadata.tar.gz"
            with tarfile.open(archive, "w:gz", format=tarfile.PAX_FORMAT) as output:
                output.addfile(tarfile.TarInfo("a" * 100000))
            with self.assertRaisesRegex(ValueError, "archive_metadata_too_large"):
                data.inspect("tarball", archive)

    def test_entry_limit_precedes_full_member_materialization(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "entries.tar.gz"
            with tarfile.open(archive, "w:gz") as output:
                for index in range(5):
                    output.addfile(tarfile.TarInfo(f"file-{index}"))
            with patch.object(data, "MAX_ARCHIVE_ENTRIES", 3):
                with self.assertRaisesRegex(ValueError, "too_many_archive_entries"):
                    data.inspect("tarball", archive)
