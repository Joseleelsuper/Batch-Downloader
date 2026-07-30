"""Implementa las responsabilidades del módulo `catalog_projection`.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession


@dataclass(frozen=True)
class CatalogProjectionReport:
    """Representa el componente `CatalogProjectionReport`.
    """
    source_mismatches: int
    """Atributo de clase `source_mismatches` de `CatalogProjectionReport`.
    """
    app_mismatches: int
    """Atributo de clase `app_mismatches` de `CatalogProjectionReport`.
    """
    counter_row_present: bool
    """Atributo de clase `counter_row_present` de `CatalogProjectionReport`.
    """
    stored_total: int | None
    """Atributo de clase `stored_total` de `CatalogProjectionReport`.
    """
    stored_available: int | None
    """Atributo de clase `stored_available` de `CatalogProjectionReport`.
    """
    stored_review: int | None
    """Atributo de clase `stored_review` de `CatalogProjectionReport`.
    """
    stored_missing: int | None
    """Atributo de clase `stored_missing` de `CatalogProjectionReport`.
    """
    stored_version: int | None
    """Atributo de clase `stored_version` de `CatalogProjectionReport`.
    """
    expected_total: int
    """Atributo de clase `expected_total` de `CatalogProjectionReport`.
    """
    expected_available: int
    """Atributo de clase `expected_available` de `CatalogProjectionReport`.
    """
    expected_review: int
    """Atributo de clase `expected_review` de `CatalogProjectionReport`.
    """
    expected_missing: int
    """Atributo de clase `expected_missing` de `CatalogProjectionReport`.
    """

    @property
    def consistent(self) -> bool:
        """Ejecuta `consistent` dentro de `CatalogProjectionReport`.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        stored_partition = (
            self.stored_total is not None
            and self.stored_available is not None
            and self.stored_review is not None
            and self.stored_missing is not None
            and self.stored_total
            == self.stored_available + self.stored_review + self.stored_missing
        )
        return (
            self.source_mismatches == 0
            and self.app_mismatches == 0
            and self.counter_row_present
            and stored_partition
            and self.stored_total == self.expected_total
            and self.stored_available == self.expected_available
            and self.stored_review == self.expected_review
            and self.stored_missing == self.expected_missing
        )

    def log_fields(self) -> dict[str, int | bool | None]:
        """Ejecuta `log_fields` dentro de `CatalogProjectionReport`.

        Returns:
            dict[str, int | bool | None]: Mapa con los datos producidos por la operación.
        """
        return {**asdict(self), "consistent": self.consistent}


