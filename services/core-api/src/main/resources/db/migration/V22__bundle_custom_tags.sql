CREATE TABLE bundle_custom_tags (
    normalized_tag VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    tag VARCHAR(80) NOT NULL,
    created_by CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (normalized_tag),
    KEY ix_bundle_custom_tags_created_by (created_by),
    CONSTRAINT fk_bundle_custom_tags_creator
        FOREIGN KEY (created_by) REFERENCES core_users (id) ON DELETE SET NULL
) DEFAULT CHARSET=utf8mb4;
