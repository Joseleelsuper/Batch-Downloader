"""Contiene las pruebas de `test_admin_schemas`.
"""
from uuid import UUID

import pytest
from pydantic import ValidationError

from app.admin_schemas import BenchmarkModelsRequest, DownloadModelRequest


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


def test_download_request_rejects_non_hub_repository_names() -> None:
    """Comprueba el escenario `download_request_rejects_non_hub_repository_names`.
    """
    with pytest.raises(ValidationError):
        DownloadModelRequest(repository="../private/model")

    request = DownloadModelRequest(
        repository="sentence-transformers/model",
        queryPrefix="query: ",
        passagePrefix="passage: ",
        minimumSimilarity=0.82,
    )
    assert request.repository == "sentence-transformers/model"
    assert request.minimum_similarity == 0.82
