CREATE TABLE IF NOT EXISTS semantic_remote_metadata_archive (
    source_kind TEXT NOT NULL CHECK (source_kind IN ('artifact', 'operation')),
    source_id UUID NOT NULL,
    metadata JSONB NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source_kind, source_id)
);

INSERT INTO semantic_remote_metadata_archive (source_kind, source_id, metadata)
SELECT
    'artifact',
    id,
    jsonb_strip_nulls(jsonb_build_object(
        'repository', hf_repository,
        'requestedRevision', requested_revision,
        'resolvedRevision', resolved_revision,
        'metadata', metadata
    ))
FROM semantic_model_artifacts
ON CONFLICT (source_kind, source_id) DO NOTHING;

INSERT INTO semantic_remote_metadata_archive (source_kind, source_id, metadata)
SELECT
    'operation',
    id,
    jsonb_strip_nulls(jsonb_build_object(
        'repository', repository,
        'resolvedRevision', resolved_revision,
        'request', request_payload,
        'result', result_payload,
        'status', status,
        'errorCode', error_code
    ))
FROM semantic_operations
WHERE operation_kind = 'download'
ON CONFLICT (source_kind, source_id) DO NOTHING;

DELETE FROM semantic_operations
WHERE operation_kind = 'download';

ALTER TABLE semantic_operations
    DROP CONSTRAINT IF EXISTS semantic_operations_operation_kind_check,
    ADD CONSTRAINT semantic_operations_operation_kind_check
        CHECK (operation_kind IN ('benchmark', 'prepare', 'activate', 'delete'));

UPDATE semantic_model_artifacts
SET artifact_state = 'failed',
    validation_message = COALESCE(
        validation_message,
        'semantic_remote_model_download_disabled'
    )
WHERE artifact_state IN ('downloading', 'validating');

ALTER TABLE semantic_model_artifacts
    ALTER COLUMN artifact_state DROP DEFAULT,
    DROP CONSTRAINT IF EXISTS semantic_model_artifacts_artifact_state_check,
    ADD CONSTRAINT semantic_model_artifacts_artifact_state_check
        CHECK (artifact_state IN ('ready', 'incompatible', 'failed', 'deleted'));

UPDATE benchmark_runs
SET scope = 'historical'
WHERE scope = 'legacy';

ALTER TABLE benchmark_runs
    ALTER COLUMN scope SET DEFAULT 'historical',
    DROP CONSTRAINT IF EXISTS benchmark_runs_scope_check,
    ADD CONSTRAINT benchmark_runs_scope_check
        CHECK (scope IN ('historical', 'smoke', 'full'));
