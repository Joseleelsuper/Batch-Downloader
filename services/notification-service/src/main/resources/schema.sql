CREATE TABLE IF NOT EXISTS notification_inbox (
    event_id VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL,
    received_at_epoch_ms BIGINT NOT NULL,
    lease_until_epoch_ms BIGINT,
    processed_at_epoch_ms BIGINT,
    last_error VARCHAR(1000),
    CONSTRAINT chk_notification_inbox_status
        CHECK (status IN ('PROCESSING', 'PROCESSED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_notification_inbox_status_lease
    ON notification_inbox (status, lease_until_epoch_ms);
