"""Selecciona copias caducadas por edad real y retira ficheros locales sin seguir enlaces."""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
import sys

REPOSITORIES = ("mysql", "postgres", "rabbitmq", "notification", "download-worker")
MAX_RETENTION_SECONDS = 86_400
PURGE_INTERVAL_SECONDS = 300


def cutoff(now: datetime, retention_seconds: int) -> datetime:
    """Descuenta la cadencia del máximo solicitado, limitado a veinticuatro horas."""
    if not PURGE_INTERVAL_SECONDS < retention_seconds <= MAX_RETENTION_SECONDS:
        raise ValueError("backup_retention_must_be_between_301_and_86400_seconds")
    return now - timedelta(seconds=retention_seconds - PURGE_INTERVAL_SECONDS)


def expired_snapshots(snapshots: object, now: datetime, retention_seconds: int) -> list[str]:
    """Valida toda la respuesta antes de seleccionar IDs, independientemente de ruta o etiqueta."""
    if not isinstance(snapshots, list):
        raise ValueError("invalid_restic_snapshot_list")
    deadline = cutoff(now, retention_seconds)
    expired = []
    for snapshot in snapshots:
        if not isinstance(snapshot, dict):
            raise ValueError("invalid_restic_snapshot")
        snapshot_id = snapshot.get("id", "")
        if not isinstance(snapshot_id, str) or not re.fullmatch(r"[0-9a-f]{64}", snapshot_id):
            raise ValueError("invalid_restic_snapshot_id")
        created = datetime.fromisoformat(snapshot["time"].replace("Z", "+00:00"))
        if created.tzinfo is None or created > now + timedelta(minutes=1):
            raise ValueError("invalid_restic_snapshot_time")
        if created <= deadline:
            expired.append(snapshot_id)
    return expired


def prune_local(root: Path, now: datetime, retention_seconds: int) -> int:
    """Elimina solo archivos de copia de los cinco directorios previstos, sin recursión."""
    deadline = cutoff(now, retention_seconds).timestamp()
    root = root.resolve(strict=True)
    removed = 0
    for repository in REPOSITORIES:
        directory = root / repository
        if directory.is_symlink() or not directory.is_dir():
            continue
        for path in directory.iterdir():
            if path.is_symlink() or not path.is_file():
                continue
            if not path.name.endswith((".sql.gz", ".dump", ".tar.gz", ".partial")):
                continue
            if path.stat().st_mtime <= deadline:
                path.unlink()
                removed += 1
    return removed


def main() -> None:
    """La salida de snapshots solo contiene IDs válidos; cualquier error impide su borrado."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("snapshots", "local"))
    parser.add_argument("--retention-seconds", type=int, default=MAX_RETENTION_SECONDS)
    parser.add_argument("--root", type=Path)
    args = parser.parse_args()
    now = datetime.now(timezone.utc)
    if args.mode == "snapshots":
        selected = expired_snapshots(json.load(sys.stdin), now, args.retention_seconds)
        if selected:
            print("\n".join(selected))
    else:
        if args.root is None:
            parser.error("local requires --root")
        print(f"Copias locales caducadas eliminadas: {prune_local(args.root, now, args.retention_seconds)}")


if __name__ == "__main__":
    main()
