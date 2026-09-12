"""Mantiene la proyección reconstruible del catálogo, su cola de embeddings y la cobertura de
cada índice.
"""
from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime, timedelta
from typing import Any

from psycopg import sql

from app.benchmark_store import SemanticBenchmarkStore
from app.catalog_fingerprint import model_catalog_coverage
from app.database import Database
from app.embeddings import RegisteredModel, vector_literal
from app.model_registry import model_index_name


def ensure_model_hnsw_index(
    connection: Any,
    *,
    model_version: str,
    dimensions: int,
) -> None:
    """Crea el índice parcial HNSW para una versión concreta con nombres SQL escapados y
    dimensiones comprobadas.

    Args:
        connection: Conexión de la transacción del llamador, que confirma o revierte todos sus
            cambios.
        model_version: Identidad inmutable del modelo y revisión cuyos embeddings se procesan.
        dimensions: Número de componentes del vector; HNSW admite de 1 a 2000.

    Raises:
        ValueError: Si las dimensiones están fuera del intervalo de 1 a 2000.
    """
    if not 1 <= dimensions <= 2000:
        raise ValueError(f"unsupported_hnsw_dimensions:{dimensions}")
    connection.execute(
        sql.SQL(
            "CREATE INDEX IF NOT EXISTS {} ON software_embeddings "
            "USING hnsw ((embedding::vector({})) vector_cosine_ops) "
            "WHERE model_version = {}"
        ).format(
            sql.Identifier(model_index_name(model_version)),
            sql.SQL(str(dimensions)),
            sql.Literal(model_version),
        )
    )


def invalidate_complete_indexes(connection: Any) -> None:
    """Invalida índices completos cuando cambia el catálogo; mantiene active como estado de
    despliegue pero impide servir búsquedas con complete=False.

    Args:
        connection: Conexión de la transacción del llamador, que confirma o revierte todos sus
            cambios.
    """
    connection.execute(
        """
        UPDATE embedding_models model
        SET deployment_state = CASE
                WHEN model.active THEN 'active'
                ELSE 'stale'
            END
        WHERE EXISTS (
            SELECT 1
            FROM semantic_index_state state
            WHERE state.model_version = model.model_version
              AND state.complete = TRUE
        )
        """
    )
    connection.execute(
        """
        UPDATE semantic_index_state
        SET complete = FALSE, built_at = now()
        WHERE complete = TRUE
        """
    )


