from __future__ import annotations

import importlib
import sys
import sysconfig

RUNTIME_EXTENSION_IMPORTS = (
    "cryptography.hazmat.bindings._rust",
    "greenlet",
    "pydantic_core._pydantic_core",
    "playwright.async_api",
    "selectolax.parser",
    "sqlalchemy",
)


def assert_free_threaded_runtime() -> None:
    """Fail fast if the build or an imported extension enabled the GIL."""
    if sysconfig.get_config_var("Py_GIL_DISABLED") != 1:
        raise RuntimeError("scraper_requires_cpython_free_threaded_build")
    is_gil_enabled = getattr(sys, "_is_gil_enabled", None)
    if is_gil_enabled is None or is_gil_enabled():
        raise RuntimeError("scraper_gil_is_enabled")
    for module_name in RUNTIME_EXTENSION_IMPORTS:
        importlib.import_module(module_name)
    if is_gil_enabled():
        raise RuntimeError("scraper_extension_reenabled_gil")
