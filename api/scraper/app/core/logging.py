"""Unifica logs de aplicación como JSON en stdout con contexto, instante UTC y excepciones
estructuradas.
"""
import logging
import sys

import structlog


def configure_logging() -> None:
    """Configura logging y structlog a nivel INFO, añade contexto y trazas estructuradas y reduce
    httpx/httpcore a WARNING.
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
    """Obtiene el logger de structlog para vincular eventos al módulo indicado.

    Args:
        name: Nombre del módulo o colaborador que identifica el logger.

    Returns:
        logger que utiliza la configuración global de procesadores.
    """
    return structlog.get_logger(name)