class SemanticStore:
    """Sincroniza documentos y vectores de PostgreSQL y publica cobertura para que las consultas
    usen únicamente índices completos.

    See Also:
        app.indexer.SemanticIndexer: Sincroniza páginas y codifica trabajos reservados.
        app.search_router.semantic_search: Consulta el modelo con cobertura completa.
    """
    def __init__(self, database: Database) -> None:
        """Conecta proyección y medición HNSW al mismo pool del servicio.

        Args:
            database: Acceso transaccional al pool; durante una operación exclusiva reutiliza
                la conexión reservada bajo el bloqueo del proceso.
        """
        self.database = database

        self.benchmarks = SemanticBenchmarkStore(database)
        """Transacciones aisladas de medición y persistencia de benchmarks."""

    def active_model(self) -> tuple[RegisteredModel, str] | None:
        """Resuelve el modelo activo únicamente cuando su estado de índice está marcado completo.

        Returns:
            registro del modelo y versión del índice, o None si no existe una combinación
                lista.
        """
        def query(connection):
            """Lee modelo activo y completitud del índice en la misma consulta.

            Args:
                connection: Conexión de la transacción del llamador, que confirma o revierte
                    todos sus cambios.

            Returns:
                identidad registrada y versión de índice o None.
            """
            row = connection.execute(
                """
                SELECT m.*, s.index_version
                FROM embedding_models m
                JOIN semantic_index_state s ON s.model_version = m.model_version
                WHERE m.active = TRUE AND s.complete = TRUE
                ORDER BY m.activated_at DESC NULLS LAST
                LIMIT 1
                """
            ).fetchone()
            if not row:
                return None
            return RegisteredModel.from_row(row), row["index_version"]

        return self.database.run(query)

    def model(self, model_version: str) -> RegisteredModel:
        """Recupera la configuración de una versión registrada sin exigir que esté activa.

        Args:
            model_version: Identidad inmutable del modelo y revisión cuyos embeddings se
                procesan.

        Returns:
            registro inmutable para construir un runtime local.

        Raises:
            LookupError: Si esa versión no está registrada.
        """
        row = self.database.run(
            lambda connection: connection.execute(
                "SELECT * FROM embedding_models WHERE model_version = %s",
                (model_version,),
            ).fetchone()
        )
        if not row:
            raise LookupError("embedding_model_not_registered")
        return RegisteredModel.from_row(row)

    def selected_model_version(self, fallback: str) -> str:
        """Prioriza el modelo activo y después el candidato seleccionado más reciente para la
        siguiente indexación.

        Args:
            fallback: Versión inicial utilizada si no existe modelo activo ni seleccionado.

        Returns:
            versión elegida o fallback si no hay activo ni seleccionado.
        """
        row = self.database.run(
            lambda connection: connection.execute(
                """
                SELECT model_version
                FROM embedding_models
                WHERE active = TRUE OR lifecycle_state = 'selected'
                ORDER BY CASE WHEN active THEN 0 ELSE 1 END, created_at DESC
                LIMIT 1
                """
            ).fetchone()
        )
        return row["model_version"] if row else fallback

    def exact_search(
        self,
        *,
        model: RegisteredModel,
        query_vector: list[float],
        minimum_similarity: float,
        limit: int,
    ) -> list[dict[str, Any]]:
        """Calcula distancia coseno exacta sobre documentos activos cuyos vectores coinciden con
        su hash actual.
        Deshabilita recorridos por índice solo en esta transacción para garantizar ranking
        exacto y desempate por UUID.

        Args:
            model: Registro con versión, prefijos y dimensiones de los vectores consultados.
            query_vector: Vector normalizado de la consulta con las dimensiones del modelo.
            minimum_similarity: Umbral inclusivo de similitud coseno para aceptar candidatos.
            limit: Máximo de filas que la consulta o reserva devuelve.

        Returns:
            candidatos ordenados, con appId, rango desde uno y similitud.
        """
        literal = vector_literal(query_vector)

        def query(connection):
            """Consulta candidatos por distancia y umbral con los recorridos aproximados
            deshabilitados localmente.

            Args:
                connection: Conexión de la transacción del llamador, que confirma o revierte
                    todos sus cambios.

            Returns:
                proyecciones ordenadas de candidatos aceptados.
            """
            connection.execute("SET LOCAL enable_indexscan = off")
            connection.execute("SET LOCAL enable_bitmapscan = off")
            rows = connection.execute(
                """
                SELECT e.app_id::text AS app_id,
                       1 - (e.embedding <=> %s::vector) AS similarity
                FROM software_embeddings e
                JOIN semantic_documents d ON d.app_id = e.app_id
                WHERE e.model_version = %s
                  AND e.content_hash = d.content_hash
                  AND d.active = TRUE
                  AND 1 - (e.embedding <=> %s::vector) >= %s
                ORDER BY e.embedding <=> %s::vector, e.app_id
                LIMIT %s
                """,
                (
                    literal,
                    model.model_version,
                    literal,
                    minimum_similarity,
                    literal,
                    limit,
                ),
            ).fetchall()
            return [
                {
                    "appId": row["app_id"],
                    "rank": index + 1,
                    "similarity": float(row["similarity"]),
                }
                for index, row in enumerate(rows)
            ]

        return self.database.run(query)

    def upsert_document_page(
        self,
        documents: list[dict[str, Any]],
        *,
        model_version: str,
        seen_at: datetime,
    ) -> int:
        """Sincroniza una página del catálogo y encola hashes sin embedding para el modelo
        seleccionado.
        Confirma documentos, trabajos e invalidación de índices conjuntamente; actualiza
        seen_at incluso si el contenido no cambió.

        Args:
            documents: Página de Scraper con appId, contentHash, content y metadata.
            model_version: Identidad inmutable del modelo y revisión cuyos embeddings se
                procesan.
            seen_at: Instante UTC compartido por todas las páginas del mismo barrido.

        Returns:
            número de documentos nuevos o con hash diferente.
        """
        changed = 0

        def mutate(connection):
            """Guarda documentos, recupera trabajos necesarios y retira la marca complete si se
            detecta contenido nuevo o modificado.

            Args:
                connection: Conexión de la transacción del llamador, que confirma o revierte
                    todos sus cambios.
            """
            nonlocal changed
            for document in documents:
                existing = connection.execute(
                    "SELECT content_hash FROM semantic_documents WHERE app_id = %s",
                    (document["appId"],),
                ).fetchone()
                if not existing or existing["content_hash"] != document["contentHash"]:
                    changed += 1
                connection.execute(
                    """
                    INSERT INTO semantic_documents (
                        app_id, content_hash, content, metadata, active, seen_at, updated_at
                    ) VALUES (%s, %s, %s, %s::jsonb, TRUE, %s, now())
                    ON CONFLICT (app_id) DO UPDATE SET
                        content_hash = EXCLUDED.content_hash,
                        content = EXCLUDED.content,
                        metadata = EXCLUDED.metadata,
                        active = TRUE,
                        seen_at = EXCLUDED.seen_at,
                        updated_at = CASE
                            WHEN semantic_documents.content_hash <> EXCLUDED.content_hash
                            THEN now() ELSE semantic_documents.updated_at END
                    """,
                    (
                        document["appId"],
                        document["contentHash"],
                        document["content"],
                        json.dumps(document["metadata"], ensure_ascii=False),
                        seen_at,
                    ),
                )
                connection.execute(
                    """
                    INSERT INTO embedding_jobs (
                        app_id, model_version, content_hash, status, available_at, updated_at
                    )
                    SELECT %s, %s, %s, 'queued', now(), now()
                    WHERE NOT EXISTS (
                        SELECT 1 FROM software_embeddings
                        WHERE app_id = %s
                          AND model_version = %s
                          AND content_hash = %s
                    )
                    ON CONFLICT (app_id, model_version, content_hash) DO UPDATE SET
                        status = CASE
                            WHEN embedding_jobs.status = 'completed' THEN embedding_jobs.status
                            ELSE 'queued'
                        END,
                        available_at = now(),
                        lease_owner = NULL,
                        lease_until = NULL,
                        updated_at = now()
                    """,
                    (
                        document["appId"],
                        model_version,
                        document["contentHash"],
                        document["appId"],
                        model_version,
                        document["contentHash"],
                    ),
                )
            if changed:
                # El hash de documento se comparte entre todas las proyecciones.
                # Marca como incompletos los estados publicados antes de construir
                # vectores para no servir un barrido parcialmente actualizado.
                invalidate_complete_indexes(connection)

        self.database.run(mutate)
        return changed

    def finish_sweep(self, seen_at: datetime) -> int:
        """Elimina documentos que no aparecieron en el barrido completo e invalida índices si
        hubo eliminaciones.

        Args:
            seen_at: Instante UTC compartido por todas las páginas del mismo barrido.

        Returns:
            número de documentos retirados; solo debe llamarse después de recibir todas las
                páginas.
        """
        def mutate(connection):
            """Elimina filas anteriores al inicio del barrido y retira completitud en la misma
            transacción.

            Args:
                connection: Conexión de la transacción del llamador, que confirma o revierte
                    todos sus cambios.

            Returns:
                número de documentos borrados.
            """
            result = connection.execute(
                """
                DELETE FROM semantic_documents
                WHERE seen_at < %s
                """,
                (seen_at,),
            )
            if result.rowcount:
                invalidate_complete_indexes(connection)
            return result.rowcount

        return self.database.run(mutate)

    def claim_jobs(
        self,
        *,
        model_version: str,
        owner: str,
        limit: int,
        lease_seconds: int,
    ) -> list[dict[str, Any]]:
        """Reserva trabajos de embeddings vencidos o disponibles para el modelo y hash actual
        usando SKIP LOCKED.

        Args:
            model_version: Identidad inmutable del modelo y revisión cuyos embeddings se
                procesan.
            owner: Identidad exclusiva del trabajador que reserva los trabajos.
            limit: Máximo de filas que la consulta o reserva devuelve.
            lease_seconds: Segundos de vigencia de la reserva a partir del reloj de
                PostgreSQL.

        Returns:
            trabajos processing con contenido, intentos incrementados y nueva reserva.
        """
        def mutate(connection):
            """Adquiere un lote elegible sin esperar filas bloqueadas por otros indexadores.

            Args:
                connection: Conexión de la transacción del llamador, que confirma o revierte
                    todos sus cambios.

            Returns:
                filas reservadas con el contenido a codificar.
            """
            return connection.execute(
                """
                WITH claimed AS (
                    SELECT j.id
                    FROM embedding_jobs j
                    JOIN semantic_documents d ON d.app_id = j.app_id
                    WHERE j.model_version = %s
                      AND d.active = TRUE
                      AND d.content_hash = j.content_hash
                      AND (
                        (j.status IN ('queued', 'failed') AND j.available_at <= now())
                        OR (j.status = 'processing' AND j.lease_until < now())
                      )
                    ORDER BY j.id
                    FOR UPDATE SKIP LOCKED
                    LIMIT %s
                )
                UPDATE embedding_jobs j
                SET status = 'processing',
                    attempts = attempts + 1,
                    lease_owner = %s,
                    lease_until = now() + (%s * interval '1 second'),
                    updated_at = now()
                FROM claimed, semantic_documents d
                WHERE j.id = claimed.id AND d.app_id = j.app_id
                RETURNING j.id, j.app_id::text AS app_id, j.content_hash, d.content
                """,
                (model_version, limit, owner, lease_seconds),
            ).fetchall()

        return self.database.run(mutate)

    def complete_jobs(
        self,
        *,
        model_version: str,
        jobs: list[dict[str, Any]],
        embeddings: list[list[float]],
    ) -> None:
        """Guarda vectores y marca completados los trabajos del lote en la misma transacción;
        exige correspondencia uno a uno mediante zip estricto.

        Args:
            model_version: Identidad inmutable del modelo y revisión cuyos embeddings se
                procesan.
            jobs: Trabajos previamente reservados con UUID, documento y hash del contenido.
            embeddings: Vectores correspondientes a jobs, en el mismo orden y con la misma
                longitud.
        """
        def mutate(connection):
            """Sustituye cada vector y libera la reserva de su trabajo después de comprobar la
            longitud del lote.

            Args:
                connection: Conexión de la transacción del llamador, que confirma o revierte
                    todos sus cambios.
            """
            for job, embedding in zip(jobs, embeddings, strict=True):
                connection.execute(
                    """
                    INSERT INTO software_embeddings (
                        app_id, model_version, content_hash, embedding, generated_at
                    ) VALUES (%s, %s, %s, %s::vector, now())
                    ON CONFLICT (app_id, model_version) DO UPDATE SET
                        content_hash = EXCLUDED.content_hash,
                        embedding = EXCLUDED.embedding,
                        generated_at = now()
                    """,
                    (
                        job["app_id"],
                        model_version,
                        job["content_hash"],
                        vector_literal(embedding),
                    ),
                )
                connection.execute(
                    """
                    UPDATE embedding_jobs
                    SET status = 'completed', lease_owner = NULL, lease_until = NULL,
                        last_error = NULL, updated_at = now()
                    WHERE id = %s
                    """,
                    (job["id"],),
                )

        self.database.run(mutate)

    def fail_jobs(self, jobs: list[dict[str, Any]], error: str) -> None:
        """Libera las reservas fallidas y programa otro intento dentro de 30 segundos con un
        error seguro acotado.

        Args:
            jobs: Trabajos previamente reservados con UUID, documento y hash del contenido.
            error: Descripción segura del fallo de codificación, recortada a 500 caracteres.
        """
        retry_at = datetime.now(UTC) + timedelta(seconds=30)
        self.database.run(
            lambda connection: [
                connection.execute(
                    """
                    UPDATE embedding_jobs
                    SET status = 'failed', available_at = %s, lease_owner = NULL,
                        lease_until = NULL, last_error = %s, updated_at = now()
                    WHERE id = %s
                    """,
                    (retry_at, error[:500], job["id"]),
                )
                for job in jobs
            ]
        )

    def coverage_and_promote(self, model_version: str) -> dict[str, Any]:
        """Recalcula cobertura y huella, crea HNSW al completarla y registra el estado preparado
        sin activar un modelo nuevo.

        Args:
            model_version: Identidad inmutable del modelo y revisión cuyos embeddings se
                procesan.

        Returns:
            conteos, completitud y versión de índice derivada de modelo y huella.

        Raises:
            LookupError: Si se alcanza cobertura completa pero la versión ya no está
                registrada.
        """

        def mutate(connection):
            """Publica cobertura, índice físico y estado de despliegue con la misma vista
            transaccional del catálogo.

            Args:
                connection: Conexión de la transacción del llamador, que confirma o revierte
                    todos sus cambios.

            Returns:
                cobertura esperada e indexada, completitud y versión del índice.

            Raises:
                LookupError: Si falta el registro del modelo que se iba a preparar.
            """
            coverage = model_catalog_coverage(connection, model_version)
            expected = int(coverage["expected"] or 0)
            indexed = int(coverage["indexed"] or 0)
            complete = expected > 0 and expected == indexed
            index_version = hashlib.sha256(
                f"{model_version}:{coverage['snapshot_hash']}".encode()
            ).hexdigest()[:20]
            if complete:
                model_row = connection.execute(
                    "SELECT dimensions FROM embedding_models WHERE model_version = %s",
                    (model_version,),
                ).fetchone()
                if not model_row:
                    raise LookupError("embedding_model_not_registered")
                ensure_model_hnsw_index(
                    connection,
                    model_version=model_version,
                    dimensions=int(model_row["dimensions"]),
                )
            connection.execute(
                """
                INSERT INTO semantic_index_state (
                    model_version, index_version, snapshot_hash,
                    expected_documents, indexed_documents, complete, built_at
                ) VALUES (%s, %s, %s, %s, %s, %s, now())
                ON CONFLICT (model_version) DO UPDATE SET
                    index_version = EXCLUDED.index_version,
                    snapshot_hash = EXCLUDED.snapshot_hash,
                    expected_documents = EXCLUDED.expected_documents,
                    indexed_documents = EXCLUDED.indexed_documents,
                    complete = EXCLUDED.complete,
                    built_at = now()
                """,
                (
                    model_version,
                    index_version,
                    coverage["snapshot_hash"],
                    expected,
                    indexed,
                    complete,
                ),
            )
            connection.execute(
                """
                UPDATE embedding_models
                SET deployment_state = CASE
                        WHEN active THEN 'active'
                        WHEN %s THEN 'ready'
                        ELSE 'preparing'
                    END,
                    lifecycle_state = CASE
                        WHEN active THEN 'active'
                        WHEN lifecycle_state = 'selected' AND %s THEN 'registered'
                        ELSE lifecycle_state
                    END
                WHERE model_version = %s
                """,
                (complete, complete, model_version),
            )
            return {
                "expected": expected,
                "indexed": indexed,
                "complete": complete,
                "indexVersion": index_version,
            }

        return self.database.run(mutate)


    def active_documents(self) -> list[dict[str, Any]]:
        """Recupera el corpus activo en orden estable de UUID para snapshots y evaluación
        reproducible.

        Returns:
            documentos con hash, texto y metadatos.
        """
        return self.database.run(
            lambda connection: connection.execute(
                """
                SELECT app_id::text AS app_id, content_hash, content, metadata
                FROM semantic_documents
                WHERE active = TRUE
                ORDER BY app_id
                """
            ).fetchall()
        )

    def register_trained_model(
        self,
        *,
        base: RegisteredModel,
        model_version: str,
        artifact_path: str,
        dataset_hash: str,
        training_config: dict[str, Any],
    ) -> None:
        """Registra una nueva versión entrenada con ubicación y parámetros de entrenamiento; una
        versión ya existente conserva sus datos.

        Args:
            base: Registro base del que se heredan familia, revisión, dimensiones y prefijos.
            model_version: Identidad inmutable del modelo y revisión cuyos embeddings se
                procesan.
            artifact_path: Directorio local del artefacto entrenado y validado.
            dataset_hash: Huella del conjunto reproducible usado para entrenar.
            training_config: Semilla, épocas, lotes y demás parámetros del entrenamiento
                realizado.
        """
        self.database.run(
            lambda connection: connection.execute(
                """
                INSERT INTO embedding_models (
                    model_version, model_key, hf_repository, hf_revision, dimensions,
                    query_prefix, passage_prefix, artifact_path, dataset_hash,
                    training_config, lifecycle_state
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s::jsonb, 'registered')
                ON CONFLICT (model_version) DO NOTHING
                """,
                (
                    model_version,
                    base.model_key,
                    base.hf_repository,
                    base.hf_revision,
                    base.dimensions,
                    base.query_prefix,
                    base.passage_prefix,
                    artifact_path,
                    dataset_hash,
                    json.dumps(training_config, sort_keys=True),
                ),
            )
        )

    def select_model(self, model_version: str, *, rrf_weight: float = 1.0) -> None:
        """Marca un único candidato selected y registra su peso RRF; los candidatos anteriores
        vuelven a registered sin cambiar el activo.

        Args:
            model_version: Identidad inmutable del modelo y revisión cuyos embeddings se
                procesan.
            rrf_weight: Peso semántico para la fusión RRF; None conserva el registrado cuando
                el método lo admite.
        """
        self.database.run(
            lambda connection: connection.execute(
                """
                UPDATE embedding_models
                SET lifecycle_state = CASE
                    WHEN model_version = %s THEN 'selected'
                    WHEN lifecycle_state = 'selected' THEN 'registered'
                    ELSE lifecycle_state END,
                    rrf_weight = CASE
                        WHEN model_version = %s THEN %s
                        ELSE rrf_weight END
                WHERE model_version = %s OR lifecycle_state = 'selected'
                """,
                (model_version, model_version, rrf_weight, model_version),
            )
        )

    def activate_complete_model(
        self,
        model_version: str,
        *,
        rrf_weight: float | None = None,
    ) -> dict[str, Any]:
        """Publica directamente una versión con cobertura no vacía y completa junto con su índice
        y peso RRF.
        Esta operación de almacén no aplica el protocolo administrativo de benchmark ni modelo
        anterior esperado.

        Args:
            model_version: Identidad inmutable del modelo y revisión cuyos embeddings se
                procesan.
            rrf_weight: Peso semántico para la fusión RRF; None conserva el registrado cuando
                el método lo admite.

        Returns:
            versión activa, índice, conteos y peso final.

        Raises:
            LookupError: Si la versión no está registrada.
            RuntimeError: Si el catálogo está vacío o quedan documentos sin el embedding
                actual.

        See Also:
            app.admin_model_lifecycle.SemanticModelLifecycleStore.activate_model: Activación
                administrativa con evidencia y comprobación del modelo anterior.
        """

        def mutate(connection):
            """Comprueba cobertura, construye HNSW y sustituye el activo dentro de una
            transacción.

            Args:
                connection: Conexión de la transacción del llamador, que confirma o revierte
                    todos sus cambios.

            Returns:
                identidades y cobertura de la versión publicada.

            Raises:
                LookupError: Si falta el modelo.
                RuntimeError: Si la cobertura es vacía o incompleta.
            """
            model = connection.execute(
                """
                SELECT dimensions, rrf_weight
                FROM embedding_models
                WHERE model_version = %s
                """,
                (model_version,),
            ).fetchone()
            if not model:
                raise LookupError("embedding_model_not_registered")
            coverage = model_catalog_coverage(connection, model_version)
            expected = int(coverage["expected"] or 0)
            indexed = int(coverage["indexed"] or 0)
            if expected == 0 or indexed != expected:
                raise RuntimeError(
                    f"model_coverage_incomplete:{indexed}/{expected}"
                )
            index_version = hashlib.sha256(
                f"{model_version}:{coverage['snapshot_hash']}".encode()
            ).hexdigest()[:20]
            ensure_model_hnsw_index(
                connection,
                model_version=model_version,
                dimensions=int(model["dimensions"]),
            )
            connection.execute(
                """
                INSERT INTO semantic_index_state (
                    model_version, index_version, snapshot_hash,
                    expected_documents, indexed_documents, complete,
                    built_at, activated_at
                ) VALUES (%s, %s, %s, %s, %s, TRUE, now(), now())
                ON CONFLICT (model_version) DO UPDATE SET
                    index_version = EXCLUDED.index_version,
                    snapshot_hash = EXCLUDED.snapshot_hash,
                    expected_documents = EXCLUDED.expected_documents,
                    indexed_documents = EXCLUDED.indexed_documents,
                    complete = TRUE,
                    built_at = now(),
                    activated_at = now()
                """,
                (
                    model_version,
                    index_version,
                    coverage["snapshot_hash"],
                    expected,
                    indexed,
                ),
            )
            connection.execute(
                """
                UPDATE embedding_models
                SET active = FALSE,
                    lifecycle_state = CASE
                        WHEN active THEN 'retired'
                        WHEN lifecycle_state = 'selected' THEN 'registered'
                        ELSE lifecycle_state END
                WHERE model_version <> %s
                  AND (active = TRUE OR lifecycle_state = 'selected')
                """,
                (model_version,),
            )
            connection.execute(
                """
                UPDATE embedding_models
                SET active = TRUE,
                    lifecycle_state = 'active',
                    activated_at = now(),
                    rrf_weight = %s
                WHERE model_version = %s
                """,
                (
                    rrf_weight
                    if rrf_weight is not None
                    else float(model["rrf_weight"]),
                    model_version,
                ),
            )
            return {
                "modelVersion": model_version,
                "indexVersion": index_version,
                "expected": expected,
                "indexed": indexed,
                "rrfWeight": (
                    rrf_weight
                    if rrf_weight is not None
                    else float(model["rrf_weight"])
                ),
            }

        return self.database.run(mutate)
