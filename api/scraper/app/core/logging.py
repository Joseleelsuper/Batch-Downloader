"""Implementa las responsabilidades del módulo `logging`.
"""
import logging
import sys

import structlog


def configure_logging() -> None:
    """Ejecuta la operación `configure_logging`.
    """
    logging.basicConfig(format="%(message)s", stream=sys.stdout, level=logging.INFO)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            structlog.processors.dict_tracebacks,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.INFO),
        logger_factory=structlog.stdlib.LoggerFactory(),
        cache_logger_on_first_use=True,
    )


def get_logger(name: str):
    """Obtiene la operación `logger`.

    Args:
        name (str): Nombre del elemento sobre el que se actúa.
    """
    return structlog.get_logger(name)
