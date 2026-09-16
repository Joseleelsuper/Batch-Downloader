"""Amplía los contratos canónicos del catálogo y del pipeline compartido."""

import hashlib
import json
from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260914_0021"
down_revision: str | None = "20260907_0020"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


_EVALUATION_INDEXES = (
    ("resolved_sources", "ix_resolved_sources_status"),
    ("scrape_runs", "ix_scrape_runs_status"),
    ("software_app_tags", "ix_software_app_tags_app"),
    ("software_apps", "ix_software_apps_app_status"),
)


def _index_names(bind: sa.Connection, table_name: str) -> set[str]:
    """Devuelve los índices físicos de una tabla sin depender del dialecto."""
    return {str(index["name"]) for index in sa.inspect(bind).get_indexes(table_name)}


def _foreign_key_names(bind: sa.Connection, table_name: str) -> set[str]:
    """Devuelve nombres de FK para mantener la revisión repetible en entornos parciales."""
    return {
        str(foreign_key["name"])
        for foreign_key in sa.inspect(bind).get_foreign_keys(table_name)
        if foreign_key.get("name")
    }


def _has_unique_key(
    bind: sa.Connection,
    table_name: str,
    columns: tuple[str, ...],
) -> bool:
    inspector = sa.inspect(bind)
    expected = tuple(columns)
    return any(
        index.get("unique") and tuple(index.get("column_names", ())) == expected
        for index in inspector.get_indexes(table_name)
    ) or any(
        tuple(constraint.get("column_names", ())) == expected
        for constraint in inspector.get_unique_constraints(table_name)
    )


def _assert_no_duplicate_request_ids(bind: sa.Connection) -> None:
    duplicate = bind.execute(
        sa.text(
            """
            SELECT request_id
            FROM scrape_runs
            WHERE request_id IS NOT NULL
            GROUP BY request_id
            HAVING COUNT(*) > 1
            LIMIT 1
            """
        )
    ).first()
    if duplicate is not None:
        raise RuntimeError(
            "0021_aborted_duplicate_scrape_run_request_id:" + str(duplicate[0])
        )


def _assert_request_ids_have_commands(bind: sa.Connection) -> None:
    """Evita dejar una FK a medias cuando el histórico contiene solicitudes huérfanas."""
    orphan = bind.execute(
        sa.text(
            """
            SELECT scrape_runs.request_id
            FROM scrape_runs
            LEFT JOIN scraper_commands ON scraper_commands.id = scrape_runs.request_id
            WHERE scrape_runs.request_id IS NOT NULL
              AND scraper_commands.id IS NULL
            LIMIT 1
            """
        )
    ).first()
    if orphan is not None:
        raise RuntimeError(
            "0021_aborted_orphan_scrape_run_request:" + str(orphan[0])
        )


def _assert_legacy_run_links_are_canonical(bind: sa.Connection) -> None:
    """Evita perder una asociación histórica al retirar ``scraper_commands.run_id``."""
    columns = {str(column["name"]) for column in sa.inspect(bind).get_columns("scraper_commands")}
    if "run_id" not in columns:
        return
    mismatch = bind.execute(
        sa.text(
            """
            SELECT scraper_commands.id, scraper_commands.run_id
            FROM scraper_commands
            LEFT JOIN scrape_runs ON scrape_runs.id = scraper_commands.run_id
            WHERE scraper_commands.run_id IS NOT NULL
              AND (
                    scrape_runs.id IS NULL
                    OR scrape_runs.request_id IS NULL
                    OR scrape_runs.request_id <> scraper_commands.id
                  )
            LIMIT 1
            """
        )
    ).first()
    if mismatch is not None:
        raise RuntimeError(
            "0021_aborted_legacy_run_link_mismatch:"
            + f"{mismatch[0]}:{mismatch[1]}"
        )


def _legacy_artifact_fingerprint(row: sa.RowMapping) -> str:
    """Calcula una huella estable para filas antiguas que aún no tenían la columna."""
    metadata = row["metadata_json"]
    if isinstance(metadata, str):
        try:
            metadata = json.loads(metadata)
        except json.JSONDecodeError:
            metadata = {}
    if not isinstance(metadata, dict):
        metadata = {}
    payload = {
        "source": str(row["download_source_id"] or ""),
        "domain": str(row["final_domain"] or "").lower(),
        "filename": str(row["filename"] or "").lower(),
        "extension": str(row["extension"] or "").lower(),
        "size": row["size_bytes"],
        "version": row["version"],
        "sha256": metadata.get("sha256") or metadata.get("expected_sha256"),
        "operating_system": metadata.get("operating_system"),
        "architecture": metadata.get("architecture"),
    }
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _backfill_missing_artifact_fingerprints(bind: sa.Connection) -> None:
    """Rellena sólo filas históricas; nunca decide qué evidencia duplicada conservar."""
    rows = bind.execute(
        sa.text(
            """
            SELECT id, download_source_id, final_domain, filename, extension,
                   size_bytes, version, metadata_json
            FROM resolved_sources
            WHERE artifact_fingerprint IS NULL
            """
        )
    ).mappings()
    statement = sa.text(
        "UPDATE resolved_sources "
        "SET artifact_fingerprint = :artifact_fingerprint "
        "WHERE id = :id AND artifact_fingerprint IS NULL"
    )
    updates: list[dict[str, object]] = []
    for row in rows:
        updates.append(
            {
                "id": row["id"],
                "artifact_fingerprint": _legacy_artifact_fingerprint(row),
            }
        )
        if len(updates) == 1_000:
            bind.execute(statement, updates)
            updates.clear()
    if updates:
        bind.execute(statement, updates)


