"""Comprueba política de reintento y contadores terminales de workers de filtro y resolución con
errores simulados.
"""

from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from sqlalchemy.exc import OperationalError
from sqlalchemy.exc import TimeoutError as SQLAlchemyTimeoutError

from app.core.config import Settings
from app.core.time import utc_now
from app.db.models import ScraperWorkItem
from app.scraper import worker_recovery
from app.scraper.pipeline_runtime import PipelineRuntime


@pytest.mark.parametrize("stage", ["filter", "scraper"])
@pytest.mark.parametrize("attempts", [2, 4])
@pytest.mark.parametrize(
    ("error", "retry", "terminal"),
    [
        (SQLAlchemyTimeoutError(), "database_pool_retry", "database_pool_timeout"),
        (
            OperationalError(None, None, Exception(1213, "deadlock")),
            "mysql_lock_retry",
            "OperationalError",
        ),
        (OperationalError(None, None, Exception(9999, "other")), None, "OperationalError"),
        (ValueError("invalid"), None, "ValueError"),
    ],
)
async def test_retry_classification_delay_and_terminal_counters(
    monkeypatch, stage, attempts, error, retry, terminal
):
    """Combina etapa, intentos y tipos de fallo de base o validación y comprueba aplazamiento con
    código y demora correctos o finalización con ambos contadores de fallo incrementados.

    Args:
        monkeypatch: Fixture que sustituye la finalización persistente por un doble asíncrono.
        stage: Etapa filter o scraper parametrizada.
        attempts: Intentos previos de la tarea, por debajo del límite o ya agotados.
        error: Excepción simulada de pool, SQL o validación.
        retry: Código esperado de reintento, o None si el fallo es terminal.
        terminal: Código esperado cuando corresponde finalizar la tarea.
    """
    finish = AsyncMock()
    monkeypatch.setattr(worker_recovery, "finish_item", finish)
    settings = Settings()
    runtime = PipelineRuntime(settings=settings, run_id=uuid4(), run_started_at=utc_now())
    item = ScraperWorkItem(attempts=attempts, package_id="Vendor.App")
    await worker_recovery.recover_worker_failure(settings, runtime, item, error, stage)
    if retry and attempts < 4:
        delay = 2 if stage == "scraper" and retry == "mysql_lock_retry" else 4
        finish.assert_awaited_once_with(settings, item, "requeue", retry, delay_seconds=delay)
        assert runtime.counters.apps_failed == runtime.counters.apps_transient_failed == 0
    else:
        finish.assert_awaited_once_with(settings, item, "fail", terminal)
        assert runtime.counters.apps_failed == runtime.counters.apps_transient_failed == 1
