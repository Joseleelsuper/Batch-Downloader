"""Contiene las pruebas de `test_admin_schemas`.
"""
from uuid import UUID

import pytest
from pydantic import ValidationError

from app.admin_schemas import BenchmarkModelsRequest


def test_benchmark_requires_two_to_four_unique_models() -> None:
    """Comprueba el escenario `benchmark_requires_two_to_four_unique_models`.
    """
    first = UUID("00000000-0000-0000-0000-000000000001")
    second = UUID("00000000-0000-0000-0000-000000000002")

    with pytest.raises(ValidationError):
        BenchmarkModelsRequest(modelIds=[first])
    with pytest.raises(ValidationError):
        BenchmarkModelsRequest(modelIds=[first, first])

    request = BenchmarkModelsRequest(modelIds=[first, second])
    assert request.model_ids == [first, second]
