"""Entrena adaptadores LoRA con artefactos locales y compara variantes sobre un snapshot
reproducible.
La selección de un candidato registra su intención de despliegue; el indexador debe completar
el índice antes de activarlo.
"""

from __future__ import annotations

import argparse
import gc
import hashlib
import json
import os
import random
import shutil
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

from app.benchmark_store import SemanticBenchmarkStore
from app.config import Settings, get_settings
from app.database import Database
from app.embeddings import EmbeddingRuntime, RegisteredModel
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
    """Encuentra capas lineales de atención compatibles con LoRA usando sufijos query, value,
    q_proj y v_proj.

    Args:
        auto_model: Modelo transformer cuyo árbol de módulos se inspecciona sin modificar sus
            pesos.

    Returns:
        nombres de sufijo únicos en orden estable.

    Raises:
        RuntimeError: Si no existe ninguna capa lineal con un sufijo compatible.
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
    """Entrena LoRA offline con negativos difíciles cuando existen y guarda tanto adaptador como
    pesos fusionados.
    Recarga el artefacto, comprueba sus dimensiones y codifica una consulta antes de escribir
    la marca de finalización.

    Args:
        base: Definición fija del modelo base disponible en la carpeta manual.
        train_rows: Pares o tripletas de entrenamiento con negativos de la misma partición.
        validation_rows: Pares de validación utilizados al terminar cada época.
        output_dir: Directorio temporal de trabajo; el llamador publica el artefacto
            terminado.
        settings: Configuración de entrenamiento, dispositivo, directorios y tamaños de lote.
        max_steps: Límite de pasos de entrenamiento, interpretado por
            SentenceTransformerTrainer.

    Raises:
        RuntimeError: Si falta el artefacto base, no hay capas LoRA, el resultado no es PEFT o
            cambian las dimensiones.
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


@dataclass(frozen=True, slots=True)
class TrainingDataset:
    """Conserva el snapshot de una ejecución y sus particiones para comparar todos los modelos
    con las mismas entradas.

    Attributes:
        dataset_hash: Huella del snapshot que incluye semilla, documentos y consultas.
        snapshot_dir: Directorio con manifiesto y archivos JSONL.
        train_rows: Pares o tripletas de entrenamiento.
        validation_rows: Consultas usadas para puntuar variantes.
        test_rows: Consultas de confirmación del candidato.
        evaluation_documents: Corpus frente al que se calculan los rankings.
    """

    dataset_hash: str
    snapshot_dir: Path
    train_rows: list[dict[str, Any]]
    validation_rows: list[dict[str, Any]]
    test_rows: list[dict[str, Any]]
    evaluation_documents: list[dict[str, Any]]


def prepare_training_dataset(
    documents: list[dict[str, Any]], settings: Settings, *, smoke: bool,
) -> TrainingDataset:
    """Construye y guarda las consultas reproducibles, negativos y particiones; exige al menos
    dos documentos.
    En conjuntos pequeños reutiliza filas si faltan validación o prueba. En smoke limita el
    corpus preservando positivos y negativos requeridos.

    Args:
        documents: Documentos del catálogo con app_id, contenido, huella y metadatos
            utilizados en la evaluación.
        settings: Configuración de entrenamiento, dispositivo, directorios y tamaños de lote.
        smoke: Si es True, usa un subconjunto determinista y un paso; ningún resultado será
            elegible para selección.

    Returns:
        snapshot y particiones que se reutilizan en entrenamiento y comparación.

    Raises:
        RuntimeError: Si el catálogo contiene menos de dos documentos.
    """
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

    return TrainingDataset(
        dataset_hash, snapshot_dir, train_rows, validation_rows, test_rows, evaluation_documents,
    )


def evaluate_training_variants(
    model: RegisteredModel, key: str, stage: str, dataset: TrainingDataset,
    settings: Settings, benchmark_store: SemanticBenchmarkStore,
) -> list[dict[str, Any]]:
    """Codifica una sola vez corpus y consultas de validación para evaluar la variante semántica
    y tres pesos híbridos.
    Reutiliza la medición HNSW y libera el runtime al terminar.

    Args:
        model: Versión registrada cuya codificación debe evaluarse.
        key: Clave de la familia de modelo utilizada en las variantes e informes.
        stage: Etapa evaluada, zero-shot o fine-tuned, utilizada para comparar el efecto del
            entrenamiento.
        dataset: Snapshot y particiones que comparten las variantes comparadas.
        settings: Configuración de entrenamiento, dispositivo, directorios y tamaños de lote.
        benchmark_store: Adaptador para medir HNSW real; None omite esa medición y conserva
            sus métricas en cero.

    Returns:
        cuatro filas de métricas: semántica pura y RRF con pesos 0.5, 1.0 y 1.5.
    """
    runtime = EmbeddingRuntime(
        model, device=settings.device, cache_dir=settings.model_cache_dir,
        batch_size=settings.index_batch_size,
    )
    prepared = prepare_runtime_evaluation(
        runtime, dataset.evaluation_documents, dataset.validation_rows,
        benchmark_store=benchmark_store,
    )
    metrics = []
    for weight in (None, 0.5, 1.0, 1.5):
        variant = f"{key}:{stage}" if weight is None else f"{key}:{stage}:hybrid:{weight}"
        metric = evaluate_prepared_runtime(prepared, variant=variant, semantic_weight=weight)
        metric.update({"modelKey": key, "stage": stage, "modelVersion": model.model_version})
        metrics.append(metric)
    del prepared, runtime
    gc.collect()
    return metrics


