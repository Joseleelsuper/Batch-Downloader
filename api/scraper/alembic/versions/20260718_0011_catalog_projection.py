"""Define la migración de esquema `20260718_0011_catalog_projection`.
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260718_0011"
"""Estado global asociado a `revision`.
"""
down_revision: str | None = "20260716_0010"
"""Estado global asociado a `down_revision`.
"""
branch_labels: str | Sequence[str] | None = None
"""Estado global asociado a `branch_labels`.
"""
depends_on: str | Sequence[str] | None = None
"""Estado global asociado a `depends_on`.
"""


TRIGGERS = (
    "trg_resolved_sources_catalog_ai",
    "trg_resolved_sources_catalog_au",
    "trg_resolved_sources_catalog_ad",
    "trg_download_sources_catalog_ai",
    "trg_download_sources_catalog_au",
    "trg_download_sources_catalog_ad",
    "trg_software_apps_catalog_ai",
    "trg_software_apps_catalog_au",
    "trg_software_apps_catalog_ad",
)
"""Constante que define `TRIGGERS`.
"""


def _execute(sql: str) -> None:
    """Ejecuta el paso interno `_execute`.

    Args:
        sql (str): Valor de `sql` utilizado por la operación.
    """
    op.execute(sa.text(sql))


def upgrade() -> None:
            # Las actualizaciones de los disparadores de fuentes forman parte del token
            # de cambio del catálogo. Conserva los microsegundos para que dos transiciones
            # del mismo segundo no colapsen en el valor MAX(updated_at) consumido por Core.
    """Ejecuta la operación `upgrade`.
    """
    _execute(
        "ALTER TABLE software_apps "
        "MODIFY COLUMN updated_at DATETIME(6) NOT NULL"
    )
    _execute(
        """
        ALTER TABLE download_sources
            ADD COLUMN catalog_downloadable_count INT UNSIGNED NOT NULL DEFAULT 0,
            ADD COLUMN catalog_available TINYINT(1)
                GENERATED ALWAYS AS (
                    CASE
                        WHEN resolution_status IN ('direct', 'fallback')
                         AND validation_status = 'valid'
                         AND catalog_downloadable_count > 0
                        THEN 1 ELSE 0
                    END
                ) STORED,
            ADD INDEX ix_download_sources_catalog_available (
                software_app_id,
                catalog_available,
                resolution_status
            )
        """
    )
    _execute(
        """
        ALTER TABLE software_apps
            ADD COLUMN catalog_available_source_count INT UNSIGNED NOT NULL DEFAULT 0,
            ADD COLUMN catalog_review_source_count INT UNSIGNED NOT NULL DEFAULT 0,
            ADD COLUMN catalog_status VARCHAR(16)
                GENERATED ALWAYS AS (
                    CASE
                        WHEN app_status <> 'active' THEN NULL
                        WHEN catalog_available_source_count > 0 THEN 'available'
                        WHEN catalog_review_source_count > 0 THEN 'review'
                        ELSE 'missing'
                    END
                ) STORED,
            ADD INDEX ix_software_apps_catalog_status_updated_name_id (
                catalog_status,
                updated_at DESC,
                normalized_name,
                id
            )
        """
    )
    _execute(
        """
        CREATE TABLE catalog_counters (
            id TINYINT UNSIGNED NOT NULL,
            total_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
            available_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
            review_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
            missing_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
            version BIGINT UNSIGNED NOT NULL DEFAULT 0,
            updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
            CONSTRAINT pk_catalog_counters PRIMARY KEY (id),
            CONSTRAINT ck_catalog_counters_singleton CHECK (id = 1),
            CONSTRAINT ck_catalog_counters_partition CHECK (
                total_count = available_count + review_count + missing_count
            )
        ) ENGINE=InnoDB
        """
    )

    # Realiza una única carga inicial antes de instalar los disparadores. La caducidad
    # se omite deliberadamente: un candidato validado y estructuralmente seguro sigue
    # siendo seleccionable y se revalida antes de entregar su URL al worker de descarga.
    _execute(
        """
        UPDATE download_sources AS source
        LEFT JOIN (
            SELECT download_source_id, COUNT(*) AS downloadable_count
            FROM resolved_sources
            WHERE catalog_downloadable = 1
            GROUP BY download_source_id
        ) AS candidates ON candidates.download_source_id = source.id
        SET source.catalog_downloadable_count = COALESCE(candidates.downloadable_count, 0)
        """
    )
    _execute(
        """
        UPDATE software_apps AS app
        LEFT JOIN (
            SELECT
                software_app_id,
                SUM(catalog_available = 1) AS available_count,
                SUM(resolution_status = 'requires_manual_review') AS review_count
            FROM download_sources
            GROUP BY software_app_id
        ) AS sources ON sources.software_app_id = app.id
        SET
            app.catalog_available_source_count = COALESCE(sources.available_count, 0),
            app.catalog_review_source_count = COALESCE(sources.review_count, 0)
        """
    )
    _execute(
        """
        INSERT INTO catalog_counters (
            id,
            total_count,
            available_count,
            review_count,
            missing_count,
            version,
            updated_at
        )
        SELECT
            1,
            COUNT(catalog_status),
            COALESCE(SUM(catalog_status = 'available'), 0),
            COALESCE(SUM(catalog_status = 'review'), 0),
            COALESCE(SUM(catalog_status = 'missing'), 0),
            1,
            UTC_TIMESTAMP(6)
        FROM software_apps
        """
    )

    _create_software_app_triggers()
    _create_download_source_triggers()
    _create_resolved_source_triggers()


def _create_software_app_triggers() -> None:
    """Ejecuta el paso interno `_create_software_app_triggers`.
    """
    _execute(
        """
        CREATE TRIGGER trg_software_apps_catalog_ai
        AFTER INSERT ON software_apps
        FOR EACH ROW
        UPDATE catalog_counters
        SET
            total_count = CAST(total_count AS SIGNED)
                + (NEW.catalog_status IS NOT NULL),
            available_count = CAST(available_count AS SIGNED)
                + (NEW.catalog_status <=> 'available'),
            review_count = CAST(review_count AS SIGNED)
                + (NEW.catalog_status <=> 'review'),
            missing_count = CAST(missing_count AS SIGNED)
                + (NEW.catalog_status <=> 'missing'),
            version = version + 1,
            updated_at = UTC_TIMESTAMP(6)
        WHERE id = 1
        """
    )
    _execute(
        """
        CREATE TRIGGER trg_software_apps_catalog_au
        AFTER UPDATE ON software_apps
        FOR EACH ROW
        BEGIN
            IF NOT (OLD.catalog_status <=> NEW.catalog_status) THEN
                UPDATE catalog_counters
                SET
                    total_count = CAST(total_count AS SIGNED)
                        + (NEW.catalog_status IS NOT NULL)
                        - (OLD.catalog_status IS NOT NULL),
                    available_count = CAST(available_count AS SIGNED)
                        + (NEW.catalog_status <=> 'available')
                        - (OLD.catalog_status <=> 'available'),
                    review_count = CAST(review_count AS SIGNED)
                        + (NEW.catalog_status <=> 'review')
                        - (OLD.catalog_status <=> 'review'),
                    missing_count = CAST(missing_count AS SIGNED)
                        + (NEW.catalog_status <=> 'missing')
                        - (OLD.catalog_status <=> 'missing'),
                    version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE id = 1;
            END IF;
        END
        """
    )
    _execute(
        """
        CREATE TRIGGER trg_software_apps_catalog_ad
        AFTER DELETE ON software_apps
        FOR EACH ROW
        UPDATE catalog_counters
        SET
            total_count = CAST(total_count AS SIGNED)
                - (OLD.catalog_status IS NOT NULL),
            available_count = CAST(available_count AS SIGNED)
                - (OLD.catalog_status <=> 'available'),
            review_count = CAST(review_count AS SIGNED)
                - (OLD.catalog_status <=> 'review'),
            missing_count = CAST(missing_count AS SIGNED)
                - (OLD.catalog_status <=> 'missing'),
            version = version + 1,
            updated_at = UTC_TIMESTAMP(6)
        WHERE id = 1
        """
    )


def _create_download_source_triggers() -> None:
    """Ejecuta el paso interno `_create_download_source_triggers`.
    """
    _execute(
        """
        CREATE TRIGGER trg_download_sources_catalog_ai
        AFTER INSERT ON download_sources
        FOR EACH ROW
        UPDATE software_apps
        SET
            catalog_available_source_count = CAST(
                catalog_available_source_count AS SIGNED
            ) + (NEW.catalog_available = 1),
            catalog_review_source_count = CAST(
                catalog_review_source_count AS SIGNED
            ) + (NEW.resolution_status = 'requires_manual_review'),
            version = version + 1,
            updated_at = UTC_TIMESTAMP(6)
        WHERE id = NEW.software_app_id
        """
    )
    _execute(
        """
        CREATE TRIGGER trg_download_sources_catalog_au
        AFTER UPDATE ON download_sources
        FOR EACH ROW
        BEGIN
            IF OLD.software_app_id = NEW.software_app_id THEN
                UPDATE software_apps
                SET
                    catalog_available_source_count = CAST(
                        catalog_available_source_count AS SIGNED
                    ) + (NEW.catalog_available = 1) - (OLD.catalog_available = 1),
                    catalog_review_source_count = CAST(
                        catalog_review_source_count AS SIGNED
                    ) + (NEW.resolution_status = 'requires_manual_review')
                      - (OLD.resolution_status = 'requires_manual_review'),
                    version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE id = NEW.software_app_id;
            ELSE
                UPDATE software_apps
                SET
                    catalog_available_source_count = CAST(
                        catalog_available_source_count AS SIGNED
                    ) - (OLD.catalog_available = 1),
                    catalog_review_source_count = CAST(
                        catalog_review_source_count AS SIGNED
                    ) - (OLD.resolution_status = 'requires_manual_review'),
                    version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE id = OLD.software_app_id;

                UPDATE software_apps
                SET
                    catalog_available_source_count = CAST(
                        catalog_available_source_count AS SIGNED
                    ) + (NEW.catalog_available = 1),
                    catalog_review_source_count = CAST(
                        catalog_review_source_count AS SIGNED
                    ) + (NEW.resolution_status = 'requires_manual_review'),
                    version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE id = NEW.software_app_id;
            END IF;
        END
        """
    )
    _execute(
        """
        CREATE TRIGGER trg_download_sources_catalog_ad
        AFTER DELETE ON download_sources
        FOR EACH ROW
        UPDATE software_apps
        SET
            catalog_available_source_count = CAST(
                catalog_available_source_count AS SIGNED
            ) - (OLD.catalog_available = 1),
            catalog_review_source_count = CAST(
                catalog_review_source_count AS SIGNED
            ) - (OLD.resolution_status = 'requires_manual_review'),
            version = version + 1,
            updated_at = UTC_TIMESTAMP(6)
        WHERE id = OLD.software_app_id
        """
    )


def _create_resolved_source_triggers() -> None:
    """Ejecuta el paso interno `_create_resolved_source_triggers`.
    """
    _execute(
        """
        CREATE TRIGGER trg_resolved_sources_catalog_ai
        AFTER INSERT ON resolved_sources
        FOR EACH ROW
        UPDATE download_sources
        SET
            catalog_downloadable_count = CAST(catalog_downloadable_count AS SIGNED)
                + (NEW.catalog_downloadable = 1),
            version = version + 1,
            updated_at = UTC_TIMESTAMP(6)
        WHERE id = NEW.download_source_id
        """
    )
    _execute(
        """
        CREATE TRIGGER trg_resolved_sources_catalog_au
        AFTER UPDATE ON resolved_sources
        FOR EACH ROW
        BEGIN
            IF OLD.download_source_id = NEW.download_source_id THEN
                UPDATE download_sources
                SET
                    catalog_downloadable_count = CAST(catalog_downloadable_count AS SIGNED)
                        + (NEW.catalog_downloadable = 1)
                        - (OLD.catalog_downloadable = 1),
                    version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE id = NEW.download_source_id;
            ELSE
                UPDATE download_sources
                SET
                    catalog_downloadable_count = CAST(catalog_downloadable_count AS SIGNED)
                        - (OLD.catalog_downloadable = 1),
                    version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE id = OLD.download_source_id;

                UPDATE download_sources
                SET
                    catalog_downloadable_count = CAST(catalog_downloadable_count AS SIGNED)
                        + (NEW.catalog_downloadable = 1),
                    version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE id = NEW.download_source_id;
            END IF;
        END
        """
    )
    _execute(
        """
        CREATE TRIGGER trg_resolved_sources_catalog_ad
        AFTER DELETE ON resolved_sources
        FOR EACH ROW
        UPDATE download_sources
        SET
            catalog_downloadable_count = CAST(catalog_downloadable_count AS SIGNED)
                - (OLD.catalog_downloadable = 1),
            version = version + 1,
            updated_at = UTC_TIMESTAMP(6)
        WHERE id = OLD.download_source_id
        """
    )


def downgrade() -> None:
    """Ejecuta la operación `downgrade`.
    """
    for trigger in reversed(TRIGGERS):
        _execute(f"DROP TRIGGER IF EXISTS {trigger}")

    op.drop_table("catalog_counters")
    op.drop_index(
        "ix_software_apps_catalog_status_updated_name_id",
        table_name="software_apps",
    )
    op.drop_column("software_apps", "catalog_status")
    op.drop_column("software_apps", "catalog_review_source_count")
    op.drop_column("software_apps", "catalog_available_source_count")
    op.drop_index(
        "ix_download_sources_catalog_available",
        table_name="download_sources",
    )
    op.drop_column("download_sources", "catalog_available")
    op.drop_column("download_sources", "catalog_downloadable_count")
    _execute(
        "ALTER TABLE software_apps "
        "MODIFY COLUMN updated_at DATETIME NOT NULL"
    )
