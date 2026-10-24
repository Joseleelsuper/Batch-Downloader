"""Comprueba la caducidad real de copias y los límites del borrado local."""
from datetime import datetime, timedelta, timezone
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

MODULE_PATH = Path(__file__).resolve().parents[2] / "ops" / "backups" / "retention.py"
SPEC = importlib.util.spec_from_file_location("backup_retention", MODULE_PATH)
retention = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(retention)
NOW = datetime(2026, 10, 24, 12, tzinfo=timezone.utc)


def snapshot(identifier, age):
    return {"id": identifier * 64, "time": (NOW - age).isoformat(),
            "paths": [f"/var/backups/mysql-{identifier}.sql.gz"]}


class BackupRetentionTest(unittest.TestCase):
    def test_expires_different_paths_even_without_a_new_backup(self):
        copies = [snapshot("a", timedelta(days=30)), snapshot("b", timedelta(days=2)),
                  snapshot("c", timedelta(hours=12))]
        self.assertEqual(["a" * 64, "b" * 64], retention.expired_snapshots(copies, NOW, 86400))

    def test_removes_at_boundary_with_margin_for_next_purge(self):
        copies = [snapshot("a", timedelta(hours=23, minutes=55)),
                  snapshot("b", timedelta(hours=23, minutes=54, seconds=59))]
        self.assertEqual(["a" * 64], retention.expired_snapshots(copies, NOW, 86400))

    def test_rejects_invalid_ids_and_future_or_naive_times(self):
        for invalid in ({"id": "--all", "time": NOW.isoformat()},
                        {"id": "a" * 64, "time": NOW.replace(tzinfo=None).isoformat()},
                        snapshot("a", -timedelta(hours=1)), None):
            with self.subTest(invalid=invalid), self.assertRaises((ValueError, TypeError)):
                retention.expired_snapshots([invalid], NOW, 86400)

    def test_rejects_retention_above_24h_or_without_purge_margin(self):
        for seconds in (0, 300, 86401):
            with self.subTest(seconds=seconds), self.assertRaises(ValueError):
                retention.expired_snapshots([], NOW, seconds)

    def test_local_cleanup_preserves_recent_unrelated_and_external_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            directory = root / "mysql"
            directory.mkdir()
            old = directory / "old.sql.gz"
            recent = directory / "recent.sql.gz"
            unrelated = directory / "settings.env"
            outside = root / "outside.sql.gz"
            for path in (old, recent, unrelated, outside):
                path.write_text("fixture", encoding="utf-8")
                stamp = (NOW - timedelta(days=2)).timestamp()
                os.utime(path, (stamp, stamp))
            stamp = (NOW - timedelta(hours=1)).timestamp()
            os.utime(recent, (stamp, stamp))
            self.assertEqual(1, retention.prune_local(root, NOW, 86400))
            self.assertFalse(old.exists())
            self.assertTrue(all(path.exists() for path in (recent, unrelated, outside)))

    def test_local_cleanup_does_not_follow_directory_symlinks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            external = root / "external"
            external.mkdir()
            old = external / "old.sql.gz"
            old.write_text("fixture", encoding="utf-8")
            stamp = (NOW - timedelta(days=2)).timestamp()
            os.utime(old, (stamp, stamp))
            try:
                (root / "mysql").symlink_to(external, target_is_directory=True)
            except OSError:
                self.skipTest("El entorno no permite crear enlaces simbólicos")
            self.assertEqual(0, retention.prune_local(root, NOW, 86400))
            self.assertTrue(old.exists())


@unittest.skipUnless(shutil.which("restic") and hasattr(os, "geteuid") and os.geteuid() == 0,
                     "La integración necesita Restic y Bash en un contenedor Linux desechable")
