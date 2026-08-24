-- Reintenta el backfill por si una cuenta fue creada después de V5 y antes del corte.
UPDATE bundles bundle
JOIN core_users user_account
  ON user_account.normalized_username = LOWER(TRIM(bundle.owner_username))
SET bundle.owner_id = user_account.id
WHERE bundle.owner_id IS NULL
  AND bundle.owner_username IS NOT NULL
  AND TRIM(bundle.owner_username) <> '';

-- El CHECK actúa como preflight: el corte se detiene antes de invalidar sesiones o
-- retirar columnas si un bundle privado/de usuario no tiene un UUID inequívoco.
CREATE TEMPORARY TABLE v12_bundle_owner_preflight (
    unresolved_count BIGINT NOT NULL,
    CONSTRAINT chk_v12_unresolved_bundle_owner CHECK (unresolved_count = 0)
);

INSERT INTO v12_bundle_owner_preflight (unresolved_count)
SELECT COUNT(*)
FROM bundles
WHERE owner_id IS NULL
  AND (LOWER(type) = 'user' OR LOWER(visibility) = 'private');

DROP TEMPORARY TABLE v12_bundle_owner_preflight;

-- Los principales serializados anteriores al contrato UUID dejan de ser válidos.
-- Los atributos se eliminan por la cascada de SPRING_SESSION_ATTRIBUTES.
DELETE FROM SPRING_SESSION;

ALTER TABLE bundles
    DROP FOREIGN KEY fk_bundles_owner;

ALTER TABLE bundles
    DROP COLUMN owner_username,
    ADD CONSTRAINT chk_bundles_owner_required CHECK (
        owner_id IS NOT NULL
        OR (LOWER(type) <> 'user' AND LOWER(visibility) <> 'private')
    );

ALTER TABLE bundles
    ADD CONSTRAINT fk_bundles_owner
        FOREIGN KEY (owner_id) REFERENCES core_users (id) ON DELETE CASCADE;
