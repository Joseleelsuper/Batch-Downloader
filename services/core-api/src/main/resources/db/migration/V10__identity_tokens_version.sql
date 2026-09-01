ALTER TABLE identity_tokens
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER created_at;
