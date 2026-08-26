"""Wrappers HTTP seguros utilizados por las operaciones de scraping."""

from app.scraper.http.fetchers import HttpxPublicResourceFetcher
from app.scraper.http.models import FetchRequest, SafeHttpError, SafeHttpResponse

__all__ = [
    "FetchRequest",
    "HttpxPublicResourceFetcher",
    "SafeHttpError",
    "SafeHttpResponse",
]
