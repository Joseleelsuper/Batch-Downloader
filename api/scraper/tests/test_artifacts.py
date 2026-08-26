"""Contiene las pruebas de `test_artifacts`.
"""
from app.scraper.artifacts import (
    DEFAULT_ARTIFACT_FORMAT_REGISTRY,
    ArtifactArchitecture,
    ArtifactFormat,
    ArtifactFormatRegistry,
    ArtifactPlatform,
)


def test_default_registry_centralizes_extension_platform_and_signature_rules() -> None:
    """Comprueba el escenario `default_registry_centralizes_extension_platform_and_signature_rules`.
    """
    registry = DEFAULT_ARTIFACT_FORMAT_REGISTRY

    assert registry.detect_extension(
        "https://downloads.example.test/get?file=product-1.0.tar.gz"
    ) == ".tar.gz"
    assert registry.platform_for(".msixbundle") == ArtifactPlatform.WINDOWS
    assert registry.matches_signature(".deb", b"!<arch>\npackage")
    assert registry.infer_extension(b"MZbinary") == ".exe"


def test_registry_can_be_extended_without_changing_candidate_or_validator_code() -> None:
    """Comprueba que el registro admite extensiones sin alterar candidatos ni validadores."""
    registry = ArtifactFormatRegistry(
        (
            ArtifactFormat(
                extension=".custom",
                platforms=(ArtifactPlatform.LINUX,),
                media_types=("application/vnd.example.custom",),
                signatures=(b"CUSTOM",),
                infer_from_signature=True,
            ),
        )
    )

    assert registry.detect_extension("https://example.test/app.custom") == ".custom"
    assert registry.infer_extension(b"CUSTOM payload") == ".custom"
    assert registry.binary_media_types == {"application/vnd.example.custom"}


def test_registry_exposes_unknown_architecture_instead_of_forcing_a_cpu_family() -> None:
    """Comprueba que el registro conserva una arquitectura desconocida sin forzar una familia."""
    registry = DEFAULT_ARTIFACT_FORMAT_REGISTRY

    assert registry.infer_architecture("product-universal.pkg") == ArtifactArchitecture.UNKNOWN
    assert registry.infer_architecture("product-arm64.pkg") == ArtifactArchitecture.AARCH64
