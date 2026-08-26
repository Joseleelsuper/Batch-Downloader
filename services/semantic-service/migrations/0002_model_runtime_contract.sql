ALTER TABLE embedding_models
    ADD COLUMN IF NOT EXISTS rrf_weight DOUBLE PRECISION NOT NULL DEFAULT 1.0
        CHECK (rrf_weight > 0 AND rrf_weight <= 10);

CREATE OR REPLACE FUNCTION enforce_registered_embedding_dimensions()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_dimensions INTEGER;
BEGIN
    SELECT dimensions
      INTO expected_dimensions
      FROM embedding_models
     WHERE model_version = NEW.model_version;

    IF expected_dimensions IS NULL THEN
        RAISE EXCEPTION 'embedding model % is not registered', NEW.model_version;
    END IF;
    IF vector_dims(NEW.embedding) <> expected_dimensions THEN
        RAISE EXCEPTION
            'embedding dimension mismatch for %: expected %, received %',
            NEW.model_version,
            expected_dimensions,
            vector_dims(NEW.embedding);
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_software_embeddings_dimensions ON software_embeddings;
CREATE TRIGGER trg_software_embeddings_dimensions
BEFORE INSERT OR UPDATE OF embedding, model_version
ON software_embeddings
FOR EACH ROW
EXECUTE FUNCTION enforce_registered_embedding_dimensions();
