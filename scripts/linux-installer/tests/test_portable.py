"""Comprueba permisos y ejecución del lanzador común en alcances de usuario y sistema."""
import io
from pathlib import Path
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[3]
                      / "services/download-worker/src/main/resources/linux-installer/lib"))
from portable import prepare_portable  # noqa: E402


class PortablePreparationTests(unittest.TestCase):
    """Mantiene permisos distintos sin alterar argumentos ni activar directorios prematuramente."""

    def test_tarball_permissions_and_quoted_launcher_for_both_scopes(self):
        """Comprueba alcance privado/público y argumentos con espacios al ejecutar el mismo tarball."""
        with tempfile.TemporaryDirectory(prefix="portable recipe ") as temporary:
            root = Path(temporary)
            payload = root / "program.tar.gz"
            script = b'#!/bin/sh\nprintf "%s\\n" "$@"\n'
            with tarfile.open(payload, "w:gz") as archive:
                entry = tarfile.TarInfo("app/run")
                entry.size, entry.mode = len(script), 0o600
                archive.addfile(entry, io.BytesIO(script))
            component = {"profile": {"strategy": "tarball", "entrypoint": "app/run"}}
            for mode, executable_mode in ((0o700, 0o700), (0o755, 0o711)):
                with self.subTest(mode=oct(mode)):
                    stage, destination = root / f"stage {mode}", root / f"installed {mode}"
                    stage.mkdir()
                    prepare_portable(component, payload, stage, destination, mode)
                    self.assertFalse(destination.exists())
                    self.assertEqual(stat.S_IMODE((stage / "launch").stat().st_mode), mode)
                    self.assertEqual(stat.S_IMODE((stage / "payload/app/run").stat().st_mode), executable_mode)
                    stage.rename(destination)
                    result = subprocess.run([str(destination / "launch"), "two words", "literal$arg"],
                                            check=True, capture_output=True, text=True)
                    self.assertEqual(result.stdout.splitlines(), ["two words", "literal$arg"])
