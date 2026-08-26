CREATE TABLE user_download_history (
    id BINARY(16) NOT NULL,
    user_id CHAR(36) NOT NULL,
    job_id CHAR(36) NOT NULL,
    app_id BINARY(16) NOT NULL,
    app_name VARCHAR(180) NOT NULL,
    downloaded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_user_download_history_operation (user_id, job_id, app_id),
    KEY ix_user_download_history_user_date (user_id, downloaded_at),
    CONSTRAINT fk_user_download_history_user
        FOREIGN KEY (user_id) REFERENCES core_users (id) ON DELETE CASCADE
);
