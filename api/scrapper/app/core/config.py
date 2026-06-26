from functools import lru_cache
from zoneinfo import ZoneInfo

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="SCRAPPER_", extra="ignore")

    app_name: str = "Batch Downloader Scraper"
    environment: str = "development"
    database_url: str = Field(
        default="mysql+asyncmy://batch_downloader:batch_downloader@localhost:3306/batch_downloader",
        description="Async SQLAlchemy database URL.",
    )
    winstall_base_url: str = "https://winstall.app"
    winstall_api_base_url: str = "https://winstall.app/api/winstall"
    request_timeout_seconds: float = 20.0
    max_redirects: int = 5
    max_download_size_bytes: int = 1_500_000_000
    scrape_page_size: int = 60
    scrape_concurrency: int = 6
    scrape_max_apps: int = 0
    scheduler_timezone: str = "Europe/Madrid"
    scheduler_hour: int = 3
    scheduler_minute: int = 0
    run_lock_stale_minutes: int = 90
    resolved_source_ttl_hours: int = 24
    url_protection_secret: str = "change-me-before-production"
    allowed_download_schemes: tuple[str, ...] = ("https",)
    preferred_operating_system: str = "windows"
    preferred_architecture: str = "x86_64"
    playwright_enabled: bool = True
    playwright_timeout_ms: int = 15_000

    @property
    def scheduler_zoneinfo(self) -> ZoneInfo:
        return ZoneInfo(self.scheduler_timezone)


@lru_cache
def get_settings() -> Settings:
    return Settings()
