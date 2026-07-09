from functools import lru_cache
from zoneinfo import ZoneInfo

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="SCRAPPER_", extra="ignore")

    app_name: str = Field(
        default="Batch Downloader Scraper",
        description="FastAPI title and health service name.",
    )
    environment: str = Field(default="development", description="Runtime environment name.")
    database_url: str = Field(
        default="mysql+asyncmy://batch_downloader:batch_downloader@localhost:3306/batch_downloader",
        description="Async SQLAlchemy database URL.",
    )
    winstall_base_url: str = "https://winstall.app"
    winstall_api_base_url: str = "https://winstall.app/api/winstall"
    request_timeout_seconds: float = 20
    max_redirects: int = 5
    max_download_size_bytes: int = 1_500_000_000
    scrape_concurrency: int = 6
    scrape_max_apps: int = 0
    scrape_app_timeout_seconds: float = 90
    scrape_searcher_backpressure_limit: int = 250
    scrape_searcher_backpressure_sleep_seconds: float = 2
    scheduler_timezone: str = "Europe/Madrid"
    scheduler_hour: int = 3
    scheduler_minute: int = 0
    run_on_startup: bool = True
    url_protection_secret: str = "replace-with-a-long-random-secret"
    allowed_download_schemes: tuple[str, ...] = ("https",)
    playwright_timeout_ms: int = 15000
    llm_groq_api_key: str = ""
    llm_groq_base_url: str = "https://api.groq.com/openai/v1"
    llm_groq_model: str = "llama-3.1-8b-instant"
    llm_deepseek_api_key: str = ""
    llm_deepseek_base_url: str = "https://api.deepseek.com"
    llm_deepseek_model: str = "deepseek-v4-flash"
    llm_max_concurrency: int = 2
    llm_max_apps_per_run: int = 0
    llm_request_timeout_seconds: float = 45

    @property
    def scheduler_zoneinfo(self) -> ZoneInfo:
        return ZoneInfo(self.scheduler_timezone)


@lru_cache
def get_settings() -> Settings:
    return Settings()
