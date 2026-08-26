ALTER TABLE download_jobs
    ADD COLUMN artifact_size_bytes BIGINT NULL AFTER object_key,
    ADD COLUMN artifact_sha256 CHAR(64) NULL AFTER artifact_size_bytes,
    ADD COLUMN wait_reason VARCHAR(80) NULL AFTER failure_code,
    ADD COLUMN retry_at DATETIME(6) NULL AFTER wait_reason;
