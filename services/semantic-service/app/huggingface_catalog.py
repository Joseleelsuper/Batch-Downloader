from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any

from huggingface_hub import HfApi


SAFE_PIPELINES = {"feature-extraction", "sentence-similarity"}
SAFE_LIBRARIES = {"sentence-transformers"}
UNSAFE_WEIGHT_SUFFIXES = (".bin", ".pkl", ".pickle", ".pt", ".pth")


@dataclass(frozen=True)
class HubModelDetail:
    repository: str
    sha: str
    display_name: str
    gated: bool
    private: bool
    library_name: str | None
    pipeline_tag: str | None
    license: str | None
    languages: list[str]
    downloads: int
    likes: int
    last_modified: str | None
    architecture: str | None
    parameter_count: int | None
    max_sequence_length: int | None
    estimated_bytes: int
    files: list[dict[str, Any]]
    compatible: bool
    compatibility_reason: str | None
    security_status: str
    suggested_query_prefix: str | None
    suggested_passage_prefix: str | None
    suggested_minimum_similarity: float | None

    def as_dict(self) -> dict[str, Any]:
        value = asdict(self)
        return {
            "repository": value["repository"],
            "sha": value["sha"],
            "displayName": value["display_name"],
            "gated": value["gated"],
            "private": value["private"],
            "libraryName": value["library_name"],
            "pipelineTag": value["pipeline_tag"],
            "license": value["license"],
            "languages": value["languages"],
            "downloads": value["downloads"],
            "likes": value["likes"],
            "lastModified": value["last_modified"],
            "architecture": value["architecture"],
            "parameterCount": value["parameter_count"],
            "maxSequenceLength": value["max_sequence_length"],
            "estimatedBytes": value["estimated_bytes"],
            "files": value["files"],
            "compatible": value["compatible"],
            "compatibilityReason": value["compatibility_reason"],
            "securityStatus": value["security_status"],
            "suggestedQueryPrefix": value["suggested_query_prefix"],
            "suggestedPassagePrefix": value["suggested_passage_prefix"],
            "suggestedMinimumSimilarity": value[
                "suggested_minimum_similarity"
            ],
        }


