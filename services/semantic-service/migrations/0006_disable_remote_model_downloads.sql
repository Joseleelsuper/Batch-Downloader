UPDATE semantic_operations
SET status = 'failed',
    phase = 'failed',
    error_code = 'semantic_remote_model_download_disabled',
    safe_message = 'Las descargas remotas de modelos están desactivadas.',
    lease_owner = NULL,
    lease_until = NULL,
    finished_at = COALESCE(finished_at, now()),
    updated_at = now()
WHERE operation_kind = 'download'
  AND status IN ('queued', 'running', 'cancel_requested');

UPDATE semantic_model_artifacts
SET artifact_state = 'failed',
    validation_message = 'semantic_remote_model_download_disabled'
WHERE artifact_state IN ('downloading', 'validating')
  AND local_path IS NULL;