def _assert_no_missing_artifact_fingerprints(bind: sa.Connection) -> None:
    missing = bind.execute(
        sa.text(
            "SELECT COUNT(*) FROM resolved_sources "
            "WHERE artifact_fingerprint IS NULL"
        )
    ).scalar_one()
    if int(missing or 0):
        raise RuntimeError(
            "0021_aborted_null_artifact_fingerprint:" + str(int(missing))
        )
    duplicate = bind.execute(
        sa.text(
            """
            SELECT download_source_id, artifact_fingerprint
            FROM resolved_sources
            GROUP BY download_source_id, artifact_fingerprint
            HAVING COUNT(*) > 1
            LIMIT 1
            """
        )
    ).first()
    if duplicate is not None:
        raise RuntimeError(
            "0021_aborted_duplicate_resolved_source_fingerprint:"
            + f"{duplicate[0]}:{duplicate[1]}"
        )


def _set_index_visibility(bind: sa.Connection, visible: bool) -> None:
    """Marca índices para evaluación sólo donde MySQL soporta invisibilidad."""
    if bind.dialect.name != "mysql":
        return
    visibility = "VISIBLE" if visible else "INVISIBLE"
    for table_name, index_name in _EVALUATION_INDEXES:
        if index_name in _index_names(bind, table_name):
            bind.execute(
                sa.text(
                    f"ALTER TABLE `{table_name}` "
                    f"ALTER INDEX `{index_name}` {visibility}"
                )
            )


def upgrade() -> None:
    """Crea contratos nuevos sin retirar todavía ningún objeto histórico."""
    bind = op.get_bind()
    _assert_no_duplicate_request_ids(bind)
    _assert_request_ids_have_commands(bind)
    _assert_legacy_run_links_are_canonical(bind)
    _backfill_missing_artifact_fingerprints(bind)
    _assert_no_missing_artifact_fingerprints(bind)

    if "ix_scrape_runs_request_id" in _index_names(bind, "scrape_runs"):
        op.drop_index("ix_scrape_runs_request_id", table_name="scrape_runs")
    if not _has_unique_key(bind, "scrape_runs", ("request_id",)):
        op.create_index(
            "uq_scrape_runs_request_id",
            "scrape_runs",
            ["request_id"],
            unique=True,
        )
    if "fk_scrape_runs_request_command" not in _foreign_key_names(bind, "scrape_runs"):
        if bind.dialect.name == "sqlite":
            with op.batch_alter_table("scrape_runs") as batch:
                batch.create_foreign_key(
                    "fk_scrape_runs_request_command",
                    "scraper_commands",
                    ["request_id"],
                    ["id"],
                    ondelete="SET NULL",
                )
        else:
            op.create_foreign_key(
                "fk_scrape_runs_request_command",
                "scrape_runs",
                "scraper_commands",
                ["request_id"],
                ["id"],
                ondelete="SET NULL",
            )

    if not _has_unique_key(
        bind,
        "resolved_sources",
        ("download_source_id", "artifact_fingerprint"),
    ):
        op.create_index(
            "uq_resolved_sources_source_fingerprint",
            "resolved_sources",
            ["download_source_id", "artifact_fingerprint"],
            unique=True,
        )
    if bind.dialect.name == "sqlite":
        with op.batch_alter_table("resolved_sources") as batch:
            batch.alter_column(
                "artifact_fingerprint",
                existing_type=sa.String(64),
                nullable=False,
            )
    else:
        op.alter_column(
            "resolved_sources",
            "artifact_fingerprint",
            existing_type=sa.String(64),
            nullable=False,
        )

    if "ix_software_apps_os_refresh" not in _index_names(bind, "software_apps"):
        op.create_index(
            "ix_software_apps_os_refresh",
            "software_apps",
            ["app_status", "operating_systems_updated_at", "id"],
        )

    # La fase expansiva deja estos índices observables e invisibles; sólo 0022 los retirará
    # después de comparar planes y tiempos de consulta.
    _set_index_visibility(bind, visible=False)


def downgrade() -> None:
    """Revierte la expansión y vuelve a hacer visibles los índices evaluados."""
    bind = op.get_bind()
    _set_index_visibility(bind, visible=True)
    if "ix_software_apps_os_refresh" in _index_names(bind, "software_apps"):
        op.drop_index("ix_software_apps_os_refresh", table_name="software_apps")
    if "uq_resolved_sources_source_fingerprint" in _index_names(
        bind, "resolved_sources"
    ):
        op.drop_index(
            "uq_resolved_sources_source_fingerprint",
            table_name="resolved_sources",
        )
    if "resolved_sources" in sa.inspect(bind).get_table_names():
        if bind.dialect.name == "sqlite":
            with op.batch_alter_table("resolved_sources") as batch:
                batch.alter_column(
                    "artifact_fingerprint",
                    existing_type=sa.String(64),
                    nullable=True,
                )
        else:
            op.alter_column(
                "resolved_sources",
                "artifact_fingerprint",
                existing_type=sa.String(64),
                nullable=True,
            )
    if "fk_scrape_runs_request_command" in _foreign_key_names(bind, "scrape_runs"):
        if bind.dialect.name == "sqlite":
            with op.batch_alter_table("scrape_runs") as batch:
                batch.drop_constraint("fk_scrape_runs_request_command", type_="foreignkey")
        else:
            op.drop_constraint(
                "fk_scrape_runs_request_command",
                "scrape_runs",
                type_="foreignkey",
            )
    if "uq_scrape_runs_request_id" in _index_names(bind, "scrape_runs"):
        op.drop_index("uq_scrape_runs_request_id", table_name="scrape_runs")
    if "ix_scrape_runs_request_id" not in _index_names(bind, "scrape_runs"):
        op.create_index("ix_scrape_runs_request_id", "scrape_runs", ["request_id"])
