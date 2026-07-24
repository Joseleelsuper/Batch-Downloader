ALTER TABLE embedding_models
    DROP CONSTRAINT IF EXISTS embedding_models_model_key_hf_revision_dataset_hash_key;

COMMENT ON COLUMN embedding_models.model_version IS
    'Immutable identity including base revision, training kind and dataset hash.';
