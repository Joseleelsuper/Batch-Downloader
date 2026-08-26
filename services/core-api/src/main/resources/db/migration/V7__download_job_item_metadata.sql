ALTER TABLE download_job_items
    ADD COLUMN app_name VARCHAR(180) NULL AFTER source_ref,
    ADD COLUMN official_url VARCHAR(2048) NULL AFTER app_name;