def prepare_trained_model(
    definition: ModelDefinition, base_model: RegisteredModel, key: str,
    dataset: TrainingDataset, settings: Settings, store: SemanticStore, *, smoke: bool,
) -> RegisteredModel:
    """Reutiliza un artefacto completo o entrena en un directorio temporal y lo publica mediante
    reemplazo atómico.
    Registra después la versión y su configuración; ante un fallo elimina solo el temporal de
    esta ejecución.

    Args:
        definition: Repositorio, revisión, dimensiones y prefijos del modelo base.
        base_model: Registro de la versión base del que se heredan identidad y configuración.
        key: Clave de la familia de modelo utilizada en las variantes e informes.
        dataset: Snapshot y particiones que comparten las variantes comparadas.
        settings: Configuración de entrenamiento, dispositivo, directorios y tamaños de lote.
        store: Almacén que registra versiones y selección; la selección todavía no activa el
            modelo.
        smoke: Si es True, usa un subconjunto determinista y un paso; ningún resultado será
            elegible para selección.

    Returns:
        registro del modelo entrenado, sin activarlo.
    """
    effective_max_steps = 1 if smoke else settings.trainer_max_steps
    training_kind = "lora-smoke" if smoke else "lora"
    trained_version = f"{key}@{definition.revision}:{training_kind}:{dataset.dataset_hash[:12]}"
    artifact = Path(settings.model_cache_dir) / "trained" / trained_version
    if not (artifact / "training-complete.json").exists():
        if artifact.exists():
            shutil.rmtree(artifact)
        temporary_artifact = artifact.with_name(f".{artifact.name}.{uuid.uuid4().hex}.tmp")
        temporary_artifact.mkdir(parents=True, exist_ok=False)
        try:
            train_model(
                base=definition,
                train_rows=dataset.train_rows,
                validation_rows=dataset.validation_rows,
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
        dataset_hash=dataset.dataset_hash,
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
    return trained_model


def select_training_winner(
    metrics: list[dict[str, Any]], dataset: TrainingDataset,
    settings: Settings, store: SemanticStore, *, smoke: bool,
) -> tuple[list[dict[str, Any]], str | None]:
    """Puntúa variantes y selecciona el candidato elegible de mayor puntuación; smoke deshabilita
    toda elegibilidad.
    Añade una evaluación de confirmación sobre test y registra selección y peso RRF para la
    futura indexación.

    Args:
        metrics: Métricas por variante, con identificadores, calidad, latencias y tamaño de
            índices.
        dataset: Snapshot y particiones que comparten las variantes comparadas.
        settings: Configuración de entrenamiento, dispositivo, directorios y tamaños de lote.
        store: Almacén que registra versiones y selección; la selección todavía no activa el
            modelo.
        smoke: Si es True, usa un subconjunto determinista y un paso; ningún resultado será
            elegible para selección.

    Returns:
        métricas puntuadas y versión elegida, o None cuando no hay ganador.
    """
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
            dataset.evaluation_documents,
            dataset.test_rows,
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
    return scored, selected


def run_training(*, smoke: bool = False) -> dict[str, Any]:
    """Abre la base de datos y coordina snapshot, entrenamiento, evaluación, selección y
    publicación de informes.
    Cierra el pool al terminar el trabajo, incluso cuando una de esas fases falla.

    Args:
        smoke: Si es True, usa un subconjunto determinista y un paso; ningún resultado será
            elegible para selección.

    Returns:
        UUID de ejecución, hash del dataset, candidato seleccionado, rutas de informes y
            alcance smoke.
    """
    settings = get_settings()
    database = Database(settings)
    database.open()
    database.verify_schema()
    store = SemanticStore(database)
    benchmark_store = SemanticBenchmarkStore(database)
    try:
        dataset = prepare_training_dataset(store.active_documents(), settings, smoke=smoke)
        metrics = [evaluate_lexical(dataset.evaluation_documents, dataset.validation_rows)]
        for key in settings.trainer_models:
            definition = MODELS_BY_KEY[key]
            base_model = store.model(definition.zero_shot_version)
            metrics.extend(evaluate_training_variants(
                base_model, key, "zero-shot", dataset, settings, benchmark_store,
            ))
            trained_model = prepare_trained_model(
                definition, base_model, key, dataset, settings, store, smoke=smoke,
            )
            metrics.extend(evaluate_training_variants(
                trained_model, key, "fine-tuned", dataset, settings, benchmark_store,
            ))

        scored, selected = select_training_winner(metrics, dataset, settings, store, smoke=smoke)
        run_id = str(uuid.uuid4())
        paths = write_reports(
            scored,
            selected=selected,
            report_dir=Path(settings.reports_dir),
            run_id=run_id,
            dataset_hash=dataset.dataset_hash,
            smoke=smoke,
        )
        benchmark_store.save_benchmark_run(
            run_id=run_id,
            dataset_hash=dataset.dataset_hash,
            seed=settings.trainer_seed,
            configuration={
                "snapshotDirectory": str(dataset.snapshot_dir),
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
            "datasetHash": dataset.dataset_hash,
            "selectedModelVersion": selected,
            "reports": paths,
            "smoke": smoke,
        }
    finally:
        database.close()


def main() -> None:
    """Interpreta --smoke, ejecuta la campaña de entrenamiento y escribe su resultado JSON en la
    salida estándar.
    """
    parser = argparse.ArgumentParser(description="Entrena y compara modelos semánticos")
    parser.add_argument("--smoke", action="store_true")
    arguments = parser.parse_args()
    print(json.dumps(run_training(smoke=arguments.smoke), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