class HuggingFaceCatalog:
    def __init__(self, api: HfApi | None = None) -> None:
        self.api = api or HfApi(token=False)

    def search(self, query: str, *, limit: int) -> list[dict[str, Any]]:
        normalized = query.strip()
        if len(normalized) < 2:
            return []
        requested = min(max(limit, 1), 50)
        candidates = self.api.list_models(
            search=normalized,
            sort="downloads",
            direction=-1,
            limit=min(requested * 3, 100),
            token=False,
        )
        results: list[dict[str, Any]] = []
        for model in candidates:
            repository = str(getattr(model, "id", "") or "")
            if not repository or bool(getattr(model, "private", False)):
                continue
            gated_value = getattr(model, "gated", False)
            if gated_value not in (False, None, "false"):
                continue
            pipeline = getattr(model, "pipeline_tag", None)
            library = getattr(model, "library_name", None)
            if library not in SAFE_LIBRARIES or pipeline not in SAFE_PIPELINES:
                continue
            results.append(
                {
                    "repository": repository,
                    "displayName": repository.rsplit("/", 1)[-1],
                    "pipelineTag": pipeline,
                    "libraryName": library,
                    "downloads": int(getattr(model, "downloads", 0) or 0),
                    "likes": int(getattr(model, "likes", 0) or 0),
                    "lastModified": _iso(getattr(model, "last_modified", None)),
                }
            )
            if len(results) >= requested:
                break
        return results

    def detail(self, repository: str, revision: str | None = None) -> HubModelDetail:
        info = self.api.model_info(
            repository,
            revision=revision,
            files_metadata=True,
            securityStatus=True,
            token=False,
        )
        siblings = list(getattr(info, "siblings", None) or [])
        files = [
            {
                "path": str(getattr(sibling, "rfilename", "") or ""),
                "size": int(getattr(sibling, "size", 0) or 0),
            }
            for sibling in siblings
            if getattr(sibling, "rfilename", None)
        ]
        names = {str(row["path"]) for row in files}
        config = getattr(info, "config", None) or {}
        if not isinstance(config, dict):
            config = {}
        card = getattr(info, "card_data", None)
        card_data = card.to_dict() if hasattr(card, "to_dict") else card or {}
        if not isinstance(card_data, dict):
            card_data = {}
        library = getattr(info, "library_name", None)
        pipeline = getattr(info, "pipeline_tag", None)
        gated_value = getattr(info, "gated", False)
        gated = gated_value not in (False, None, "false")
        private = bool(getattr(info, "private", False))
        has_safetensors = any(name.endswith(".safetensors") for name in names)
        unsafe_weights = any(name.endswith(UNSAFE_WEIGHT_SUFFIXES) for name in names)
        remote_code = bool(config.get("auto_map")) or any(name.endswith(".py") for name in names)
        reasons: list[str] = []
        if private:
            reasons.append("private_model")
        if gated:
            reasons.append("gated_model")
        sentence_transformers_layout = (
            library in SAFE_LIBRARIES or "modules.json" in names
        )
        if not sentence_transformers_layout or pipeline not in SAFE_PIPELINES:
            reasons.append("unsupported_pipeline")
        if not has_safetensors:
            reasons.append("safetensors_required")
        if unsafe_weights:
            reasons.append("unsafe_weights_present")
        if remote_code:
            reasons.append("remote_code_required")
        license_value = card_data.get("license")
        if isinstance(license_value, list):
            license_value = ", ".join(str(item) for item in license_value)
        languages = card_data.get("language") or card_data.get("languages") or []
        if isinstance(languages, str):
            languages = [languages]
        architectures = config.get("architectures") or []
        architecture = (
            str(architectures[0])
            if isinstance(architectures, list) and architectures
            else None
        )
        safetensors = getattr(info, "safetensors", None)
        parameters = getattr(safetensors, "parameters", None)
        parameter_count = None
        if isinstance(parameters, dict):
            parameter_count = sum(int(value or 0) for value in parameters.values())
        security = getattr(info, "security_repo_status", None)
        security_status = _security_status(security)
        if security_status == "unsafe":
            reasons.append("unsafe_security_scan")
        suggested_query_prefix = _metadata_string(
            card_data,
            config,
            "query_prefix",
            "queryPrefix",
        )
        suggested_passage_prefix = _metadata_string(
            card_data,
            config,
            "passage_prefix",
            "passagePrefix",
        )
        repository_name = str(getattr(info, "id", repository)).lower()
        if "e5" in repository_name:
            suggested_query_prefix = suggested_query_prefix or "query: "
            suggested_passage_prefix = suggested_passage_prefix or "passage: "
        suggested_minimum_similarity = _metadata_float(
            card_data,
            config,
            "minimum_similarity",
            "minimumSimilarity",
            "similarity_threshold",
        )
        return HubModelDetail(
            repository=str(getattr(info, "id", repository)),
            sha=str(getattr(info, "sha", "") or ""),
            display_name=str(getattr(info, "id", repository)).rsplit("/", 1)[-1],
            gated=gated,
            private=private,
            library_name=library,
            pipeline_tag=pipeline,
            license=str(license_value) if license_value else None,
            languages=[str(value) for value in languages],
            downloads=int(getattr(info, "downloads", 0) or 0),
            likes=int(getattr(info, "likes", 0) or 0),
            last_modified=_iso(getattr(info, "last_modified", None)),
            architecture=architecture,
            parameter_count=parameter_count,
            max_sequence_length=_integer(
                config.get("max_seq_length") or config.get("max_position_embeddings")
            ),
            estimated_bytes=sum(int(row["size"]) for row in files),
            files=files,
            compatible=not reasons and len(str(getattr(info, "sha", ""))) == 40,
            compatibility_reason=reasons[0] if reasons else None,
            security_status=security_status,
            suggested_query_prefix=suggested_query_prefix,
            suggested_passage_prefix=suggested_passage_prefix,
            suggested_minimum_similarity=suggested_minimum_similarity,
        )


def _iso(value: Any) -> str | None:
    return value.isoformat() if hasattr(value, "isoformat") else (str(value) if value else None)


def _integer(value: Any) -> int | None:
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def _metadata_string(
    card_data: dict[str, Any],
    config: dict[str, Any],
    *keys: str,
) -> str | None:
    for source in (card_data, config):
        for key in keys:
            value = source.get(key)
            if isinstance(value, str) and value:
                return value
    return None


def _metadata_float(
    card_data: dict[str, Any],
    config: dict[str, Any],
    *keys: str,
) -> float | None:
    for source in (card_data, config):
        for key in keys:
            try:
                value = source.get(key)
                if value is not None:
                    number = float(value)
                    if -1 <= number <= 1:
                        return number
            except (TypeError, ValueError):
                continue
    return None


def _security_status(value: Any) -> str:
    if value is None:
        return "unknown"
    if isinstance(value, dict):
        scans_done = bool(value.get("scansDone") or value.get("scans_done"))
        issues = value.get("filesWithIssues") or value.get("files_with_issues") or []
    else:
        scans_done = bool(
            getattr(value, "scans_done", False)
            or getattr(value, "scansDone", False)
        )
        issues = (
            getattr(value, "files_with_issues", None)
            or getattr(value, "filesWithIssues", None)
            or []
        )
    if issues:
        return "unsafe"
    return "safe" if scans_done else "unknown"
