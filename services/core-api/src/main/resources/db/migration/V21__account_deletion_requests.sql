CREATE TABLE account_deletion_requests (
    user_id CHAR(36) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    KEY ix_account_deletion_requested_at (requested_at),
    CONSTRAINT fk_account_deletion_user
        FOREIGN KEY (user_id) REFERENCES core_users (id) ON DELETE CASCADE
);
