-- A download belongs either to a signed-in account or to the browser that created it.
-- The browser token itself never reaches this table: only its HMAC-SHA-256 digest is stored.
ALTER TABLE download_jobs
    DROP FOREIGN KEY fk_download_jobs_owner;

ALTER TABLE download_jobs
    MODIFY owner_id CHAR(36) NULL,
    ADD COLUMN anonymous_owner_hash CHAR(64) NULL AFTER owner_id,
    ADD COLUMN anonymous_ip_hash CHAR(64) NULL AFTER anonymous_owner_hash,
    ADD COLUMN requested_count INT NULL AFTER notify_when_ready,
    ADD COLUMN accepted_count INT NULL AFTER requested_count,
    ADD COLUMN omitted_count INT NULL AFTER accepted_count,
    ADD CONSTRAINT chk_download_jobs_single_owner CHECK (
        (owner_id IS NOT NULL AND anonymous_owner_hash IS NULL)
        OR (owner_id IS NULL AND anonymous_owner_hash IS NOT NULL)
    );

UPDATE download_jobs job
SET requested_count = (SELECT COUNT(*) FROM download_job_items item WHERE item.job_id = job.id),
    accepted_count = (SELECT COUNT(*) FROM download_job_items item WHERE item.job_id = job.id),
    omitted_count = 0
WHERE requested_count IS NULL;

ALTER TABLE download_jobs
    MODIFY requested_count INT NOT NULL,
    MODIFY accepted_count INT NOT NULL,
    MODIFY omitted_count INT NOT NULL,
    ADD KEY ix_download_jobs_anonymous_owner_created (anonymous_owner_hash, created_at),
    ADD KEY ix_download_jobs_anonymous_ip_created (anonymous_ip_hash, created_at),
    ADD CONSTRAINT fk_download_jobs_owner
        FOREIGN KEY (owner_id) REFERENCES core_users (id);

-- Existing bundles retain their legacy username while new writes use a real Core user id.
ALTER TABLE bundles
    ADD COLUMN owner_id CHAR(36) NULL AFTER owner_username,
    ADD KEY ix_bundles_owner_visibility (owner_id, visibility),
    ADD CONSTRAINT fk_bundles_owner
        FOREIGN KEY (owner_id) REFERENCES core_users (id) ON DELETE SET NULL;

UPDATE bundles bundle
JOIN core_users user_account ON user_account.normalized_username = LOWER(TRIM(bundle.owner_username))
SET bundle.owner_id = user_account.id
WHERE bundle.owner_id IS NULL
  AND bundle.owner_username IS NOT NULL
  AND TRIM(bundle.owner_username) <> '';
