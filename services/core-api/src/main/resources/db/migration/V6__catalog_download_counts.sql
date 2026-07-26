-- Keep an all-time count of successfully completed downloads on the catalog
-- authority so ordering does not need to aggregate the job history per request.
ALTER TABLE software_apps
    ADD COLUMN download_count BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER catalog_review_source_count,
    ADD KEY ix_software_apps_catalog_downloads (
        app_status,
        catalog_status,
        download_count,
        normalized_name,
        id
    );

UPDATE software_apps app
LEFT JOIN (
    SELECT UUID_TO_BIN(item.app_id) AS software_app_id, COUNT(*) AS completed_count
    FROM download_job_items item
    WHERE item.status = 'COMPLETED'
    GROUP BY item.app_id
) history ON history.software_app_id = app.id
SET app.download_count = COALESCE(history.completed_count, 0);

CREATE TRIGGER trg_download_job_items_count_ai
AFTER INSERT ON download_job_items
FOR EACH ROW
UPDATE software_apps
SET download_count = download_count + 1
WHERE id = UUID_TO_BIN(NEW.app_id)
  AND NEW.status = 'COMPLETED';

CREATE TRIGGER trg_download_job_items_count_au
AFTER UPDATE ON download_job_items
FOR EACH ROW
UPDATE software_apps
SET download_count = download_count + 1
WHERE id = UUID_TO_BIN(NEW.app_id)
  AND OLD.status <> 'COMPLETED'
  AND NEW.status = 'COMPLETED';
