"""Contiene las pruebas de `test_config`.
"""
import pytest

from app.core.config import Settings


def test_database_url_is_built_from_components_without_parsing_password() -> None:
    """Comprueba el escenario `database_url_is_built_from_components_without_parsing_password`.
    """
    settings = Settings(
        database_host="mysql",
        database_port=3306,
        database_name="batch_downloader",
        database_username="batch_user",
        database_password="p@ss:$ab_/?#",
    )

    url = settings.database_url

    assert url.drivername == "mysql+aiomysql"
    assert url.host == "mysql"
    assert url.port == 3306
    assert url.database == "batch_downloader"
    assert url.username == "batch_user"
    assert url.password == "p@ss:$ab_/?#"
    assert "%40" in url.render_as_string(hide_password=False)


def test_database_url_override_is_limited_to_explicit_test_configuration() -> None:
    """Comprueba el escenario `database_url_override_is_limited_to_explicit_test_configuration`.
    """
    settings = Settings(database_url_override="sqlite+aiosqlite:///:memory:")

    assert settings.database_url.drivername == "sqlite+aiosqlite"


def test_database_pool_reserves_capacity_for_pipeline_and_catalog() -> None:
    """Los workers no deben agotar las conexiones necesarias para servir el catálogo."""
    settings = Settings(_env_file=None)

    assert settings.database_pool_max == 8
    assert settings.database_max_overflow == 4
    assert settings.database_pool_timeout_seconds == 5
    assert settings.run_on_startup is False


def test_rejects_removed_scrapper_environment_prefix(monkeypatch: pytest.MonkeyPatch) -> None:
    """Una configuración antigua falla con una instrucción de migración explícita."""
    monkeypatch.setenv("SCRAPPER_DATABASE_HOST", "legacy-host")

    with pytest.raises(ValueError, match=r"SCRAPPER_\* a SCRAPER_\*"):
        Settings(_env_file=None)
