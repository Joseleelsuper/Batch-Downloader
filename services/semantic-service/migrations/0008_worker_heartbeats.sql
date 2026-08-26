CREATE TABLE semantic_worker_heartbeats (
    role TEXT PRIMARY KEY,
    instance_id UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_success_at TIMESTAMPTZ,
    last_error_at TIMESTAMPTZ,
    last_error_code VARCHAR(128),
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT semantic_worker_heartbeats_failures_nonnegative
        CHECK (consecutive_failures >= 0)
);

CREATE INDEX semantic_worker_heartbeats_heartbeat_idx
    ON semantic_worker_heartbeats(heartbeat_at);
