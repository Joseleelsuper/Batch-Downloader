"""Contiene las pruebas de `test_config`.
"""
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
