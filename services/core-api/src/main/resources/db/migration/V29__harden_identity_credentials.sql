ALTER TABLE core_users
    ADD COLUMN credentials_version BIGINT NOT NULL DEFAULT 0,
    MODIFY COLUMN normalized_email VARCHAR(320) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;

UPDATE core_users SET credentials_version = 1 WHERE role = 'ADMIN';

-- Mantiene la versión de credenciales si una reversión restaura Core V28.
CREATE TRIGGER core_users_credentials_on_insert
BEFORE INSERT ON core_users FOR EACH ROW
SET NEW.credentials_version = IF(NEW.password_hash IS NOT NULL, GREATEST(NEW.credentials_version, 1), 0);

CREATE TRIGGER core_users_credentials_on_update
BEFORE UPDATE ON core_users FOR EACH ROW
SET NEW.credentials_version = CASE
    WHEN NEW.password_hash IS NOT NULL
            AND NOT (CAST(NEW.password_hash AS BINARY) <=> CAST(OLD.password_hash AS BINARY))
        THEN OLD.credentials_version + 1
    ELSE OLD.credentials_version
END;

DELETE session_row
FROM SPRING_SESSION session_row
JOIN core_users user_row ON user_row.id = session_row.PRINCIPAL_NAME
WHERE user_row.role = 'ADMIN';

ALTER TABLE pending_magic_link_requests
    MODIFY COLUMN normalized_email VARCHAR(320) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;

CREATE TABLE pending_magic_link_guard (id TINYINT NOT NULL PRIMARY KEY);
INSERT INTO pending_magic_link_guard VALUES (1);

DELETE FROM core_outbox_events
WHERE aggregate_type = 'pending_magic_link'
  AND event_type = 'notification.email.requested'
  AND published_at IS NULL;

TRUNCATE TABLE pending_magic_link_requests;
