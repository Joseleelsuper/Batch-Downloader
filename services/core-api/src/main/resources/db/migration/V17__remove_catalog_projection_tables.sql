-- Las proyecciones históricas no tienen consumidores. Se aborta si contienen datos para no
-- ocultar una instalación externa que todavía dependa de ellas.
DROP PROCEDURE IF EXISTS assert_catalog_projection_tables_empty;
DELIMITER $$
CREATE PROCEDURE assert_catalog_projection_tables_empty()
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
END$$
DELIMITER ;

CALL assert_catalog_projection_tables_empty();
DROP PROCEDURE assert_catalog_projection_tables_empty;

DROP TABLE IF EXISTS catalog_source_projections;
DROP TABLE IF EXISTS catalog_app_projections;
