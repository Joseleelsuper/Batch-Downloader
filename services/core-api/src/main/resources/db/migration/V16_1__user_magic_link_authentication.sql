-- Elimina permisos y solicitudes de credenciales que ya no pertenecen al flujo de usuario.
DELETE FROM core_outbox_events
WHERE published_at IS NULL
  AND event_type = 'notification.email.requested'
  AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.template'))
      IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET');

DELETE FROM identity_tokens;
ALTER TABLE identity_tokens
    DROP INDEX ix_identity_tokens_user_type,
    DROP COLUMN token_type,
    ADD INDEX ix_identity_tokens_user (user_id);

-- Conserva las sesiones administrativas y fuerza un nuevo enlace para cada usuario.
DELETE session_row
FROM SPRING_SESSION session_row
JOIN core_users user_row ON session_row.PRINCIPAL_NAME = user_row.id
WHERE user_row.role = 'USER';

ALTER TABLE core_users MODIFY password_hash VARCHAR(100) NULL;
UPDATE core_users
SET password_hash = NULL,
    updated_at = NOW(6),
    version = version + 1
WHERE role = 'USER';

ALTER TABLE core_users
    ADD CONSTRAINT chk_core_users_password_by_role
    CHECK ((role = 'ADMIN' AND password_hash IS NOT NULL)
        OR (role = 'USER' AND password_hash IS NULL));
