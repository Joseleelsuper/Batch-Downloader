CREATE TABLE IF NOT EXISTS core_users (
    id CHAR(36) NOT NULL,
    username VARCHAR(80) NOT NULL,
    normalized_username VARCHAR(80) NOT NULL,
    email VARCHAR(320) NOT NULL,
    normalized_email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    role VARCHAR(16) NOT NULL,
    notify_on_job_completion BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_core_users_username (normalized_username),
    UNIQUE KEY uq_core_users_email (normalized_email)
);

CREATE TABLE IF NOT EXISTS identity_tokens (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    token_type VARCHAR(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_identity_tokens_hash (token_hash),
    KEY ix_identity_tokens_user_type (user_id, token_type),
    CONSTRAINT fk_identity_tokens_user FOREIGN KEY (user_id) REFERENCES core_users (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS catalog_app_projections (
    app_id CHAR(36) NOT NULL,
    slug VARCHAR(180) NOT NULL,
    name VARCHAR(180) NOT NULL,
    publisher VARCHAR(180) NULL,
    description TEXT NULL,
    downloadable BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (app_id),
    UNIQUE KEY uq_catalog_app_projection_slug (slug),
    KEY ix_catalog_app_projection_name (name),
    KEY ix_catalog_app_projection_updated (updated_at)
);

CREATE TABLE IF NOT EXISTS catalog_source_projections (
    source_ref CHAR(36) NOT NULL,
    app_id CHAR(36) NOT NULL,
    trust_status VARCHAR(24) NOT NULL,
    artifact_format VARCHAR(24) NULL,
    platform VARCHAR(24) NULL,
    architecture VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    size_bytes BIGINT NULL,
    sha256 CHAR(64) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (source_ref),
    KEY ix_catalog_source_app_status (app_id, trust_status),
    CONSTRAINT fk_catalog_source_app FOREIGN KEY (app_id) REFERENCES catalog_app_projections (app_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS download_jobs (
    id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    status VARCHAR(24) NOT NULL,
    progress SMALLINT NOT NULL DEFAULT 0,
    object_key VARCHAR(512) NULL,
    failure_code VARCHAR(80) NULL,
    cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE,
    notify_when_ready BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_download_jobs_owner_created (owner_id, created_at),
    KEY ix_download_jobs_status_expires (status, expires_at),
    CONSTRAINT fk_download_jobs_owner FOREIGN KEY (owner_id) REFERENCES core_users (id)
);

CREATE TABLE IF NOT EXISTS download_job_items (
    id CHAR(36) NOT NULL,
    job_id CHAR(36) NOT NULL,
    app_id CHAR(36) NOT NULL,
    source_ref CHAR(36) NOT NULL,
    status VARCHAR(24) NOT NULL,
    bytes_downloaded BIGINT NOT NULL DEFAULT 0,
    sha256 CHAR(64) NULL,
    error_code VARCHAR(80) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_download_job_item_app (job_id, app_id),
    KEY ix_download_job_items_job_status (job_id, status),
    CONSTRAINT fk_download_job_items_job FOREIGN KEY (job_id) REFERENCES download_jobs (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS core_outbox_events (
    id CHAR(36) NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    routing_key VARCHAR(160) NOT NULL,
    payload JSON NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    last_error VARCHAR(500) NULL,
    PRIMARY KEY (id),
    KEY ix_core_outbox_pending (published_at, next_attempt_at, occurred_at)
);

CREATE TABLE IF NOT EXISTS core_inbox_messages (
    message_id CHAR(36) NOT NULL,
    message_type VARCHAR(120) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL,
    last_error VARCHAR(500) NULL,
    PRIMARY KEY (message_id)
);

CREATE TABLE IF NOT EXISTS SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100) NULL,
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID),
    CONSTRAINT SPRING_SESSION_IX1 UNIQUE (SESSION_ID),
    INDEX SPRING_SESSION_IX2 (EXPIRY_TIME),
    INDEX SPRING_SESSION_IX3 (PRINCIPAL_NAME)
);

CREATE TABLE IF NOT EXISTS SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BLOB NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);
