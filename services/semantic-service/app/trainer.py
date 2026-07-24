from __future__ import annotations

import argparse
import csv
import gc
import hashlib
import heapq
import json
import os
import random
import shutil
import statistics
import time
import uuid
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np
import psutil

from app.config import get_settings
from app.database import Database
from app.embeddings import EmbeddingRuntime
from app.evaluation import (
    average_precision,
    lexical_rank,
    mean,
    ndcg,
    normalized_tokens,
    recall,
    reciprocal_rank,
    reciprocal_rank_fusion,
)
from app.model_registry import MODELS_BY_KEY, ModelDefinition
from app.store import SemanticStore

DESCRIPTION_STOPWORDS = {
    "aplicacion",
    "aplicaciones",
    "como",
    "con",
    "desde",
    "esta",
    "este",
    "para",
    "permite",
    "software",
    "the",
    "una",
    "utiliza",
}
EVALUATION_CANDIDATE_LIMIT = 2000


@dataclass
class PreparedRuntimeEvaluation:
    queries: list[dict[str, Any]]
    semantic_rankings: list[list[str]]
    lexical_rankings: list[list[str]]
    semantic_latencies_ms: list[float]
    lexical_latencies_ms: list[float]
    embedding_build_ms: float
    document_vector_bytes: int
    index_metrics: dict[str, float | int]
    latency_sample_size: int


@dataclass
class NegativeMiningIndex:
    documents_by_id: dict[str, dict[str, Any]]
    tokens_by_id: dict[str, set[str]]
    aliases_by_id: dict[str, set[str]]
    postings: dict[tuple[str, str], set[str]]
    fallback_by_split: dict[str, list[str]]


def split_for_app(app_id: str, seed: int) -> str:
    bucket = int(hashlib.sha256(f"{seed}:{app_id}".encode()).hexdigest()[:8], 16) % 100
    if bucket < 80:
        return "train"
    if bucket < 90:
        return "validation"
    return "test"


def build_query_snapshot(documents: list[dict[str, Any]], seed: int) -> list[dict[str, Any]]:
    split_by_app = {
        document["app_id"]: split_for_app(document["app_id"], seed)
        for document in documents
    }
    by_tag: dict[tuple[str, str], set[str]] = defaultdict(set)
    by_platform_tag: dict[tuple[str, str, str], set[str]] = defaultdict(set)
    mining_index = build_negative_mining_index(documents, seed)
    rows: list[dict[str, Any]] = []
    for document in documents:
        metadata = document.get("metadata") or {}
        split = split_by_app[document["app_id"]]
        systems = {
            str(value).strip().lower()
            for value in metadata.get("operatingSystems") or []
            if str(value).strip()
        }
        for tag in metadata.get("tags") or []:
            normalized_tag = str(tag).strip().lower()
            by_tag[(split, normalized_tag)].add(document["app_id"])
            for system in systems:
                by_platform_tag[(split, system, normalized_tag)].add(
                    document["app_id"]
                )
    for document in documents:
        metadata = document.get("metadata") or {}
        app_id = document["app_id"]
        split = split_by_app[app_id]
        name = str(metadata.get("name") or "").strip()
        package_id = str(metadata.get("packageId") or "").strip()
        publisher = str(metadata.get("publisher") or "").strip()
        systems = [str(value) for value in metadata.get("operatingSystems") or []]
        tags = [str(value) for value in metadata.get("tags") or []]
        description = " ".join(
            str(metadata.get(field) or "")
            for field in ("shortDescription", "longDescription")
        )
        description_terms = [
            token
            for token in normalized_tokens(description)
            if len(token) >= 4 and token not in DESCRIPTION_STOPWORDS
        ]
        candidates: list[tuple[str, set[str], str]] = []
        if name:
            candidates.append((name, {app_id}, "navigation-name"))
        if package_id:
            candidates.append((package_id, {app_id}, "navigation-package"))
        if publisher and name:
            candidates.append((f"{publisher} {name}", {app_id}, "publisher"))
        if tags:
            intent = " ".join(tags[:3])
            positives = set().union(
                *(by_tag[(split, tag.strip().lower())] for tag in tags[:2])
            )
            candidates.append((f"aplicación de {intent}", positives or {app_id}, "intent"))
        if description_terms:
            candidates.append(
                (
                    " ".join(dict.fromkeys(description_terms))[:160],
                    {app_id},
                    "description-intent",
                )
            )
        if systems and tags:
            positives = by_platform_tag[
                (split, systems[0].strip().lower(), tags[0].strip().lower())
            ]
            candidates.append(
                (
                    f"{tags[0]} para {systems[0]}",
                    positives or {app_id},
                    "platform-intent",
                )
            )
        for query, positives, kind in candidates:
            rows.append(
                {
                    "query": query,
                    "positiveAppId": app_id,
                    "relevantAppIds": sorted(positives),
                    "positive": document["content"],
                    "positiveAliases": sorted(
                        {
                            alias
                            for value in (name, package_id)
                            if (alias := " ".join(normalized_tokens(value)))
                        }
                    ),
                    "split": split,
                    "kind": kind,
                }
            )
    documents_by_id = {document["app_id"]: document for document in documents}
    for row in rows:
        negatives = mine_hard_negatives(
            row,
            documents,
            seed=seed,
            mining_index=mining_index,
        )
        row["hardNegativeAppIds"] = [item["app_id"] for item in negatives]
        row["hardNegatives"] = [item["content"] for item in negatives]
        positive = documents_by_id[row["positiveAppId"]]
        row["positiveContentHash"] = positive.get("content_hash")
    return rows


