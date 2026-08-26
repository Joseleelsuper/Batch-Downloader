ALTER TABLE core_outbox_events
    ADD COLUMN claim_token CHAR(36) NULL,
    ADD COLUMN claimed_at TIMESTAMP(6) NULL;

CREATE INDEX ix_core_outbox_claimable
    ON core_outbox_events (published_at, next_attempt_at, claimed_at, occurred_at);
