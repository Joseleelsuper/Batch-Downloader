"""Contiene las pruebas de `test_huggingface_catalog`.
"""
from __future__ import annotations

from dataclasses import dataclass
from types import SimpleNamespace
from typing import Any

from app.huggingface_catalog import HuggingFaceCatalog


@dataclass
class Card:
    """Agrupa los escenarios de prueba de `Card`.
    """
    values: dict[str, Any]
    """Atributo de clase `values` de `Card`.
    """

    def to_dict(self) -> dict[str, Any]:
        """Ejecuta `to_dict` dentro de `Card`.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.
        """
        return self.values


class FakeHubApi:
    """Agrupa los escenarios de prueba de `FakeHubApi`.
    """
    def __init__(self, models: list[Any] | None = None, detail: Any = None) -> None:
        """Inicializa una instancia de `FakeHubApi`.

        Args:
            models (list[Any] | None): Valor de `models` utilizado por la operación.
            detail (Any): Valor de `detail` utilizado por la operación.
        """
        self.models = models or []
        """Estado de instancia asociado a `models`.
        """
        self.detail_value = detail
        """Estado de instancia asociado a `detail_value`.
        """
        self.calls: list[tuple[str, dict[str, Any]]] = []
        """Estado de instancia asociado a `calls`.
        """

    def list_models(self, **kwargs: Any) -> list[Any]:
        """Enumera la operación `models`.

        Args:
            **kwargs (Any): Valor de `kwargs` utilizado por la operación.

        Returns:
            list[Any]: Colección de elementos obtenidos por la operación.
        """
        self.calls.append(("list", kwargs))
        return self.models

    def model_info(self, _repository: str, **kwargs: Any) -> Any:
        """Ejecuta `model_info` dentro de `FakeHubApi`.

        Args:
            _repository (str): Valor de `_repository` utilizado por la operación.
            **kwargs (Any): Valor de `kwargs` utilizado por la operación.

        Returns:
            Any: Resultado producido por la operación.
        """
        self.calls.append(("detail", kwargs))
        return self.detail_value


def summary(
    repository: str,
    *,
    library: str = "sentence-transformers",
    gated: bool = False,
    private: bool = False,
) -> Any:
    """Ejecuta la operación `summary`.

    Args:
        repository (str): Valor de `repository` utilizado por la operación.
        library (str): Valor de `library` utilizado por la operación.
        gated (bool): Valor de `gated` utilizado por la operación.
        private (bool): Valor de `private` utilizado por la operación.

    Returns:
        Any: Resultado producido por la operación.
    """
    return SimpleNamespace(
        id=repository,
        library_name=library,
        pipeline_tag="sentence-similarity",
        gated=gated,
        private=private,
        downloads=10,
        likes=2,
        last_modified=None,
    )


def detail(*, files: list[str], security: dict[str, Any] | None = None) -> Any:
    """Ejecuta la operación `detail`.

    Args:
        files (list[str]): Valor de `files` utilizado por la operación.
        security (dict[str, Any] | None): Valor de `security` utilizado por la operación.

    Returns:
        Any: Resultado producido por la operación.
    """
    return SimpleNamespace(
        id="owner/model",
        sha="a" * 40,
        library_name="sentence-transformers",
        pipeline_tag="sentence-similarity",
        gated=False,
        private=False,
        downloads=12,
        likes=3,
        last_modified=None,
        config={
            "architectures": ["BertModel"],
            "max_position_embeddings": 512,
        },
        card_data=Card({
            "license": "apache-2.0",
            "language": ["es", "en"],
            "query_prefix": "query: ",
            "passage_prefix": "passage: ",
            "minimum_similarity": 0.4,
        }),
        siblings=[
            SimpleNamespace(rfilename=name, size=100)
            for name in files
        ],
        safetensors=SimpleNamespace(parameters={"F32": 42}),
        security_repo_status=security,
    )


def test_search_only_returns_public_sentence_transformers_without_a_token() -> None:
    """Comprueba el escenario `search_only_returns_public_sentence_transformers_without_a_token`.
    """
    api = FakeHubApi([
        summary("owner/valid"),
        summary("owner/gated", gated=True),
        summary("owner/private", private=True),
        summary("owner/transformers", library="transformers"),
    ])

    results = HuggingFaceCatalog(api).search("embedding", limit=10)

    assert [row["repository"] for row in results] == ["owner/valid"]
    assert api.calls[0][1]["token"] is False


def test_detail_resolves_a_safe_immutable_safetensors_model() -> None:
    """Comprueba el escenario `detail_resolves_a_safe_immutable_safetensors_model`.
    """
    api = FakeHubApi(detail=detail(
        files=["config.json", "modules.json", "model.safetensors"],
        security={"scansDone": True, "filesWithIssues": []},
    ))

    model = HuggingFaceCatalog(api).detail("owner/model")

    assert model.compatible is True
    assert model.sha == "a" * 40
    assert model.security_status == "safe"
    assert model.parameter_count == 42
    assert model.max_sequence_length == 512
    assert model.suggested_query_prefix == "query: "
    assert model.suggested_passage_prefix == "passage: "
    assert model.suggested_minimum_similarity == 0.4
    assert api.calls[0][1]["securityStatus"] is True
    assert api.calls[0][1]["token"] is False


def test_detail_rejects_pickle_code_and_reported_security_issues() -> None:
    """Comprueba el escenario `detail_rejects_pickle_code_and_reported_security_issues`.
    """
    api = FakeHubApi(detail=detail(
        files=[
            "config.json",
            "modules.json",
            "model.safetensors",
            "pytorch_model.bin",
            "custom_model.py",
        ],
        security={
            "scansDone": True,
            "filesWithIssues": [{"path": "pytorch_model.bin"}],
        },
    ))

    model = HuggingFaceCatalog(api).detail("owner/model")

    assert model.compatible is False
    assert model.compatibility_reason == "unsafe_weights_present"
    assert model.security_status == "unsafe"
