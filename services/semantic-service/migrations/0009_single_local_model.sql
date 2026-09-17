-- La simplificacion elimina el historial administrativo y deja una unica ranura local.
-- Es deliberadamente irreversible: conservar una copia de PostgreSQL antes de aplicarla.

DROP TABLE IF EXISTS benchmark_runs;
DROP TABLE IF EXISTS semantic_operations;

DROP INDEX IF EXISTS ux_embedding_models_artifact;
ALTER TABLE embedding_models DROP COLUMN IF EXISTS artifact_id;

DROP TABLE IF EXISTS semantic_model_artifacts;
DROP TABLE IF EXISTS semantic_remote_metadata_archive;

DROP INDEX IF EXISTS ux_embedding_models_artifact;
DROP INDEX IF EXISTS ix_embeddings_77e7db8d29ffe398_hnsw;
DROP INDEX IF EXISTS ix_embeddings_76688c11ad80efe9_hnsw;
DROP INDEX IF EXISTS ix_embeddings_c9aff84bb59b7fb2_hnsw;

DO $$
DECLARE
    index_name TEXT;
BEGIN
    FOR index_name IN
        SELECT indexname
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'software_embeddings'
          AND indexdef ILIKE '%USING hnsw%'
    LOOP
        EXECUTE format('DROP INDEX IF EXISTS %I', index_name);
    END LOOP;
END;
$$;

DELETE FROM semantic_worker_heartbeats WHERE role = 'model-worker';
DELETE FROM embedding_models;

ALTER TABLE embedding_models
    DROP COLUMN IF EXISTS model_key,
    DROP COLUMN IF EXISTS hf_repository,
    DROP COLUMN IF EXISTS hf_revision,
    DROP COLUMN IF EXISTS artifact_path,
    DROP COLUMN IF EXISTS artifact_id,
    DROP COLUMN IF EXISTS dataset_hash,
    DROP COLUMN IF EXISTS training_config,
    DROP COLUMN IF EXISTS lifecycle_state,
    DROP COLUMN IF EXISTS rrf_weight,
    DROP COLUMN IF EXISTS deployment_state;

ALTER TABLE embedding_models
    DROP CONSTRAINT IF EXISTS embedding_models_dimensions_check,
    ADD CONSTRAINT embedding_models_dimensions_supported
        CHECK (dimensions BETWEEN 1 AND 2000);

CREATE UNIQUE INDEX IF NOT EXISTS ux_embedding_models_single_row
    ON embedding_models ((TRUE));
