"""Retira enlaces y proyecciones obsoletos tras la puerta de compatibilidad."""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260914_0022"
down_revision: str | None = "20260914_0021"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


_EVALUATION_INDEXES = (
    ("resolved_sources", "ix_resolved_sources_status"),
    ("scrape_runs", "ix_scrape_runs_status"),
    ("software_app_tags", "ix_software_app_tags_app"),
    ("software_apps", "ix_software_apps_app_status"),
)


def _index_names(bind: sa.Connection, table_name: str) -> set[str]:
    return {str(index["name"]) for index in sa.inspect(bind).get_indexes(table_name)}


def _column_names(bind: sa.Connection, table_name: str) -> set[str]:
    return {str(column["name"]) for column in sa.inspect(bind).get_columns(table_name)}


def _assert_evaluation_indexes_are_invisible(bind: sa.Connection) -> None:
    """Evita retirar un índice que no haya pasado explícitamente por la puerta de medición."""
    if bind.dialect.name != "mysql":
        return
    for table_name, index_name in _EVALUATION_INDEXES:
        if index_name not in _index_names(bind, table_name):
            continue
        visible = bind.execute(
            sa.text(
                "SELECT IS_VISIBLE FROM information_schema.statistics "
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = :table_name "
                "AND INDEX_NAME = :index_name"
            ),
            {"table_name": table_name, "index_name": index_name},
        ).scalar()
        if str(visible).upper() != "NO":
            raise RuntimeError(
                "0022_aborted_index_not_measured:" + f"{table_name}.{index_name}"
            )


def upgrade() -> None:
    """Elimina objetos sólo cuando la fase de compatibilidad ya ha pasado sus umbrales."""
    bind = op.get_bind()

    # Comprobar la puerta antes de cualquier DDL: MySQL confirma implícitamente los cambios
    # estructurales, así que un fallo posterior no debe dejar un contrato parcialmente reducido.
    _assert_evaluation_indexes_are_invisible(bind)

    if "run_id" in _column_names(bind, "scraper_commands"):
        if "ix_scraper_commands_run_id" in _index_names(bind, "scraper_commands"):
            op.drop_index("ix_scraper_commands_run_id", table_name="scraper_commands")
        op.drop_column("scraper_commands", "run_id")

    # La vista sólo era un alias de catalog_counters; todas las lecturas ya usan el singleton.
    op.execute(sa.text("DROP VIEW IF EXISTS application_totals"))

    # La ejecución de esta revisión presupone que el gate de rendimiento ha confirmado que
    # ninguno de estos índices invisibles es necesario. No se ejecuta OPTIMIZE TABLE.
    for table_name, index_name in _EVALUATION_INDEXES:
        if index_name in _index_names(bind, table_name):
            op.drop_index(index_name, table_name=table_name)


def downgrade() -> None:
    """Restaura el enlace histórico, la vista y los índices para un rollback controlado."""
    bind = op.get_bind()
    if "run_id" not in _column_names(bind, "scraper_commands"):
        op.add_column("scraper_commands", sa.Column("run_id", sa.BINARY(16)))
    if "ix_scraper_commands_run_id" not in _index_names(bind, "scraper_commands"):
        op.create_index(
            "ix_scraper_commands_run_id",
            "scraper_commands",
            ["run_id"],
        )
    op.execute(
        sa.text(
            """
            CREATE OR REPLACE VIEW application_totals AS
            SELECT total_count AS total_apps,
                   available_count AS available_apps,
                   review_count AS review_apps,
                   missing_count AS missing_installer_apps,
                   version, updated_at
            FROM catalog_counters
            WHERE id = 1
            """
        )
    )
    for table_name, index_name, columns in (
        ("resolved_sources", "ix_resolved_sources_status", ["status"]),
        ("scrape_runs", "ix_scrape_runs_status", ["status"]),
        ("software_app_tags", "ix_software_app_tags_app", ["software_app_id"]),
        ("software_apps", "ix_software_apps_app_status", ["app_status"]),
    ):
        if index_name not in _index_names(bind, table_name):
            op.create_index(index_name, table_name, columns)
