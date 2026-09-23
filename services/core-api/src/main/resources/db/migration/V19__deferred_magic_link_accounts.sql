CREATE TABLE IF NOT EXISTS pending_magic_link_requests (
    id CHAR(36) NOT NULL,
    email VARCHAR(320) NOT NULL,
    normalized_email VARCHAR(320) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    locale VARCHAR(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_pending_magic_link_email (normalized_email),
    UNIQUE KEY uq_pending_magic_link_hash (token_hash),
    KEY ix_pending_magic_link_expiry (consumed_at, expires_at)
);
