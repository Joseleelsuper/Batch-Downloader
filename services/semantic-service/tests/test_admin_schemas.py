"""Verifica identidad y cardinalidad mínima de candidatos antes de encolar un benchmark."""
from uuid import UUID

import pytest
from pydantic import ValidationError

from app.admin_schemas import BenchmarkModelsRequest


def test_benchmark_requires_two_to_four_unique_models() -> None:
    """Un solo modelo o UUID repetidos se rechazan; dos candidatos distintos conservan su orden."""
    first = UUID("00000000-0000-0000-0000-000000000001")
    second = UUID("00000000-0000-0000-0000-000000000002")

    with pytest.raises(ValidationError):
        BenchmarkModelsRequest(modelIds=[first])
    with pytest.raises(ValidationError):
        BenchmarkModelsRequest(modelIds=[first, first])

    request = BenchmarkModelsRequest(modelIds=[first, second])
    assert request.model_ids == [first, second]
