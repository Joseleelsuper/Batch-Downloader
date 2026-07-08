CREATE TABLE IF NOT EXISTS bundles (
    id BINARY(16) NOT NULL,
    slug VARCHAR(180) NOT NULL,
    name VARCHAR(180) NOT NULL,
    description TEXT NULL,
    type VARCHAR(32) NOT NULL,
    visibility VARCHAR(32) NOT NULL,
    owner_username VARCHAR(180) NULL,
    star_count INT NOT NULL DEFAULT 0,
    app_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_bundles_slug (slug),
    KEY ix_bundles_type_visibility (type, visibility),
    KEY ix_bundles_updated_at (updated_at)
);

CREATE TABLE IF NOT EXISTS bundle_items (
    id BINARY(16) NOT NULL,
    bundle_id BINARY(16) NOT NULL,
    software_app_id BINARY(16) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_bundle_item (bundle_id, software_app_id),
    KEY ix_bundle_items_bundle_sort (bundle_id, sort_order),
    CONSTRAINT fk_bundle_items_bundle FOREIGN KEY (bundle_id) REFERENCES bundles (id) ON DELETE CASCADE,
    CONSTRAINT fk_bundle_items_app FOREIGN KEY (software_app_id) REFERENCES software_apps (id)
);

CREATE TABLE IF NOT EXISTS bundle_tags (
    id BINARY(16) NOT NULL,
    bundle_id BINARY(16) NOT NULL,
    tag VARCHAR(120) NOT NULL,
    normalized_tag VARCHAR(120) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_bundle_tag (bundle_id, normalized_tag),
    KEY ix_bundle_tags_normalized_tag (normalized_tag),
    CONSTRAINT fk_bundle_tags_bundle FOREIGN KEY (bundle_id) REFERENCES bundles (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS bundle_stars (
    id BINARY(16) NOT NULL,
    bundle_id BINARY(16) NOT NULL,
    user_key VARCHAR(180) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_bundle_star (bundle_id, user_key),
    CONSTRAINT fk_bundle_stars_bundle FOREIGN KEY (bundle_id) REFERENCES bundles (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS software_requests (
    id BINARY(16) NOT NULL,
    requested_name VARCHAR(180) NOT NULL,
    official_url VARCHAR(2048) NOT NULL,
    description TEXT NULL,
    generated_description TEXT NULL,
    status VARCHAR(32) NOT NULL,
    requester_email VARCHAR(320) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY ix_software_requests_status (status),
    KEY ix_software_requests_url (official_url(191))
);

CREATE TABLE IF NOT EXISTS admin_audit_logs (
    id BINARY(16) NOT NULL,
    actor VARCHAR(180) NOT NULL,
    action VARCHAR(80) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id VARCHAR(180) NULL,
    safe_metadata JSON NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY ix_admin_audit_logs_created_at (created_at),
    KEY ix_admin_audit_logs_action (action)
);
