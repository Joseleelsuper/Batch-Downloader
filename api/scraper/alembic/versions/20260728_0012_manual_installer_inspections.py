"""Persist recoverable manual installer inspections.

Revision ID: 20260728_0012
Revises: 20260718_0011
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260728_0012"
down_revision: str | None = "20260718_0011"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "manual_installer_inspections",
        sa.Column("id", sa.BINARY(16), nullable=False),
        sa.Column("software_app_id", sa.BINARY(16), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("phase", sa.String(length=32), nullable=False),
        sa.Column("captured_app_version", sa.BigInteger(), nullable=False),
        sa.Column("input_hash", sa.String(length=64), nullable=False),
        sa.Column("installer_url_encrypted", sa.Text(), nullable=False),
        sa.Column("source_page_url_encrypted", sa.Text(), nullable=False),
        sa.Column("result_json", sa.JSON(), nullable=True),
        sa.Column("warnings_json", sa.JSON(), nullable=False),
        sa.Column("error_code", sa.String(length=120), nullable=True),
        sa.Column("expires_at", sa.DateTime(), nullable=False),
        sa.Column("applied_at", sa.DateTime(), nullable=True),
        sa.Column("applied_app_version", sa.BigInteger(), nullable=True),
        sa.Column("source_ref", sa.BINARY(16), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(
            ["software_app_id"],
            ["software_apps.id"],
            name="fk_manual_installer_inspections_app",
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["source_ref"],
            ["resolved_sources.id"],
            name="fk_manual_installer_inspections_source",
            ondelete="SET NULL",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_manual_installer_inspections_app_status",
        "manual_installer_inspections",
        ["software_app_id", "status", "created_at"],
    )
    op.create_index(
        "ix_manual_installer_inspections_expires",
        "manual_installer_inspections",
        ["expires_at"],
    )


def downgrade() -> None:
    op.drop_table("manual_installer_inspections")
