"""add app tags and long descriptions

Revision ID: 20260627_0002
Revises: 20260626_0001
Create Date: 2026-06-27
"""

from alembic import op
import sqlalchemy as sa

revision = "20260627_0002"
down_revision = "20260626_0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("software_apps", sa.Column("long_description", sa.Text(), nullable=True))
    op.add_column(
        "software_apps", sa.Column("long_description_language", sa.String(16), nullable=True)
    )
    op.add_column(
        "software_apps",
        sa.Column(
            "long_description_status",
            sa.String(32),
            server_default="pending",
            nullable=False,
        ),
    )
    op.add_column(
        "software_apps", sa.Column("long_description_source", sa.String(50), nullable=True)
    )
    op.add_column(
        "software_apps", sa.Column("long_description_model", sa.String(120), nullable=True)
    )
    op.add_column(
        "software_apps", sa.Column("long_description_generated_at", sa.DateTime(), nullable=True)
    )
    op.add_column(
        "software_apps", sa.Column("long_description_input_hash", sa.String(64), nullable=True)
    )
    op.add_column(
        "software_apps", sa.Column("long_description_error", sa.String(1000), nullable=True)
    )
    op.create_index(
        "ix_software_apps_long_description_status",
        "software_apps",
        ["long_description_status"],
    )
    op.create_index(
        "ix_software_apps_long_description_input_hash",
        "software_apps",
        ["long_description_input_hash"],
    )

    op.create_table(
        "software_app_tags",
        sa.Column("id", sa.BINARY(16), nullable=False),
        sa.Column("software_app_id", sa.BINARY(16), nullable=False),
        sa.Column("tag", sa.String(120), nullable=False),
        sa.Column("normalized_tag", sa.String(120), nullable=False),
        sa.Column("source", sa.String(50), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(["software_app_id"], ["software_apps.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("software_app_id", "normalized_tag", name="uq_software_app_tag"),
    )
    op.create_index("ix_software_app_tags_app", "software_app_tags", ["software_app_id"])
    op.create_index(
        "ix_software_app_tags_normalized_tag",
        "software_app_tags",
        ["normalized_tag"],
    )
    op.execute(
        sa.text(
            """
            INSERT IGNORE INTO software_app_tags
                (id, software_app_id, tag, normalized_tag, source, created_at)
            SELECT
                UNHEX(REPLACE(UUID(), '-', '')),
                software_apps.id,
                LEFT(TRIM(tags.tag), 120),
                LEFT(LOWER(TRIM(tags.tag)), 120),
                'winstall',
                UTC_TIMESTAMP()
            FROM software_apps
            JOIN JSON_TABLE(
                software_apps.metadata_json,
                '$.tags[*]' COLUMNS(tag VARCHAR(120) PATH '$')
            ) AS tags
            WHERE JSON_TYPE(JSON_EXTRACT(software_apps.metadata_json, '$.tags')) = 'ARRAY'
              AND tags.tag IS NOT NULL
              AND TRIM(tags.tag) <> ''
            """
        )
    )


def downgrade() -> None:
    op.drop_index("ix_software_app_tags_normalized_tag", table_name="software_app_tags")
    op.drop_index("ix_software_app_tags_app", table_name="software_app_tags")
    op.drop_table("software_app_tags")
    op.drop_index("ix_software_apps_long_description_input_hash", table_name="software_apps")
    op.drop_index("ix_software_apps_long_description_status", table_name="software_apps")
    op.drop_column("software_apps", "long_description_error")
    op.drop_column("software_apps", "long_description_input_hash")
    op.drop_column("software_apps", "long_description_generated_at")
    op.drop_column("software_apps", "long_description_model")
    op.drop_column("software_apps", "long_description_source")
    op.drop_column("software_apps", "long_description_status")
    op.drop_column("software_apps", "long_description_language")
    op.drop_column("software_apps", "long_description")
