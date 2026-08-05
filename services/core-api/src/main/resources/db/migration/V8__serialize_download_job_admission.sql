CREATE TABLE IF NOT EXISTS download_job_capacity_guard (
    id TINYINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_download_job_capacity_guard_singleton CHECK (id = 1)
);

INSERT IGNORE INTO download_job_capacity_guard (id) VALUES (1);
