-- Las tablas retiradas deben estar vacías para evitar perder datos pendientes o
-- ocultar una instalación externa que todavía dependa de las proyecciones.
DROP PROCEDURE IF EXISTS assert_retired_tables_empty;
DELIMITER $$
CREATE PROCEDURE assert_retired_tables_empty()
BEGIN
    SET @catalog_projection_has_rows = 0;
    IF EXISTS (
           SELECT 1
           FROM information_schema.tables
           WHERE table_schema = DATABASE()
             AND table_name = 'catalog_source_projections'
             AND table_type = 'BASE TABLE'
       )
    THEN
        SET @catalog_projection_check =
            'SELECT EXISTS (SELECT 1 FROM catalog_source_projections LIMIT 1) INTO @catalog_projection_has_rows';
        PREPARE catalog_projection_statement FROM @catalog_projection_check;
        EXECUTE catalog_projection_statement;
        DEALLOCATE PREPARE catalog_projection_statement;
    END IF;
    IF @catalog_projection_has_rows = 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V17 aborted: catalog projection tables are not empty';
    END IF;
    SET @catalog_projection_has_rows = 0;
    IF EXISTS (
           SELECT 1
           FROM information_schema.tables
           WHERE table_schema = DATABASE()
             AND table_name = 'catalog_app_projections'
             AND table_type = 'BASE TABLE'
       )
    THEN
        SET @catalog_projection_check =
            'SELECT EXISTS (SELECT 1 FROM catalog_app_projections LIMIT 1) INTO @catalog_projection_has_rows';
        PREPARE catalog_projection_statement FROM @catalog_projection_check;
        EXECUTE catalog_projection_statement;
        DEALLOCATE PREPARE catalog_projection_statement;
    END IF;
    IF @catalog_projection_has_rows = 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V17 aborted: catalog projection tables are not empty';
    END IF;
    SET @software_requests_has_rows = 0;
    IF EXISTS (
           SELECT 1
           FROM information_schema.tables
           WHERE table_schema = DATABASE()
             AND table_name = 'software_requests'
             AND table_type = 'BASE TABLE'
       )
    THEN
        SET @software_requests_check =
            'SELECT EXISTS (SELECT 1 FROM software_requests LIMIT 1) INTO @software_requests_has_rows';
        PREPARE software_requests_statement FROM @software_requests_check;
        EXECUTE software_requests_statement;
        DEALLOCATE PREPARE software_requests_statement;
    END IF;
    IF @software_requests_has_rows = 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V17 aborted: software_requests is not empty';
    END IF;
END$$
DELIMITER ;

CALL assert_retired_tables_empty();
DROP PROCEDURE assert_retired_tables_empty;

DROP TABLE IF EXISTS catalog_source_projections;
DROP TABLE IF EXISTS catalog_app_projections;
