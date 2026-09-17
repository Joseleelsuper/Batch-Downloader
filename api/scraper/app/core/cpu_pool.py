"""Comparte un pool limitado de hilos para sacar análisis síncronos del bucle asíncrono del
scraper.
"""
from __future__ import annotations

import asyncio
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from functools import partial

from app.core.config import get_settings

_executor = ThreadPoolExecutor(
    max_workers=max(1, get_settings().cpu_thread_workers),
    thread_name_prefix="scraper-cpu",
)



async def run_cpu_bound[**P, T](
    function: Callable[P, T], *args: P.args, **kwargs: P.kwargs
) -> T:
    """Envía la función y sus argumentos al pool de cálculo del proceso y espera su resultado sin
    bloquear el bucle de eventos.

    Args:
        function: Función síncrona que se ejecutará fuera del bucle de eventos.
        args: Argumentos posicionales de la función delegada.
        kwargs: Argumentos con nombre de la función delegada.

    Returns:
        resultado de la función; sus excepciones se propagan al llamador.
    """
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(_executor, partial(function, *args, **kwargs))
