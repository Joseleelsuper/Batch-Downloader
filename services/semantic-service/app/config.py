"""Implementa las responsabilidades del módulo `config`.
"""
from __future__ import annotations

from functools import lru_cache

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict
from psycopg.conninfo import make_conninfo


class Settings(BaseSettings):
    """Agrupa las opciones de configuración de `Settings`.
    """
    model_config = SettingsConfigDict(
        env_prefix="SEMANTIC_",
        env_file=".env",
        extra="ignore",
    )
    """Campo declarado `model_config` de `Settings`.
    """

    database_host: str = "localhost"
    """Campo declarado `database_host` de `Settings`.
    """
    database_port: int = 5432
    """Campo declarado `database_port` de `Settings`.
    """
    database_name: str = "batch_downloader_semantic"
    """Campo declarado `database_name` de `Settings`.
    """
    database_username: str = "batch_downloader"
    """Campo declarado `database_username` de `Settings`.
    """
    database_password: SecretStr = SecretStr("batch_downloader")
    """Campo declarado `database_password` de `Settings`.
    """
    postgres_dsn_override: str | None = Field(default=None, exclude=True)
    """Campo declarado `postgres_dsn_override` de `Settings`.
    """
    internal_service_token: SecretStr = SecretStr("")
    """Campo declarado `internal_service_token` de `Settings`.
    """
    scraper_api_url: str = "http://scraper-api:8000"
    """Campo declarado `scraper_api_url` de `Settings`.
    """
    service_url: str = "http://semantic-service:8000"
    """Campo declarado `service_url` de `Settings`.
    """
    model_cache_dir: str = "/models"
    """Campo declarado `model_cache_dir` de `Settings`.
    """
    reports_dir: str = "/reports"
    """Campo declarado `reports_dir` de `Settings`.
    """
    device: str = "cpu"
    """Campo declarado `device` de `Settings`.
    """
    initial_model_version: str = (
        "multilingual-e5-base@d128750597153bb5987e10b1c3493a34e5a4502a:zero-shot"
    )
    """Campo declarado `initial_model_version` de `Settings`.
    """
    candidate_limit: int = 20000
    """Campo declarado `candidate_limit` de `Settings`.
    """
    minimum_similarity: float = 0.82
    """Campo declarado `minimum_similarity` de `Settings`.
    """
    index_batch_size: int = 32
    """Campo declarado `index_batch_size` de `Settings`.
    """
    index_interval_seconds: float = 300.0
    """Campo declarado `index_interval_seconds` de `Settings`.
    """
    index_lease_seconds: int = 900
    """Campo declarado `index_lease_seconds` de `Settings`.
    """
    search_timeout_seconds: float = 3.0
    """Campo declarado `search_timeout_seconds` de `Settings`.
    """
    operation_poll_seconds: float = 2.0
    """Campo declarado `operation_poll_seconds` de `Settings`.
    """
    operation_lease_seconds: int = 300
    """Campo declarado `operation_lease_seconds` de `Settings`.
    """
    model_max_bytes: int = 16_106_127_360
    """Campo declarado `model_max_bytes` de `Settings`.
    """
    model_min_free_bytes: int = 10_737_418_240
    """Campo declarado `model_min_free_bytes` de `Settings`.
    """
    trainer_seed: int = 20260723
    """Campo declarado `trainer_seed` de `Settings`.
    """
    trainer_epochs: float = 1.0
    """Campo declarado `trainer_epochs` de `Settings`.
    """
    trainer_batch_size: int = 8
    """Campo declarado `trainer_batch_size` de `Settings`.
    """
    trainer_max_steps: int = -1
    """Campo declarado `trainer_max_steps` de `Settings`.
    """
    trainer_models: tuple[str, ...] = (
        "paraphrase-multilingual-MiniLM-L12-v2",
        "multilingual-e5-base",
        "bge-m3",
    )
    """Campo declarado `trainer_models` de `Settings`.
    """

    @property
    def postgres_dsn(self) -> str:
        """Ejecuta `postgres_dsn` dentro de `Settings`.

        Returns:
            str: Resultado producido por la operación.
        """
        if self.postgres_dsn_override:
            return self.postgres_dsn_override
        return make_conninfo(
            host=self.database_host,
            port=self.database_port,
            dbname=self.database_name,
            user=self.database_username,
            password=self.database_password.get_secret_value(),
        )


@lru_cache
def get_settings() -> Settings:
    """Obtiene la operación `settings`.

    Returns:
        Settings: Resultado de `get_settings`.
    """
    return Settings()
