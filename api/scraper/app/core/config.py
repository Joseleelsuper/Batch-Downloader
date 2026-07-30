"""Implementa las responsabilidades del módulo `config`.
"""
from enum import StrEnum
from functools import lru_cache
from zoneinfo import ZoneInfo

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy import URL, make_url


class GroqDescriptionModel(StrEnum):
    """Enumera los valores admitidos por `GroqDescriptionModel`.
    """

    LLAMA_3_3_70B = "llama-3.3-70b-versatile"
    """Constante que define `LLAMA_3_3_70B`.
    """
    GPT_OSS_120B = "openai/gpt-oss-120b"
    """Constante que define `GPT_OSS_120B`.
    """
    QWEN_3_32B = "qwen/qwen3-32b"
    """Constante que define `QWEN_3_32B`.
    """
    QWEN_3_6_27B = "qwen/qwen3.6-27b"
    """Constante que define `QWEN_3_6_27B`.
    """
    LLAMA_4_SCOUT = "meta-llama/llama-4-scout-17b-16e-instruct"
    """Constante que define `LLAMA_4_SCOUT`.
    """
    LLAMA_3_1_8B = "llama-3.1-8b-instant"
    """Constante que define `LLAMA_3_1_8B`.
    """


DEFAULT_GROQ_DESCRIPTION_FALLBACKS = (
    GroqDescriptionModel.LLAMA_3_3_70B,
    GroqDescriptionModel.QWEN_3_32B,
    GroqDescriptionModel.QWEN_3_6_27B,
    GroqDescriptionModel.LLAMA_4_SCOUT,
)
"""Constante que define `DEFAULT_GROQ_DESCRIPTION_FALLBACKS`.
"""


