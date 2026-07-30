"""Contiene las pruebas de `test_schemas`.
"""
from pydantic import ValidationError
import pytest

from app.schemas import SemanticSearchRequest


def test_runtime_request_can_enumerate_the_complete_public_catalog() -> None:
    """Comprueba el escenario `runtime_request_can_enumerate_the_complete_public_catalog`.
    """
    request = SemanticSearchRequest(query="Launchers de github", limit=20_000)

    assert request.limit == 20_000


def test_runtime_request_rejects_candidates_beyond_the_functional_ceiling() -> None:
    """Comprueba el escenario `runtime_request_rejects_candidates_beyond_the_functional_ceiling`.
    """
    with pytest.raises(ValidationError):
        SemanticSearchRequest(query="launchers", limit=20_001)