def build_negative_mining_index(
    documents: list[dict[str, Any]],
    seed: int,
) -> NegativeMiningIndex:
    documents_by_id = {document["app_id"]: document for document in documents}
    tokens_by_id: dict[str, set[str]] = {}
    aliases_by_id: dict[str, set[str]] = {}
    postings: dict[tuple[str, str], set[str]] = defaultdict(set)
    fallback_by_split: dict[str, list[str]] = defaultdict(list)
    for document in documents:
        app_id = document["app_id"]
        split = split_for_app(app_id, seed)
        metadata = document.get("metadata") or {}
        tokens = set(
            normalized_tokens(
                " ".join(
                    [
                        str(document.get("content") or ""),
                        str(metadata.get("publisher") or ""),
                        " ".join(str(tag) for tag in metadata.get("tags") or []),
                    ]
                )
            )
        )
        aliases = {
            alias
            for field in ("name", "packageId")
            if (
                alias := " ".join(
                    normalized_tokens(str(metadata.get(field) or ""))
                )
            )
        }
        tokens_by_id[app_id] = tokens
        aliases_by_id[app_id] = aliases
        fallback_by_split[split].append(app_id)
        for token in tokens:
            postings[(split, token)].add(app_id)
    for split, app_ids in fallback_by_split.items():
        app_ids.sort(
            key=lambda app_id: hashlib.sha256(
                f"{seed}:{split}:{app_id}".encode()
            ).hexdigest()
        )
    return NegativeMiningIndex(
        documents_by_id=documents_by_id,
        tokens_by_id=tokens_by_id,
        aliases_by_id=aliases_by_id,
        postings=postings,
        fallback_by_split=dict(fallback_by_split),
    )


def mine_hard_negatives(
    query_row: dict[str, Any],
    documents: list[dict[str, Any]],
    *,
    seed: int,
    limit: int = 3,
    mining_index: NegativeMiningIndex | None = None,
) -> list[dict[str, Any]]:
    """Mine deterministic lexical hard negatives without crossing data splits.

    Every declared positive is excluded. Exact name/package aliases are also
    excluded conservatively, preventing duplicate catalog entries from being
    mislabeled as negatives.
    """
    query = str(query_row["query"])
    query_tokens = set(normalized_tokens(query))
    relevant = set(query_row["relevantAppIds"])
    positive_aliases = set(query_row.get("positiveAliases") or [])
    split = query_row["split"]
    normalized_query = " ".join(normalized_tokens(query))
    index = mining_index or build_negative_mining_index(documents, seed)
    overlap_by_id: dict[str, int] = defaultdict(int)
    for token in query_tokens:
        for app_id in index.postings.get((split, token), set()):
            overlap_by_id[app_id] += 1
    candidate_ids = set(overlap_by_id)
    fallback_target = max(32, limit * 8)
    if len(candidate_ids) < fallback_target:
        for app_id in index.fallback_by_split.get(split, []):
            candidate_ids.add(app_id)
            if len(candidate_ids) >= fallback_target:
                break
    candidates: list[tuple[float, str, dict[str, Any]]] = []
    for app_id in candidate_ids:
        if app_id in relevant:
            continue
        aliases = index.aliases_by_id[app_id]
        if (
            normalized_query
            and normalized_query in aliases
            or aliases & positive_aliases
        ):
            continue
        candidate_tokens = index.tokens_by_id[app_id]
        overlap = overlap_by_id.get(app_id, 0)
        union = len(query_tokens | candidate_tokens)
        lexical_score = overlap * 100.0 + (overlap / union if union else 0.0)
        deterministic_tie = hashlib.sha256(
            f"{seed}:{query}:{app_id}".encode()
        ).hexdigest()
        candidates.append(
            (
                lexical_score,
                deterministic_tie,
                index.documents_by_id[app_id],
            )
        )
    hardest = heapq.nsmallest(
        limit,
        candidates,
        key=lambda item: (-item[0], item[1]),
    )
    return [document for _, _, document in hardest]


