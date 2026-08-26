ALTER TABLE core_users
    MODIFY password_hash VARCHAR(100) NULL;

ALTER TABLE identity_tokens
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER created_at;

CREATE TABLE oauth_identities (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    provider_email VARCHAR(320) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    last_login_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_oauth_identity_subject (provider, provider_subject),
    UNIQUE KEY uq_oauth_identity_user_provider (user_id, provider),
    KEY ix_oauth_identity_provider_email (provider, provider_email),
    CONSTRAINT fk_oauth_identity_user
        FOREIGN KEY (user_id) REFERENCES core_users (id) ON DELETE CASCADE
);
