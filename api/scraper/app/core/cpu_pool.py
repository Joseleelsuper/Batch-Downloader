"""Implementa las responsabilidades del módulo `cpu_pool`.
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
"""Estado global asociado a `_executor`.
"""


async def run_cpu_bound[**P, T](
    function: Callable[P, T], *args: P.args, **kwargs: P.kwargs
) -> T:
    """Ejecuta la operación `cpu_bound`.

    Args:
        function (Callable[P, T]): Valor de `function` utilizado por la operación.
        *args (P.args): Valor de `args` utilizado por la operación.
        **kwargs (P.kwargs): Valor de `kwargs` utilizado por la operación.

    Returns:
        T: Resultado producido por la operación.
    """
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(_executor, partial(function, *args, **kwargs))
