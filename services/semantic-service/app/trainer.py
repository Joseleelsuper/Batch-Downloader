"""Implementa las responsabilidades del módulo `trainer`."""

from __future__ import annotations

import argparse
import gc
import hashlib
import json
import os
import random
import shutil
import uuid
from pathlib import Path
from typing import Any

import numpy as np

from app.benchmark_store import SemanticBenchmarkStore
from app.config import get_settings
from app.database import Database
from app.embeddings import EmbeddingRuntime
from app.model_registry import MODELS_BY_KEY, ModelDefinition
from app.runtime_evaluation import (
    evaluate_lexical,
    evaluate_prepared_runtime,
    evaluate_runtime,
    inherit_index_metrics,
    prepare_runtime_evaluation,
    score_variants,
)
from app.store import SemanticStore
from app.training_dataset import build_query_snapshot, write_snapshot
from app.training_reports import write_reports


def discover_lora_targets(auto_model: Any) -> list[str]:
    """Ejecuta la operación `discover_lora_targets`.

    Args:
        auto_model (Any): Valor de `auto_model` utilizado por la operación.

    Returns:
        list[str]: Colección de elementos obtenidos por la operación.

    Throws:
        RuntimeError: Si el estado de ejecución impide completar la operación.
    """
    endings = {"query", "value", "q_proj", "v_proj"}
    targets = sorted(
        {
            name.rsplit(".", 1)[-1]
            for name, module in auto_model.named_modules()
            if name.rsplit(".", 1)[-1] in endings and module.__class__.__name__.lower() == "linear"
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
    """Ejecuta la operación `train_model`.

    Args:
        base (ModelDefinition): Valor de `base` utilizado por la operación.
        train_rows (list[dict[str, Any]]): Valor de `train_rows` utilizado por la operación.
        validation_rows (list[dict[str, Any]]): Valor de `validation_rows` utilizado por la
            operación.
        output_dir (Path): Valor de `output_dir` utilizado por la operación.
        settings (Any): Configuración del servicio.
        max_steps (int): Valor de `max_steps` utilizado por la operación.

    Throws:
        RuntimeError: Si el estado de ejecución impide completar la operación.
    """
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
    manual_root = Path(settings.model_cache_dir) / "manual"
    directory_name = base.repository.replace("/", "--")
    base_path = manual_root / directory_name / base.revision
    if not base_path.is_dir():
        base_path = manual_root / directory_name
    if not base_path.is_dir():
        raise RuntimeError("model_artifact_missing")
    model = SentenceTransformer(
        str(base_path),
        device=settings.device,
        cache_folder=settings.model_cache_dir,
        trust_remote_code=False,
        local_files_only=True,
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
        local_files_only=True,
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


def run_training(*, smoke: bool = False) -> dict[str, Any]:
    """Ejecuta la operación `training`.

    Args:
        smoke (bool): Valor de `smoke` utilizado por la operación.

    Returns:
        dict[str, Any]: Mapa con los datos producidos por la operación.

    Throws:
        RuntimeError: Si el estado de ejecución impide completar la operación.
    """
    settings = get_settings()
    database = Database(settings)
    database.open()
    database.verify_schema()
    store = SemanticStore(database)
    benchmark_store = SemanticBenchmarkStore(database)
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
            evaluation_ids = {document["app_id"] for document in evaluation_documents}
            evaluation_documents.extend(
                document
                for document in deterministic_documents
                if document["app_id"] not in evaluation_ids
            )
            evaluation_documents = evaluation_documents[: max(256, len(required_ids))]
        else:
            evaluation_documents = documents

        metrics: list[dict[str, Any]] = [evaluate_lexical(evaluation_documents, validation_rows)]
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
                benchmark_store=benchmark_store,
            )
            zero = evaluate_prepared_runtime(
                zero_prepared,
                variant=f"{key}:zero-shot",
                semantic_weight=None,
            )
            zero.update(
                {
                    "modelKey": key,
                    "stage": "zero-shot",
                    "modelVersion": definition.zero_shot_version,
                }
            )
            metrics.append(zero)
            for weight in (0.5, 1.0, 1.5):
                hybrid = evaluate_prepared_runtime(
                    zero_prepared,
                    variant=f"{key}:zero-shot:hybrid:{weight}",
                    semantic_weight=weight,
                )
                hybrid.update(
                    {
                        "modelKey": key,
                        "stage": "zero-shot",
                        "modelVersion": definition.zero_shot_version,
                    }
                )
                metrics.append(hybrid)
            del zero_prepared, zero_runtime
            gc.collect()

            training_kind = "lora-smoke" if smoke else "lora"
            trained_version = f"{key}@{definition.revision}:{training_kind}:{dataset_hash[:12]}"
            artifact = Path(settings.model_cache_dir) / "trained" / trained_version
            if not (artifact / "training-complete.json").exists():
                if artifact.exists():
                    shutil.rmtree(artifact)
                temporary_artifact = artifact.with_name(f".{artifact.name}.{uuid.uuid4().hex}.tmp")
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
                benchmark_store=benchmark_store,
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
                hybrid.update(
                    {
                        "modelKey": key,
                        "stage": "fine-tuned",
                        "modelVersion": trained_version,
                    }
                )
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
        if selected and winner is not None:
            # La partición de prueba se abre una sola vez para la variante seleccionada.
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
        benchmark_store.save_benchmark_run(
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
    """Ejecuta el punto de entrada del módulo."""
    parser = argparse.ArgumentParser(description="Entrena y compara modelos semánticos")
    parser.add_argument("--smoke", action="store_true")
    arguments = parser.parse_args()
    print(json.dumps(run_training(smoke=arguments.smoke), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
