"""Protege el límite funcional de candidatos que necesita Core para filtrar el catálogo completo."""
import pytest
from pydantic import ValidationError

from app.schemas import SemanticSearchRequest


def test_runtime_request_can_enumerate_the_complete_public_catalog() -> None:
    """Una consulta puede solicitar exactamente veinte mil candidatos."""
    request = SemanticSearchRequest(query="Launchers de github", limit=20_000)

    assert request.limit == 20_000


def test_runtime_request_rejects_candidates_beyond_the_functional_ceiling() -> None:
    """Una consulta que supera veinte mil candidatos se rechaza durante validación."""
    with pytest.raises(ValidationError):
        SemanticSearchRequest(query="launchers", limit=20_001)
