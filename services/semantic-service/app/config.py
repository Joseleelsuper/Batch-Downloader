"""Configuracion compartida del API semantico y del indexador."""
from __future__ import annotations

from datetime import UTC, datetime
from functools import lru_cache
from pathlib import Path
from zoneinfo import ZoneInfo

from psycopg.conninfo import make_conninfo
from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Valida el entorno minimo que necesitan la API y el indexador."""

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
    database_role: str = Field(default="api", pattern="^(api|indexer)$")
    api_db_pool_min: int = Field(default=1, ge=0)
    api_db_pool_max: int = Field(default=3, ge=1)
    indexer_db_pool_min: int = Field(default=0, ge=0)
    indexer_db_pool_max: int = Field(default=1, ge=1)
    db_pool_timeout_seconds: float = Field(default=2.0, gt=0)
    db_pool_max_lifetime_seconds: float = Field(default=1500.0, ge=60)
    postgres_dsn_override: str | None = Field(default=None, exclude=True)

    internal_service_token: SecretStr = SecretStr("")
    scraper_api_url: str = "http://scraper-api:8000"

    model_dir: str = "/models/current"
    model_runtime_cache_dir: str = "/models/runtime-cache"
    model_manifest_name: str = "batch-model.json"
    device: str = "cpu"

    candidate_limit: int = Field(default=20000, ge=1)
    index_batch_size: int = Field(default=32, ge=1)
    index_interval_seconds: float = Field(default=300.0, gt=0)
    index_lease_seconds: int = Field(default=900, ge=1)
    search_timeout_seconds: float = Field(default=3.0, gt=0)
    search_concurrency: int = Field(default=2, ge=1)
    search_capacity_wait_seconds: float = Field(default=2.0, gt=0)

    background_timezone: str = "Europe/Madrid"
    background_start_hour: int = Field(default=1, ge=0, le=23)
    background_end_hour: int = Field(default=7, ge=0, le=23)

    worker_heartbeat_interval_seconds: float = Field(default=10.0, ge=1.0)
    worker_heartbeat_stale_seconds: float = Field(default=45.0, ge=5.0)
    worker_failure_threshold: int = Field(default=3, ge=1)

    @property
    def postgres_dsn(self) -> str:
        """Devuelve el DSN de pruebas o uno compuesto sin exponerlo en logs."""
        if self.postgres_dsn_override:
            return self.postgres_dsn_override
        return make_conninfo(
            host=self.database_host,
            port=self.database_port,
            dbname=self.database_name,
            user=self.database_username,
            password=self.database_password.get_secret_value(),
        )

    @property
    def database_pool_limits(self) -> tuple[int, int]:
        """Devuelve los limites del pool para el proceso actual."""
        if self.database_role == "indexer":
            return self.indexer_db_pool_min, self.indexer_db_pool_max
        return self.api_db_pool_min, self.api_db_pool_max

    @property
    def model_manifest_path(self) -> str:
        """Devuelve la ruta absoluta del manifiesto de la ranura actual."""
        return str(Path(self.model_dir) / self.model_manifest_name)

    def background_window_open(self, moment: datetime | None = None) -> bool:
        """Indica si una iteracion pesada puede ejecutarse en la ventana configurada."""
        current = (moment or datetime.now(UTC)).astimezone(
            ZoneInfo(self.background_timezone)
        )
        start, end = self.background_start_hour, self.background_end_hour
        if start == end:
            return True
        if start < end:
            return start <= current.hour < end
        return current.hour >= start or current.hour < end


@lru_cache
def get_settings() -> Settings:
    """Carga una sola instancia de configuracion por proceso."""
    return Settings()
