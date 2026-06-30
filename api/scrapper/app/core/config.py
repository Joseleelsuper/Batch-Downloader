from functools import lru_cache
from zoneinfo import ZoneInfo

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="SCRAPPER_", extra="ignore")

    app_name: str = Field(description="FastAPI title and health service name.")
    environment: str = Field(description="Runtime environment name.")
    database_url: str = Field(description="Async SQLAlchemy database URL.")
    winstall_base_url: str
    winstall_api_base_url: str
    request_timeout_seconds: float
    max_redirects: int
    max_download_size_bytes: int
    scrape_page_size: int
    scrape_concurrency: int
    scrape_max_apps: int
    scrape_app_timeout_seconds: float
    scheduler_timezone: str
    scheduler_hour: int
    scheduler_minute: int
    run_on_startup: bool
    run_lock_stale_minutes: int
    resolved_source_ttl_hours: int
    url_protection_secret: str
    allowed_download_schemes: tuple[str, ...]
    preferred_operating_system: str
    preferred_architecture: str
    playwright_enabled: bool
    playwright_timeout_ms: int
    llm_groq_api_key: str
    llm_groq_base_url: str
    llm_groq_model: str
    llm_deepseek_api_key: str
    llm_deepseek_base_url: str
    llm_deepseek_model: str
    llm_max_concurrency: int
    llm_max_apps_per_run: int
    llm_enrich_interval_apps: int
    llm_request_timeout_seconds: float

    @property
    def scheduler_zoneinfo(self) -> ZoneInfo:
        return ZoneInfo(self.scheduler_timezone)


@lru_cache
def get_settings() -> Settings:
    return Settings()
