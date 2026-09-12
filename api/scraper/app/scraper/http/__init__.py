"""Expone el contrato de petición, respuesta y error y el cliente compuesto para consultar
recursos HTTPS públicos.
"""

from app.scraper.http.fetchers import HttpxPublicResourceFetcher
from app.scraper.http.models import FetchRequest, SafeHttpError, SafeHttpResponse

__all__ = [
    "FetchRequest",
    "HttpxPublicResourceFetcher",
    "SafeHttpError",
    "SafeHttpResponse",
]