def write_snapshot(
    documents: list[dict[str, Any]],
    queries: list[dict[str, Any]],
    *,
    root: Path,
    seed: int,
) -> tuple[str, Path]:
    digest = hashlib.sha256()
    digest.update(f"seed:{seed}\n".encode())
    for record_type, records in (("document", documents), ("query", queries)):
        for record in records:
            digest.update(record_type.encode())
            digest.update(b":")
            digest.update(
                json.dumps(
                    record,
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                    default=str,
                ).encode()
            )
            digest.update(b"\n")
    dataset_hash = digest.hexdigest()
    snapshot_dir = root / "datasets" / dataset_hash
    snapshot_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = snapshot_dir / "manifest.json"
    if manifest_path.exists():
        return dataset_hash, snapshot_dir
    with (snapshot_dir / "documents.jsonl").open(
        "w",
        encoding="utf-8",
    ) as output:
        for document in documents:
            output.write(
                json.dumps(document, ensure_ascii=False, default=str) + "\n"
            )
    for split in ("train", "validation", "test"):
        with (snapshot_dir / f"{split}.jsonl").open("w", encoding="utf-8") as output:
            for row in queries:
                if row["split"] == split:
                    output.write(json.dumps(row, ensure_ascii=False) + "\n")
    manifest_path.write_text(
        json.dumps(
            {
                "datasetHash": dataset_hash,
                "seed": seed,
                "applications": len(documents),
                "queries": len(queries),
                "createdAt": datetime.now(timezone.utc).isoformat(),
            },
            indent=2,
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    return dataset_hash, snapshot_dir


def discover_lora_targets(auto_model: Any) -> list[str]:
    endings = {"query", "value", "q_proj", "v_proj"}
    targets = sorted(
        {
            name.rsplit(".", 1)[-1]
            for name, module in auto_model.named_modules()
            if name.rsplit(".", 1)[-1] in endings
            and module.__class__.__name__.lower() == "linear"
        }
    )
    if not targets:
        raise RuntimeError("lora_target_modules_not_found")
    return targets


def train_model(
    *,
    base: ModelDefinition,
    train_rows: list[dict[str, Any]],
    validation_rows: list[dict[str, Any]],
    output_dir: Path,
    settings,
    max_steps: int,
) -> None:
    from datasets import Dataset
    from peft import LoraConfig, PeftModel, TaskType, get_peft_model
    from sentence_transformers import (
        SentenceTransformer,
        SentenceTransformerTrainer,
        SentenceTransformerTrainingArguments,
    )
    from sentence_transformers.sentence_transformer.losses import (
        MultipleNegativesRankingLoss,
    )

    random.seed(settings.trainer_seed)
    np.random.seed(settings.trainer_seed)
    model = SentenceTransformer(
        base.repository,
        revision=base.revision,
        device=settings.device,
        cache_folder=settings.model_cache_dir,
        trust_remote_code=False,
    )
    transformer_module = model[0]
    auto_model = transformer_module.auto_model
    target_modules = discover_lora_targets(auto_model)
    transformer_module.model = get_peft_model(
        auto_model,
        LoraConfig(
            task_type=TaskType.FEATURE_EXTRACTION,
            r=16,
            lora_alpha=32,
            lora_dropout=0.05,
            target_modules=target_modules,
            bias="none",
        ),
    )
    triplets = [
        {
            "anchor": base.query_prefix + row["query"],
            "positive": base.passage_prefix + row["positive"],
            "negative": base.passage_prefix + negative,
        }
        for row in train_rows
        for negative in row.get("hardNegatives") or []
    ]
    training_examples = triplets or [
        {
            "anchor": base.query_prefix + row["query"],
            "positive": base.passage_prefix + row["positive"],
        }
        for row in train_rows
    ]
    train_dataset = Dataset.from_list(training_examples)
    eval_dataset = Dataset.from_list(
        [
            {
                "anchor": base.query_prefix + row["query"],
                "positive": base.passage_prefix + row["positive"],
            }
            for row in validation_rows
        ]
    )
    arguments = SentenceTransformerTrainingArguments(
        output_dir=str(output_dir / "checkpoints"),
        num_train_epochs=settings.trainer_epochs,
        max_steps=max_steps,
        per_device_train_batch_size=settings.trainer_batch_size,
        per_device_eval_batch_size=settings.trainer_batch_size,
        learning_rate=2e-4,
        warmup_steps=0.1,
        fp16=settings.device.startswith("cuda"),
        bf16=False,
        dataloader_pin_memory=settings.device.startswith("cuda"),
        batch_sampler="no_duplicates",
        eval_strategy="epoch",
        save_strategy="no",
        logging_steps=10,
        seed=settings.trainer_seed,
        data_seed=settings.trainer_seed,
        load_best_model_at_end=False,
        report_to="none",
    )
    trainer = SentenceTransformerTrainer(
        model=model,
        args=arguments,
        train_dataset=train_dataset,
        eval_dataset=eval_dataset,
        loss=MultipleNegativesRankingLoss(
            model,
            hardness_mode="hard_negatives" if triplets else "in_batch_negatives",
            hardness_strength=0.5 if triplets else 0.0,
        ),
    )
    trainer.train()
    peft_model = transformer_module.model
    if not isinstance(peft_model, PeftModel):
        raise RuntimeError("trained_model_is_not_peft")
    peft_model.save_pretrained(str(output_dir / "adapter"))
    transformer_module.model = peft_model.merge_and_unload()
    model.save_pretrained(str(output_dir))
    (output_dir / "training-metadata.json").write_text(
        json.dumps(
            {
                "seed": settings.trainer_seed,
                "baseRevision": base.revision,
                "targetModules": target_modules,
                "loss": "MultipleNegativesRankingLoss",
                "trainRows": len(train_rows),
                "trainingExamples": len(training_examples),
                "hardNegativeExamples": len(triplets),
                "validationRows": len(validation_rows),
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    reloaded = SentenceTransformer(
        str(output_dir),
        device=settings.device,
        cache_folder=settings.model_cache_dir,
        trust_remote_code=False,
    )
    actual_dimensions = reloaded.get_embedding_dimension()
    if actual_dimensions != base.dimensions:
        raise RuntimeError(
            f"trained_embedding_dimension_mismatch:{actual_dimensions}:{base.dimensions}"
        )
    reloaded.encode(
        [base.query_prefix + "validación de artefacto"],
        convert_to_numpy=True,
        normalize_embeddings=True,
        show_progress_bar=False,
    )
    (output_dir / "training-complete.json").write_text(
        json.dumps(
            {
                "modelKey": base.key,
                "baseRevision": base.revision,
                "dimensions": actual_dimensions,
            },
            sort_keys=True,
        ),
        encoding="utf-8",
    )


def evaluate_runtime(
    runtime: EmbeddingRuntime,
    documents: list[dict[str, Any]],
    queries: list[dict[str, Any]],
    *,
    variant: str,
    semantic_weight: float | None,
    benchmark_store: SemanticStore | None = None,
) -> dict[str, Any]:
    prepared = prepare_runtime_evaluation(
        runtime,
        documents,
        queries,
        benchmark_store=benchmark_store,
    )
    return evaluate_prepared_runtime(
        prepared,
        variant=variant,
        semantic_weight=semantic_weight,
    )


def prepare_runtime_evaluation(
    runtime: EmbeddingRuntime,
    documents: list[dict[str, Any]],
    queries: list[dict[str, Any]],
    *,
    benchmark_store: SemanticStore | None = None,
) -> PreparedRuntimeEvaluation:
    """Build one immutable evaluation index and reuse it for every RRF weight."""
    index_started = time.perf_counter()
    document_vectors = np.asarray(
        runtime.encode_documents([document["content"] for document in documents]),
        dtype=np.float32,
    )
    embedding_build_ms = (time.perf_counter() - index_started) * 1000
    app_ids = [document["app_id"] for document in documents]
    semantic_rankings: list[list[str]] = []
    lexical_rankings: list[list[str]] = []
    semantic_latencies_ms: list[float] = []
    lexical_latencies_ms: list[float] = []
    query_vectors = np.asarray(
        runtime.encode_queries([query["query"] for query in queries]),
        dtype=np.float32,
    )
    latency_sample_size = min(100, len(queries))
    query_encoding_latencies_ms: list[float] = []
    for query in queries[:latency_sample_size]:
        before = time.perf_counter()
        runtime.encode_query(query["query"])
        query_encoding_latencies_ms.append((time.perf_counter() - before) * 1000)
    fallback_encoding_latency = (
        statistics.median(query_encoding_latencies_ms)
        if query_encoding_latencies_ms
        else 0.0
    )
    for query_index, query in enumerate(queries):
        before = time.perf_counter()
        query_vector = query_vectors[query_index]
        scores = document_vectors @ query_vector
        semantic = [
            app_ids[index]
            for index in np.argsort(-scores, kind="stable")[
                :EVALUATION_CANDIDATE_LIMIT
            ].tolist()
        ]
        ranking_latency = (time.perf_counter() - before) * 1000
        semantic_latencies_ms.append(
            (
                query_encoding_latencies_ms[query_index]
                if query_index < latency_sample_size
                else fallback_encoding_latency
            )
            + ranking_latency
        )
        semantic_rankings.append(semantic)
        before = time.perf_counter()
        lexical = lexical_rank(query["query"], documents)[
            :EVALUATION_CANDIDATE_LIMIT
        ]
        lexical_latencies_ms.append((time.perf_counter() - before) * 1000)
        lexical_rankings.append(lexical)
    index_metrics: dict[str, float | int] = {
        "hnswRecallAt20": 0.0,
        "hnswBuildMs": 0.0,
        "hnswIndexBytes": 0,
    }
    if benchmark_store is not None:
        index_metrics = benchmark_store.benchmark_hnsw(
            dimensions=runtime.registered.dimensions,
            app_ids=app_ids,
            document_vectors=document_vectors.tolist(),
            query_vectors=query_vectors.tolist(),
        )
    return PreparedRuntimeEvaluation(
        queries=queries,
        semantic_rankings=semantic_rankings,
        lexical_rankings=lexical_rankings,
        semantic_latencies_ms=semantic_latencies_ms,
        lexical_latencies_ms=lexical_latencies_ms,
        embedding_build_ms=embedding_build_ms,
        document_vector_bytes=int(document_vectors.nbytes),
        index_metrics=index_metrics,
        latency_sample_size=latency_sample_size,
    )


def evaluate_prepared_runtime(
    prepared: PreparedRuntimeEvaluation,
    *,
    variant: str,
    semantic_weight: float | None,
) -> dict[str, Any]:
    rankings: list[tuple[dict[str, Any], list[str]]] = []
    latencies: list[float] = []
    for index, query in enumerate(prepared.queries):
        semantic = prepared.semantic_rankings[index]
        if semantic_weight is None:
            ranked = semantic
            latency = prepared.semantic_latencies_ms[index]
        else:
            before = time.perf_counter()
            ranked = reciprocal_rank_fusion(
                prepared.lexical_rankings[index],
                semantic,
                semantic_weight=semantic_weight,
            )
            fusion_ms = (time.perf_counter() - before) * 1000
            latency = (
                prepared.semantic_latencies_ms[index]
                + prepared.lexical_latencies_ms[index]
                + fusion_ms
            )
        rankings.append((query, ranked))
        latencies.append(latency)
    elapsed = sum(latencies) / 1000
    relevant = [(row, set(row["relevantAppIds"]), ranked) for row, ranked in rankings]
    navigational = [
        (row, ranked)
        for row, ranked in rankings
        if row["kind"] in {"navigation-name", "navigation-package"}
    ]
    index_metrics = prepared.index_metrics
    hnsw_bytes = int(index_metrics["hnswIndexBytes"])
    return {
        "variant": variant,
        "ndcgAt10": mean(ndcg(ranked, truth, 10) for _, truth, ranked in relevant),
        "mrrAt10": mean(reciprocal_rank(ranked, truth, 10) for _, truth, ranked in relevant),
        "mapAt10": mean(average_precision(ranked, truth, 10) for _, truth, ranked in relevant),
        "recallAt10": mean(recall(ranked, truth, 10) for _, truth, ranked in relevant),
        "recallAt20": mean(recall(ranked, truth, 20) for _, truth, ranked in relevant),
        "exactMrrAt1": mean(
            reciprocal_rank(ranked, {row["positiveAppId"]}, 1)
            for row, ranked in navigational
        ),
        "p50Ms": statistics.median(latencies) if latencies else 0.0,
        "p95Ms": percentile(latencies, 0.95),
        "p99Ms": percentile(latencies, 0.99),
        "throughputQps": len(prepared.queries) / elapsed if elapsed else 0.0,
        "embeddingBuildMs": prepared.embedding_build_ms,
        "hnswBuildMs": index_metrics["hnswBuildMs"],
        "indexBuildMs": (
            prepared.embedding_build_ms + float(index_metrics["hnswBuildMs"])
        ),
        "hnswRecallAt20": index_metrics["hnswRecallAt20"],
        "vectorBytes": prepared.document_vector_bytes,
        "hnswIndexBytes": hnsw_bytes,
        "indexBytes": prepared.document_vector_bytes + hnsw_bytes,
        "rssBytes": psutil.Process(os.getpid()).memory_info().rss,
        "vramBytes": accelerator_memory_bytes(),
        "semanticWeight": semantic_weight,
        "latencySampleSize": prepared.latency_sample_size,
    }


def evaluate_lexical(
    documents: list[dict[str, Any]],
    queries: list[dict[str, Any]],
) -> dict[str, Any]:
    latencies = []
    rankings = []
    started = time.perf_counter()
    for query in queries:
        before = time.perf_counter()
        ranked = lexical_rank(query["query"], documents)
        latencies.append((time.perf_counter() - before) * 1000)
        rankings.append((query, ranked))
    elapsed = time.perf_counter() - started
    truth_rows = [
        (row, set(row["relevantAppIds"]), ranked)
        for row, ranked in rankings
    ]
    navigational = [
        (row, ranked)
        for row, ranked in rankings
        if row["kind"] in {"navigation-name", "navigation-package"}
    ]
    return {
        "variant": "lexical",
        "ndcgAt10": mean(ndcg(ranked, truth, 10) for _, truth, ranked in truth_rows),
        "mrrAt10": mean(
            reciprocal_rank(ranked, truth, 10) for _, truth, ranked in truth_rows
        ),
        "mapAt10": mean(
            average_precision(ranked, truth, 10) for _, truth, ranked in truth_rows
        ),
        "recallAt10": mean(recall(ranked, truth, 10) for _, truth, ranked in truth_rows),
        "recallAt20": mean(recall(ranked, truth, 20) for _, truth, ranked in truth_rows),
        "exactMrrAt1": mean(
            reciprocal_rank(ranked, {row["positiveAppId"]}, 1)
            for row, ranked in navigational
        ),
        "p50Ms": statistics.median(latencies) if latencies else 0.0,
        "p95Ms": percentile(latencies, 0.95),
        "p99Ms": percentile(latencies, 0.99),
        "throughputQps": len(queries) / elapsed if elapsed else 0.0,
        "embeddingBuildMs": 0.0,
        "hnswBuildMs": 0.0,
        "indexBuildMs": 0.0,
        "hnswRecallAt20": 0.0,
        "vectorBytes": 0,
        "hnswIndexBytes": 0,
        "indexBytes": 0,
        "rssBytes": psutil.Process(os.getpid()).memory_info().rss,
        "vramBytes": accelerator_memory_bytes(),
        "semanticWeight": None,
    }


def accelerator_memory_bytes() -> int:
    try:
        import torch

        if torch.cuda.is_available():
            return int(torch.cuda.memory_allocated())
    except Exception:
        return 0
    return 0


def inherit_index_metrics(
    target: dict[str, Any],
    source: dict[str, Any],
) -> None:
    for key in (
        "embeddingBuildMs",
        "hnswBuildMs",
        "indexBuildMs",
        "hnswRecallAt20",
        "vectorBytes",
        "hnswIndexBytes",
        "indexBytes",
    ):
        target[key] = source[key]


def percentile(values: list[float], quantile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, round((len(ordered) - 1) * quantile)))
    return ordered[index]


def score_variants(
    metrics: list[dict[str, Any]],
    *,
    lexical_exact: float,
) -> list[dict[str, Any]]:
    quality_values = [row["ndcgAt10"] for row in metrics]
    inverse_latency = [1.0 / max(row["p95Ms"], 0.001) for row in metrics]
    inverse_memory = [
        1.0
        / max(
            row["rssBytes"] + row["vramBytes"] + row["indexBytes"],
            1,
        )
        for row in metrics
    ]
    for rows, key in (
        (quality_values, "qualityNormalized"),
        (inverse_latency, "latencyNormalized"),
        (inverse_memory, "memoryNormalized"),
    ):
        low, high = min(rows), max(rows)
        for metric, value in zip(metrics, rows, strict=True):
            metric[key] = 1.0 if high == low else (value - low) / (high - low)
    zero_shot_quality = {
        (row["modelKey"], row.get("semanticWeight")): row["ndcgAt10"]
        for row in metrics
        if row.get("stage") == "zero-shot"
    }
    for metric in metrics:
        metric["totalScore"] = (
            0.70 * metric["qualityNormalized"]
            + 0.20 * metric["latencyNormalized"]
            + 0.10 * metric["memoryNormalized"]
        )
        metric["eligible"] = (
            metric.get("stage") == "fine-tuned"
            and metric["exactMrrAt1"] >= lexical_exact
            and metric["ndcgAt10"]
            > zero_shot_quality.get(
                (metric.get("modelKey"), metric.get("semanticWeight")),
                1.0,
            )
        )
    return metrics


def write_reports(
    metrics: list[dict[str, Any]],
    *,
    selected: str | None,
    report_dir: Path,
    run_id: str,
    dataset_hash: str,
    smoke: bool = False,
) -> dict[str, str]:
    report_dir.mkdir(parents=True, exist_ok=True)
    json_path = report_dir / f"{run_id}.json"
    csv_path = report_dir / f"{run_id}.csv"
    markdown_path = report_dir / f"{run_id}.md"
    payload = {
        "runId": run_id,
        "datasetHash": dataset_hash,
        "selectedModelVersion": selected,
        "smoke": smoke,
        "metrics": metrics,
    }
    json_path.write_text(
        json.dumps(payload, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    fieldnames = sorted({key for row in metrics for key in row})
    with csv_path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(metrics)
    markdown = [
        "# Benchmark de búsqueda semántica",
        "",
        f"- Dataset: `{dataset_hash}`",
        f"- Modelo seleccionado: `{selected or 'ninguno; se conserva E5 zero-shot'}`",
        (
            "- Alcance: `smoke`; un paso y subconjunto determinista, "
            "sin selección ni activación."
            if smoke
            else "- Alcance: entrenamiento y evaluación completos."
        ),
        "",
        "| Variante | nDCG@10 | MRR@10 | Recall@20 | HNSW@20 | p95 ms | Score | Elegible |",
        "|---|---:|---:|---:|---:|---:|---:|:---:|",
    ]
    for row in sorted(metrics, key=lambda value: value.get("totalScore", 0), reverse=True):
        markdown.append(
            "| {variant} | {ndcg:.4f} | {mrr:.4f} | {recall:.4f} | "
            "{hnsw:.4f} | {p95:.2f} | {score:.4f} | {eligible} |".format(
                variant=row["variant"],
                ndcg=row["ndcgAt10"],
                mrr=row["mrrAt10"],
                recall=row["recallAt20"],
                hnsw=row["hnswRecallAt20"],
                p95=row["p95Ms"],
                score=row.get("totalScore", 0),
                eligible="sí" if row.get("eligible") else "no",
            )
        )
    markdown_path.write_text("\n".join(markdown) + "\n", encoding="utf-8")
    return {
        "json": str(json_path),
        "csv": str(csv_path),
        "markdown": str(markdown_path),
    }


def run_training(*, smoke: bool = False) -> dict[str, Any]:
    settings = get_settings()
    database = Database(settings)
    database.open()
    database.migrate()
    store = SemanticStore(database)
    try:
        documents = store.active_documents()
        if len(documents) < 2:
            raise RuntimeError("semantic_training_requires_two_documents")
        if smoke:
            documents = sorted(
                documents,
                key=lambda row: hashlib.sha256(
                    f"{settings.trainer_seed}:smoke:{row['app_id']}".encode()
                ).hexdigest(),
            )[:512]
        queries = build_query_snapshot(documents, settings.trainer_seed)
        dataset_hash, snapshot_dir = write_snapshot(
            documents,
            queries,
            root=Path(settings.model_cache_dir),
            seed=settings.trainer_seed,
        )
        train_rows = [row for row in queries if row["split"] == "train"]
        validation_rows = [row for row in queries if row["split"] == "validation"]
        test_rows = [row for row in queries if row["split"] == "test"]
        if not validation_rows:
            validation_rows = train_rows[-max(1, len(train_rows) // 10) :]
        if not test_rows:
            test_rows = validation_rows
        if smoke:
            train_rows = train_rows[: max(2, settings.trainer_batch_size)]
            validation_rows = validation_rows[: max(1, settings.trainer_batch_size)]
            test_rows = test_rows[:10]
            required_ids = {
                app_id
                for row in train_rows + validation_rows + test_rows
                for app_id in (
                    [row["positiveAppId"]]
                    + row["relevantAppIds"]
                    + row.get("hardNegativeAppIds", [])
                )
            }
            deterministic_documents = sorted(
                documents,
                key=lambda row: hashlib.sha256(
                    f"{settings.trainer_seed}:{row['app_id']}".encode()
                ).hexdigest(),
            )
            evaluation_documents = [
                document
                for document in deterministic_documents
                if document["app_id"] in required_ids
            ]
            evaluation_ids = {
                document["app_id"] for document in evaluation_documents
            }
            evaluation_documents.extend(
                document
                for document in deterministic_documents
                if document["app_id"] not in evaluation_ids
            )
            evaluation_documents = evaluation_documents[: max(256, len(required_ids))]
        else:
            evaluation_documents = documents

        metrics: list[dict[str, Any]] = [
            evaluate_lexical(evaluation_documents, validation_rows)
        ]
        effective_max_steps = 1 if smoke else settings.trainer_max_steps
        for key in settings.trainer_models:
            definition = MODELS_BY_KEY[key]
            base_model = store.model(definition.zero_shot_version)
            zero_runtime = EmbeddingRuntime(
                base_model,
                device=settings.device,
                cache_dir=settings.model_cache_dir,
                batch_size=settings.index_batch_size,
            )
            zero_prepared = prepare_runtime_evaluation(
                zero_runtime,
                evaluation_documents,
                validation_rows,
                benchmark_store=store,
            )
            zero = evaluate_prepared_runtime(
                zero_prepared,
                variant=f"{key}:zero-shot",
                semantic_weight=None,
            )
            zero.update({"modelKey": key, "stage": "zero-shot", "modelVersion": definition.zero_shot_version})
            metrics.append(zero)
            for weight in (0.5, 1.0, 1.5):
                hybrid = evaluate_prepared_runtime(
                    zero_prepared,
                    variant=f"{key}:zero-shot:hybrid:{weight}",
                    semantic_weight=weight,
                )
                hybrid.update({"modelKey": key, "stage": "zero-shot", "modelVersion": definition.zero_shot_version})
                metrics.append(hybrid)
            del zero_prepared, zero_runtime
            gc.collect()

            training_kind = "lora-smoke" if smoke else "lora"
            trained_version = (
                f"{key}@{definition.revision}:{training_kind}:{dataset_hash[:12]}"
            )
            artifact = Path(settings.model_cache_dir) / "trained" / trained_version
            if not (artifact / "training-complete.json").exists():
                if artifact.exists():
                    shutil.rmtree(artifact)
                temporary_artifact = artifact.with_name(
                    f".{artifact.name}.{uuid.uuid4().hex}.tmp"
                )
                temporary_artifact.mkdir(parents=True, exist_ok=False)
                try:
                    train_model(
                        base=definition,
                        train_rows=train_rows,
                        validation_rows=validation_rows,
                        output_dir=temporary_artifact,
                        settings=settings,
                        max_steps=effective_max_steps,
                    )
                    os.replace(temporary_artifact, artifact)
                except Exception:
                    shutil.rmtree(temporary_artifact, ignore_errors=True)
                    raise
            gc.collect()
            store.register_trained_model(
                base=base_model,
                model_version=trained_version,
                artifact_path=str(artifact),
                dataset_hash=dataset_hash,
                training_config={
                    "seed": settings.trainer_seed,
                    "epochs": settings.trainer_epochs,
                    "batchSize": settings.trainer_batch_size,
                    "maxSteps": effective_max_steps,
                    "loss": "MultipleNegativesRankingLoss",
                    "adapter": "LoRA",
                    "smoke": smoke,
                },
            )
            trained_model = store.model(trained_version)
            trained_runtime = EmbeddingRuntime(
                trained_model,
                device=settings.device,
                cache_dir=settings.model_cache_dir,
                batch_size=settings.index_batch_size,
            )
            tuned_prepared = prepare_runtime_evaluation(
                trained_runtime,
                evaluation_documents,
                validation_rows,
                benchmark_store=store,
            )
            tuned = evaluate_prepared_runtime(
                tuned_prepared,
                variant=f"{key}:fine-tuned",
                semantic_weight=None,
            )
            tuned.update({"modelKey": key, "stage": "fine-tuned", "modelVersion": trained_version})
            metrics.append(tuned)
            for weight in (0.5, 1.0, 1.5):
                hybrid = evaluate_prepared_runtime(
                    tuned_prepared,
                    variant=f"{key}:fine-tuned:hybrid:{weight}",
                    semantic_weight=weight,
                )
                hybrid.update({"modelKey": key, "stage": "fine-tuned", "modelVersion": trained_version})
                metrics.append(hybrid)
            del tuned_prepared, trained_runtime
            gc.collect()

        lexical_exact = metrics[0]["exactMrrAt1"]
        scored = score_variants(metrics, lexical_exact=lexical_exact)
        if smoke:
            for row in scored:
                row["eligible"] = False
        eligible = [row for row in scored if row.get("eligible")]
        winner = max(eligible, key=lambda row: row["totalScore"]) if eligible else None
        selected = winner.get("modelVersion") if winner else None
        if selected:
            # The test partition is opened exactly once for the selected variant.
            selected_runtime = EmbeddingRuntime(
                store.model(selected),
                device=settings.device,
                cache_dir=settings.model_cache_dir,
                batch_size=settings.index_batch_size,
            )
            test_metric = evaluate_runtime(
                selected_runtime,
                evaluation_documents,
                test_rows,
                variant=f"{winner['variant']}:test-confirmation",
                semantic_weight=winner.get("semanticWeight"),
            )
            test_metric.update(
                {
                    "modelKey": winner["modelKey"],
                    "stage": "test-confirmation",
                    "modelVersion": selected,
                    "eligible": True,
                    "totalScore": winner["totalScore"],
                }
            )
            inherit_index_metrics(test_metric, winner)
            scored.append(test_metric)
            store.select_model(
                selected,
                rrf_weight=float(winner.get("semanticWeight") or 1.0),
            )
        run_id = str(uuid.uuid4())
        paths = write_reports(
            scored,
            selected=selected,
            report_dir=Path(settings.reports_dir),
            run_id=run_id,
            dataset_hash=dataset_hash,
            smoke=smoke,
        )
        store.save_benchmark_run(
            run_id=run_id,
            dataset_hash=dataset_hash,
            seed=settings.trainer_seed,
            configuration={
                "snapshotDirectory": str(snapshot_dir),
                "weights": {"quality": 0.7, "latency": 0.2, "memory": 0.1},
                "rrfK": 60,
                "smoke": smoke,
            },
            metrics=scored,
            selected_model_version=selected,
            paths=paths,
        )
        return {
            "runId": run_id,
            "datasetHash": dataset_hash,
            "selectedModelVersion": selected,
            "reports": paths,
            "smoke": smoke,
        }
    finally:
        database.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Entrena y compara modelos semánticos")
    parser.add_argument("--smoke", action="store_true")
    arguments = parser.parse_args()
    print(json.dumps(run_training(smoke=arguments.smoke), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
