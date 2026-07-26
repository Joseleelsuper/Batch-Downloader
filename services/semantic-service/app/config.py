from __future__ import annotations

from functools import lru_cache

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict
from psycopg.conninfo import make_conninfo


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="SEMANTIC_",
        env_file=".env",
        extra="ignore",
    )

    database_host: str = "localhost"
    database_port: int = 5432
    database_name: str = "batch_downloader_semantic"
    database_username: str = "batch_downloader"
    database_password: SecretStr = SecretStr("batch_downloader")
    postgres_dsn_override: str | None = Field(default=None, exclude=True)
    internal_service_token: SecretStr = SecretStr("")
    scraper_api_url: str = "http://scraper-api:8000"
    model_cache_dir: str = "/models"
    reports_dir: str = "/reports"
    device: str = "cpu"
    initial_model_version: str = (
        "multilingual-e5-base@d128750597153bb5987e10b1c3493a34e5a4502a:zero-shot"
    )
    candidate_limit: int = 20000
    minimum_similarity: float = 0.82
    index_batch_size: int = 32
    index_interval_seconds: float = 300.0
    index_lease_seconds: int = 900
    search_timeout_seconds: float = 3.0
    trainer_seed: int = 20260723
    trainer_epochs: float = 1.0
    trainer_batch_size: int = 8
    trainer_max_steps: int = -1
    trainer_models: tuple[str, ...] = (
        "paraphrase-multilingual-MiniLM-L12-v2",
        "multilingual-e5-base",
        "bge-m3",
    )

    @property
    def postgres_dsn(self) -> str:
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
    return Settings()