class BackupRetentionIntegrationTest(unittest.TestCase):
    def test_real_snapshots_expire_and_listing_failure_prevents_prune(self):
        """Ejecuta el script real contra repositorios Restic locales sin contactar con OCI."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            repositories = root / "repositories"
            repositories.mkdir()
            env = dict(os.environ, RESTIC_PASSWORD="retention-test-only", TZ="UTC")

            def run(*args, **kwargs):
                result = subprocess.run(args, env=env, text=True, capture_output=True, **kwargs)
                self.assertEqual(0, result.returncode, result.stderr + result.stdout)
                return result

            binary = shutil.which("restic")
            for name in retention.REPOSITORIES:
                run(binary, "-r", str(repositories / name), "init")
            mysql = str(repositories / "mysql")
            old = root / "old-personal-data.txt"
            old.write_text("old fixture", encoding="utf-8")
            old_time = (datetime.now(timezone.utc) - timedelta(days=2)).strftime("%Y-%m-%d %H:%M:%S")
            run(binary, "-r", mysql, "backup", "--time", old_time, str(old))
            # También debe caducar la última copia de un repositorio sin copias recientes.
            run(binary, "-r", str(repositories / "postgres"), "backup", "--time", old_time, str(old))
            recent = root / "recent-personal-data.txt"
            recent.write_text("recent fixture", encoding="utf-8")
            run(binary, "-r", mysql, "backup", str(recent))
            backup_root = root / "backups"
            (backup_root / "mysql").mkdir(parents=True)
            local_old = backup_root / "mysql" / "old.sql.gz"
            local_old.write_text("fixture", encoding="utf-8")
            stamp = (datetime.now(timezone.utc) - timedelta(days=2)).timestamp()
            os.utime(local_old, (stamp, stamp))
            config = root / "backup.env"
            config.write_text("OCI_S3_ENDPOINT=https://unused.invalid\nOCI_S3_REGION=test\n"
                              "OCI_BACKUP_BUCKET=test\nAWS_ACCESS_KEY_ID=test\n"
                              "AWS_SECRET_ACCESS_KEY=test\nRESTIC_PASSWORD=retention-test-only\n",
                              encoding="utf-8")
            bin_dir = root / "bin"
            bin_dir.mkdir()
            wrapper = bin_dir / "restic"
            wrapper.write_text("#!/bin/sh\nset -eu\nrepo=${RESTIC_REPOSITORY##*/}\n"
                               'printf "%s %s\\n" "$repo" "$1" >> "$TEST_CALLS"\n'
                               'if [ "${FAIL_LISTING:-}" = "$repo" ] && [ "$1" = snapshots ]; then exit 1; fi\n'
                               'export RESTIC_REPOSITORY="$TEST_REPOSITORIES/$repo"\n'
                               f'exec "{binary}" "$@"\n', encoding="utf-8")
            wrapper.chmod(0o755)
            calls = root / "calls"
            env.update(PATH=str(bin_dir) + os.pathsep + env["PATH"],
                       TEST_REPOSITORIES=str(repositories), TEST_CALLS=str(calls),
                       BATCH_BACKUP_CONFIG=str(config), BATCH_BACKUP_ROOT=str(backup_root),
                       BATCH_BACKUP_RETENTION_HELPER=str(MODULE_PATH))
            script = str(MODULE_PATH.with_name("backup.sh"))
            run("bash", script, "purge")
            remaining = json.loads(run(binary, "-r", mysql, "snapshots", "--json").stdout)
            self.assertEqual(1, len(remaining))
            self.assertEqual([str(recent)], remaining[0]["paths"])
            self.assertFalse(local_old.exists())
            self.assertEqual([], json.loads(run(binary, "-r", str(repositories / "postgres"),
                                                 "snapshots", "--json").stdout))
            run(binary, "-r", mysql, "check", "--read-data")

            calls.write_text("", encoding="utf-8")
            env["FAIL_LISTING"] = "mysql"
            failed = subprocess.run(["bash", script, "purge"], env=env, capture_output=True, text=True)
            self.assertNotEqual(0, failed.returncode)
            self.assertNotIn("mysql prune", calls.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
