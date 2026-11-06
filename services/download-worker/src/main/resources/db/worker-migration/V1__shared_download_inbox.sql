-- El worker es propietario de esta tabla y de su historial de migraciones separado.
CREATE TABLE download_inbox (
    event_id VARCHAR(64) NOT NULL PRIMARY KEY,
    status VARCHAR(24) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    pending_result LONGTEXT NULL,
    job_id CHAR(36) NULL,
    KEY ix_download_inbox_status_started (status, started_at),
    KEY ix_download_inbox_status_completed (status, completed_at),
    KEY ix_download_inbox_job (job_id)
);
