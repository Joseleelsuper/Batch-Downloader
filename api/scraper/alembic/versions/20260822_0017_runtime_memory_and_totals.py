"""Evita filesorts de ejecuciones y acota los datos temporales regenerables."""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260822_0017"
down_revision: str | None = "20260819_0016"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Añade accesos indexados, limpia caché caducada y publica los totales."""
    op.create_index("ix_scrape_runs_started_at", "scrape_runs", ["started_at"])
    op.create_index(
        "ix_scrape_runs_status_started_at",
        "scrape_runs",
        ["status", "started_at"],
    )

    # Son vistas previas regenerables con TTL; no contienen historial autoritativo.
    op.execute(
        sa.text(
            "DELETE FROM scraper_worker_snapshots "
            "WHERE expires_at < UTC_TIMESTAMP(6)"
        )
    )
    # Las métricas de alta frecuencia solo alimentan las gráficas recientes.
    op.execute(
        sa.text(
            "DELETE FROM scraper_metric_snapshots "
            "WHERE captured_at < UTC_TIMESTAMP(6) - INTERVAL 30 DAY"
        )
    )
    # Los terminales antiguos no se vuelven a reclamar; runs y logs conservan la auditoría.
    op.execute(
        sa.text(
            "DELETE FROM scraper_work_items "
            "WHERE status IN ('completed', 'discarded') "
            "AND updated_at < UTC_TIMESTAMP(6) - INTERVAL 30 DAY"
        )
    )

    op.execute(
        sa.text(
            """
            CREATE VIEW application_totals AS
            SELECT
                total_count AS total_apps,
                available_count AS available_apps,
                review_count AS review_apps,
                missing_count AS missing_installer_apps,
                version,
                updated_at
            FROM catalog_counters
            WHERE id = 1
            """
        )
    )


def downgrade() -> None:
    """Retira la vista y los índices; la limpieza de datos no es reversible."""
    op.execute(sa.text("DROP VIEW IF EXISTS application_totals"))
    op.drop_index("ix_scrape_runs_status_started_at", table_name="scrape_runs")
    op.drop_index("ix_scrape_runs_started_at", table_name="scrape_runs")
