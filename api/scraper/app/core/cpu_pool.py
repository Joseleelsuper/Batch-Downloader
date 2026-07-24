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
    """Run a pure CPU function without moving async clients or sessions across threads."""
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(_executor, partial(function, *args, **kwargs))
