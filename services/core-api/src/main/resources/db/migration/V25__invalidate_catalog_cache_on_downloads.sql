CREATE TRIGGER trg_software_apps_download_count_cache_au
AFTER UPDATE ON software_apps
FOR EACH ROW
UPDATE catalog_counters
SET version = version + 1, updated_at = UTC_TIMESTAMP(6)
WHERE id = 1 AND OLD.download_count <> NEW.download_count;
