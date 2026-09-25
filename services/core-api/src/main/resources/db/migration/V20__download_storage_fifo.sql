CREATE TABLE download_job_storage (
    queue_sequence BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_id CHAR(36) NOT NULL UNIQUE,
    estimated_bytes BIGINT NULL,
    required_bytes BIGINT NOT NULL DEFAULT 0,
    reserved_bytes BIGINT NOT NULL DEFAULT 0,
    phase VARCHAR(24) NOT NULL DEFAULT 'ESTIMATING',
    attempt_id CHAR(36) NULL,
    last_activity_at DATETIME(6) NOT NULL,
    last_progress_at DATETIME(6) NOT NULL,
    last_worker_at DATETIME(6) NOT NULL,
    delivery_status VARCHAR(24) NOT NULL DEFAULT 'WAITING',
    delivery_bytes BIGINT NOT NULL DEFAULT 0,
    active_transfers INT NOT NULL DEFAULT 0,
    retry_at DATETIME(6) NOT NULL,
    KEY ix_download_storage_fifo (phase, queue_sequence),
    CONSTRAINT fk_download_storage_job FOREIGN KEY (job_id) REFERENCES download_jobs(id) ON DELETE CASCADE,
    CONSTRAINT ck_download_storage_bytes CHECK (reserved_bytes >= 0 AND required_bytes >= 0)
);

-- Se concilia el almacenamiento existente antes de habilitar nuevas admisiones.
INSERT INTO download_job_storage
    (job_id, phase, last_activity_at, last_progress_at, last_worker_at, retry_at)
SELECT id, 'RECONCILING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM download_jobs ORDER BY created_at, id;

CREATE TABLE download_job_transfers (
    transfer_id CHAR(36) NOT NULL PRIMARY KEY,
    job_id CHAR(36) NOT NULL,
    CONSTRAINT fk_download_transfer_job FOREIGN KEY(job_id) REFERENCES download_jobs(id) ON DELETE CASCADE
);

-- Recibo mínimo sin archivos ni reserva: mantiene la cuota horaria y los reintentos
-- de confirmación después de borrar el trabajo. Se purga una hora tras su limpieza.
CREATE TABLE download_job_receipts (
    job_id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id CHAR(36) NULL,
    anonymous_owner_hash VARCHAR(64) NULL,
    anonymous_ip_hash VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    saved_bytes BIGINT NULL,
    cleaned_at DATETIME(6) NULL,
    KEY ix_download_receipts_browser (anonymous_owner_hash, created_at),
    KEY ix_download_receipts_ip (anonymous_ip_hash, created_at)
);
INSERT INTO download_job_receipts(job_id,owner_id,anonymous_owner_hash,anonymous_ip_hash,created_at)
SELECT id,owner_id,anonymous_owner_hash,anonymous_ip_hash,created_at FROM download_jobs;
