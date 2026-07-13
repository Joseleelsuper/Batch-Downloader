CREATE TABLE IF NOT EXISTS download_inbox (
    event_id VARCHAR(64) PRIMARY KEY,
    status VARCHAR(24) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NULL
);

CREATE INDEX IF NOT EXISTS ix_download_inbox_status_started
    ON download_inbox (status, started_at);