class CatalogProjectionRepository:
    """Gestiona la persistencia y consulta de `CatalogProjection`.
    """

    def __init__(self, session: AsyncSession) -> None:
        """Inicializa una instancia de `CatalogProjectionRepository`.

        Args:
            session (AsyncSession): Sesión de base de datos utilizada por la operación.
        """
        self.session = session
        """Estado de instancia asociado a `session`.
        """

    async def check(self) -> CatalogProjectionReport:
        """Ejecuta `check` dentro de `CatalogProjectionRepository`.

        Returns:
            CatalogProjectionReport: Resultado producido por la operación.
        """
        source_mismatches = int(
            await self.session.scalar(
                text(
                    """
                    SELECT COUNT(*)
                    FROM download_sources AS ds
                    LEFT JOIN (
                        SELECT download_source_id, COUNT(*) AS expected_count
                        FROM resolved_sources
                        WHERE catalog_downloadable = 1
                        GROUP BY download_source_id
                    ) AS expected ON expected.download_source_id = ds.id
                    WHERE ds.catalog_downloadable_count
                        <> COALESCE(expected.expected_count, 0)
                    """
                )
            )
            or 0
        )
        app_mismatches = int(
            await self.session.scalar(
                text(
                    """
                    SELECT COUNT(*)
                    FROM software_apps AS app
                    LEFT JOIN (
                        SELECT
                            software_app_id,
                            SUM(catalog_available = 1) AS expected_available,
                            SUM(
                                resolution_status = 'requires_manual_review'
                            ) AS expected_review
                        FROM download_sources
                        GROUP BY software_app_id
                    ) AS expected ON expected.software_app_id = app.id
                    WHERE app.catalog_available_source_count
                            <> COALESCE(expected.expected_available, 0)
                       OR app.catalog_review_source_count
                            <> COALESCE(expected.expected_review, 0)
                    """
                )
            )
            or 0
        )
        expected = (
            await self.session.execute(
                text(
                    """
                    SELECT
                        COUNT(catalog_status) AS total_count,
                        COALESCE(SUM(catalog_status = 'available'), 0) AS available_count,
                        COALESCE(SUM(catalog_status = 'review'), 0) AS review_count,
                        COALESCE(SUM(catalog_status = 'missing'), 0) AS missing_count
                    FROM software_apps
                    """
                )
            )
        ).mappings().one()
        stored = (
            await self.session.execute(
                text(
                    """
                    SELECT
                        total_count,
                        available_count,
                        review_count,
                        missing_count,
                        version
                    FROM catalog_counters
                    WHERE id = 1
                    """
                )
            )
        ).mappings().one_or_none()
        return CatalogProjectionReport(
            source_mismatches=source_mismatches,
            app_mismatches=app_mismatches,
            counter_row_present=stored is not None,
            stored_total=int(stored["total_count"]) if stored else None,
            stored_available=int(stored["available_count"]) if stored else None,
            stored_review=int(stored["review_count"]) if stored else None,
            stored_missing=int(stored["missing_count"]) if stored else None,
            stored_version=int(stored["version"]) if stored else None,
            expected_total=int(expected["total_count"]),
            expected_available=int(expected["available_count"]),
            expected_review=int(expected["review_count"]),
            expected_missing=int(expected["missing_count"]),
        )

    async def repair(self, lock_timeout_seconds: int = 30) -> CatalogProjectionReport:
        # Conserva el argumento por compatibilidad con la CLI. El timeout de transacción
        # de InnoDB gobierna este bloqueo de fila y, a diferencia de un bloqueo consultivo
        # con nombre, no puede filtrarse al pool de conexiones.
        """Ejecuta `repair` dentro de `CatalogProjectionRepository`.

        Args:
            lock_timeout_seconds (int): Valor de `lock_timeout_seconds` utilizado por la operación.

        Returns:
            CatalogProjectionReport: Resultado producido por la operación.
        """
        del lock_timeout_seconds
        try:
            await self.session.execute(
                text(
                    """
                    INSERT IGNORE INTO catalog_counters (
                        id,
                        total_count,
                        available_count,
                        review_count,
                        missing_count,
                        version,
                        updated_at
                    ) VALUES (1, 0, 0, 0, 0, 0, UTC_TIMESTAMP(6))
                    """
                )
            )
            await self.session.execute(
                text("SELECT id FROM catalog_counters WHERE id = 1 FOR UPDATE")
            )
        # Parte de los estados de aplicación materializados. Las transiciones posteriores
        # pueden aplicar deltas con signo sin producir valores negativos, incluso cuando
        # el propio singleton fue la parte dañada durante una restauración.
            await self.session.execute(
                text(
                    """
                    UPDATE catalog_counters
                    SET
                        total_count = (
                            SELECT COUNT(catalog_status) FROM software_apps
                        ),
                        available_count = (
                            SELECT COALESCE(SUM(catalog_status = 'available'), 0)
                            FROM software_apps
                        ),
                        review_count = (
                            SELECT COALESCE(SUM(catalog_status = 'review'), 0)
                            FROM software_apps
                        ),
                        missing_count = (
                            SELECT COALESCE(SUM(catalog_status = 'missing'), 0)
                            FROM software_apps
                        ),
                        version = version + 1,
                        updated_at = UTC_TIMESTAMP(6)
                    WHERE id = 1
                    """
                )
            )
            await self.session.execute(
                text(
                    """
                    UPDATE download_sources AS ds
                    LEFT JOIN (
                        SELECT download_source_id, COUNT(*) AS expected_count
                        FROM resolved_sources
                        WHERE catalog_downloadable = 1
                        GROUP BY download_source_id
                    ) AS expected ON expected.download_source_id = ds.id
                    SET ds.catalog_downloadable_count = COALESCE(expected.expected_count, 0)
                    WHERE ds.catalog_downloadable_count
                        <> COALESCE(expected.expected_count, 0)
                    """
                )
            )
            await self.session.execute(
                text(
                    """
                    UPDATE software_apps AS app
                    LEFT JOIN (
                        SELECT
                            software_app_id,
                            SUM(catalog_available = 1) AS expected_available,
                            SUM(
                                resolution_status = 'requires_manual_review'
                            ) AS expected_review
                        FROM download_sources
                        GROUP BY software_app_id
                    ) AS expected ON expected.software_app_id = app.id
                    SET
                        app.catalog_available_source_count = COALESCE(
                            expected.expected_available,
                            0
                        ),
                        app.catalog_review_source_count = COALESCE(
                            expected.expected_review,
                            0
                        )
                    WHERE app.catalog_available_source_count
                            <> COALESCE(expected.expected_available, 0)
                       OR app.catalog_review_source_count
                            <> COALESCE(expected.expected_review, 0)
                    """
                )
            )
            await self.session.execute(
                text(
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
                    ON DUPLICATE KEY UPDATE
                        total_count = VALUES(total_count),
                        available_count = VALUES(available_count),
                        review_count = VALUES(review_count),
                        missing_count = VALUES(missing_count),
                        version = catalog_counters.version + 1,
                        updated_at = VALUES(updated_at)
                    """
                )
            )
            await self.session.commit()
            return await self.check()
        except Exception:
            await self.session.rollback()
            raise
