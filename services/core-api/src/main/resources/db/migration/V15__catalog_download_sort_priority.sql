-- download_count y su índice pertenecen a Core. Sustituye la agrupación por
-- catalog_status por la prioridad binaria que mantiene review al final sin filesort.
ALTER TABLE software_apps
    DROP INDEX ix_software_apps_catalog_downloads,
    ADD INDEX ix_software_apps_catalog_downloads (
        app_status,
        catalog_review_priority,
        download_count DESC,
        normalized_name,
        id
    );
