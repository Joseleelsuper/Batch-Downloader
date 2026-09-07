CREATE TABLE download_job_linux_context (
    job_id CHAR(36) NOT NULL PRIMARY KEY,
    linux_target VARCHAR(16) NOT NULL,
    architecture VARCHAR(16) NOT NULL,
    dependencies TEXT NOT NULL,
    CONSTRAINT fk_linux_context_job FOREIGN KEY (job_id) REFERENCES download_jobs(id) ON DELETE CASCADE
);
