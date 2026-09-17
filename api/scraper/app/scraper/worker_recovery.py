"""Clasifica fallos de persistencia sin convertir contención temporal en ausencia de
instaladores.
"""

from typing import Literal

from sqlalchemy.exc import OperationalError
from sqlalchemy.exc import TimeoutError as SQLAlchemyTimeoutError

from app.core.config import Settings
from app.core.logging import get_logger
from app.db.models import ScraperWorkItem
from app.scraper.pipeline_runtime import PipelineRuntime, is_transient_mysql_lock_error
from app.scraper.pipeline_support import exception_detail, finish_item

logger = get_logger(__name__)


async def recover_worker_failure(
    settings: Settings,
    runtime: PipelineRuntime,
    item: ScraperWorkItem,
    error: Exception,
    stage: Literal["filter", "scraper"],
) -> None:
    """Reencola contención del pool o locks hasta tres veces; el cuarto intento falla.

    El filtro conserva espera exponencial para locks y el scraper su espera de dos
    segundos. Los fallos definitivos incrementan ambos contadores de error del run.
    """
    retry_reason, terminal_reason = _database_failure_codes(error)
    if retry_reason and item.attempts < 4:
        delay = (
            2
            if stage == "scraper" and retry_reason == "mysql_lock_retry"
            else min(30, 2**item.attempts)
        )
        await finish_item(settings, item, "requeue", retry_reason, delay_seconds=delay)
        logger.warning(
            f"{stage}_app_requeued",
            winstall_id=item.package_id,
            reason=retry_reason,
            attempts=item.attempts,
        )
        return
    await finish_item(settings, item, "fail", terminal_reason)
    await runtime.increment("apps_failed")
    await runtime.increment("apps_transient_failed")
    error_name = (
        "OperationalError"
        if stage == "scraper" and isinstance(error, OperationalError)
        else error.__class__.__name__
    )
    logger.warning(
        f"{stage}_app_failed",
        winstall_id=item.package_id,
        error=error_name,
        detail=exception_detail(error),
    )


def _database_failure_codes(error: Exception) -> tuple[str | None, str]:
    """Distingue agotamiento del pool, locks reintentables y fallos no clasificados."""
    if isinstance(error, SQLAlchemyTimeoutError):
        return "database_pool_retry", "database_pool_timeout"
    if isinstance(error, OperationalError):
        return "mysql_lock_retry" if is_transient_mysql_lock_error(
            error
        ) else None, "OperationalError"
    return None, error.__class__.__name__