class Settings(BaseSettings):
    """Agrupa las opciones de configuración de `Settings`.
    """

    model_config = SettingsConfigDict(env_file=".env", env_prefix="SCRAPPER_", extra="ignore")
    """Campo declarado `model_config` de `Settings`.
    """

    app_name: str = Field(
        default="Batch Downloader Scraper",
        description="FastAPI title and health service name.",
    )
    """Campo declarado `app_name` de `Settings`.
    """
    environment: str = Field(default="development", description="Runtime environment name.")
    """Campo declarado `environment` de `Settings`.
    """
    database_host: str = "localhost"
    """Campo declarado `database_host` de `Settings`.
    """
    database_port: int = 3306
    """Campo declarado `database_port` de `Settings`.
    """
    database_name: str = "batch_downloader"
    """Campo declarado `database_name` de `Settings`.
    """
    database_username: str = "batch_downloader"
    """Campo declarado `database_username` de `Settings`.
    """
    database_password: SecretStr = SecretStr("batch_downloader")
    """Campo declarado `database_password` de `Settings`.
    """
    database_url_override: str | None = Field(
        default=None,
        description="Test-only full URL override; runtime configuration uses database components.",
        exclude=True,
    )
    """Campo declarado `database_url_override` de `Settings`.
    """
    winstall_base_url: str = "https://winstall.app"
    """Campo declarado `winstall_base_url` de `Settings`.
    """
    winstall_api_base_url: str = "https://winstall.app/api/winstall"
    """Campo declarado `winstall_api_base_url` de `Settings`.
    """
    request_timeout_seconds: float = 20
    """Campo declarado `request_timeout_seconds` de `Settings`.
    """
    max_redirects: int = 5
    """Campo declarado `max_redirects` de `Settings`.
    """
    max_download_size_bytes: int = 1_500_000_000
    """Campo declarado `max_download_size_bytes` de `Settings`.
    """
    icon_max_bytes: int = 5_000_000
    """Campo declarado `icon_max_bytes` de `Settings`.
    """
    manual_inspection_ttl_hours: int = 24
    """Campo declarado `manual_inspection_ttl_hours` de `Settings`.
    """
    manual_inspection_max_attempts: int = 4
    """Campo declarado `manual_inspection_max_attempts` de `Settings`.
    """
    manual_page_max_bytes: int = 1_000_000
    """Campo declarado `manual_page_max_bytes` de `Settings`.
    """
    so_filter_concurrency: int = 2
    """Campo declarado `so_filter_concurrency` de `Settings`.
    """
    so_filter_max_attempts: int = 4
    """Campo declarado `so_filter_max_attempts` de `Settings`.
    """
    scrape_concurrency: int = 6
    """Campo declarado `scrape_concurrency` de `Settings`.
    """
    scrape_max_apps: int = 0
    """Campo declarado `scrape_max_apps` de `Settings`.
    """
    scrape_app_timeout_seconds: float = 90
    """Campo declarado `scrape_app_timeout_seconds` de `Settings`.
    """
    scrape_searcher_backpressure_limit: int = 250
    """Campo declarado `scrape_searcher_backpressure_limit` de `Settings`.
    """
    scrape_searcher_backpressure_sleep_seconds: float = 2
    """Campo declarado `scrape_searcher_backpressure_sleep_seconds` de `Settings`.
    """
    cpu_thread_workers: int = 4
    """Campo declarado `cpu_thread_workers` de `Settings`.
    """
    scheduler_timezone: str = "Europe/Madrid"
    """Campo declarado `scheduler_timezone` de `Settings`.
    """
    scheduler_hour: int = 3
    """Campo declarado `scheduler_hour` de `Settings`.
    """
    scheduler_minute: int = 0
    """Campo declarado `scheduler_minute` de `Settings`.
    """
    run_on_startup: bool = True
    """Campo declarado `run_on_startup` de `Settings`.
    """
    url_protection_secret: str = "replace-with-a-long-random-secret"
    """Campo declarado `url_protection_secret` de `Settings`.
    """
    allowed_download_schemes: tuple[str, ...] = ("https",)
    """Campo declarado `allowed_download_schemes` de `Settings`.
    """
    playwright_timeout_ms: int = 15000
    """Campo declarado `playwright_timeout_ms` de `Settings`.
    """
    internal_service_token: SecretStr = SecretStr("")
    """Campo declarado `internal_service_token` de `Settings`.
    """
    llm_groq_api_key: str = ""
    """Campo declarado `llm_groq_api_key` de `Settings`.
    """
    llm_groq_base_url: str = "https://api.groq.com/openai/v1"
    """Campo declarado `llm_groq_base_url` de `Settings`.
    """
    llm_groq_model: str = "llama-3.1-8b-instant"
    """Campo declarado `llm_groq_model` de `Settings`.
    """
    llm_groq_fallback_models: tuple[GroqDescriptionModel, ...] = (
        DEFAULT_GROQ_DESCRIPTION_FALLBACKS
    )
    """Campo declarado `llm_groq_fallback_models` de `Settings`.
    """
    llm_deepseek_api_key: str = ""
    """Campo declarado `llm_deepseek_api_key` de `Settings`.
    """
    llm_deepseek_base_url: str = "https://api.deepseek.com"
    """Campo declarado `llm_deepseek_base_url` de `Settings`.
    """
    llm_deepseek_model: str = "deepseek-v4-flash"
    """Campo declarado `llm_deepseek_model` de `Settings`.
    """
    llm_max_concurrency: int = 2
    """Campo declarado `llm_max_concurrency` de `Settings`.
    """
    llm_max_apps_per_run: int = 0
    """Campo declarado `llm_max_apps_per_run` de `Settings`.
    """
    llm_request_timeout_seconds: float = 45
    """Campo declarado `llm_request_timeout_seconds` de `Settings`.
    """
    llm_rate_limit_cooldown_seconds: float = 3600
    """Campo declarado `llm_rate_limit_cooldown_seconds` de `Settings`.
    """
    llm_transient_cooldown_seconds: float = 30
    """Campo declarado `llm_transient_cooldown_seconds` de `Settings`.
    """
    llm_model_error_cooldown_seconds: float = 86400
    """Campo declarado `llm_model_error_cooldown_seconds` de `Settings`.
    """

    @property
    def scheduler_zoneinfo(self) -> ZoneInfo:
        """Ejecuta `scheduler_zoneinfo` dentro de `Settings`.

        Returns:
            ZoneInfo: Resultado producido por la operación.
        """
        return ZoneInfo(self.scheduler_timezone)

    @property
    def database_url(self) -> URL:
        """Ejecuta `database_url` dentro de `Settings`.

        Returns:
            URL: Resultado producido por la operación.
        """
        if self.database_url_override:
            return make_url(self.database_url_override)
        return URL.create(
            drivername="mysql+aiomysql",
            username=self.database_username,
            password=self.database_password.get_secret_value(),
            host=self.database_host,
            port=self.database_port,
            database=self.database_name,
        )


@lru_cache
def get_settings() -> Settings:
    """Obtiene la operación `settings`.

    Returns:
        Settings: Resultado de `get_settings`.
    """
    return Settings()
