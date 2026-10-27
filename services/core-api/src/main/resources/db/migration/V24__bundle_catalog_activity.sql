ALTER TABLE bundles ADD COLUMN download_count BIGINT UNSIGNED NOT NULL DEFAULT 0;
ALTER TABLE bundle_stars ADD KEY ix_bundle_stars_user (user_key);
ALTER TABLE download_job_receipts
    ADD COLUMN bundle_id BINARY(16) NULL,
    ADD CONSTRAINT fk_download_receipts_bundle FOREIGN KEY (bundle_id) REFERENCES bundles(id) ON DELETE SET NULL;

-- Señal durable: los votos y las descargas no cambian el texto semántico.
CREATE TABLE bundle_semantic_source_state (
    id TINYINT NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 1
);
INSERT INTO bundle_semantic_source_state(id) VALUES (1);

CREATE TRIGGER trg_bundle_semantic_ai AFTER INSERT ON bundles FOR EACH ROW
UPDATE bundle_semantic_source_state SET revision = revision + 1 WHERE id = 1;
CREATE TRIGGER trg_bundle_semantic_ad AFTER DELETE ON bundles FOR EACH ROW
UPDATE bundle_semantic_source_state SET revision = revision + 1 WHERE id = 1;
CREATE TRIGGER trg_bundle_semantic_au AFTER UPDATE ON bundles FOR EACH ROW
UPDATE bundle_semantic_source_state SET revision = revision + 1 WHERE id = 1
AND (NOT (OLD.name <=> NEW.name) OR NOT (OLD.description <=> NEW.description)
    OR NOT (OLD.visibility <=> NEW.visibility) OR NOT (OLD.type <=> NEW.type));
CREATE TRIGGER trg_bundle_tag_semantic_ai AFTER INSERT ON bundle_tags FOR EACH ROW
UPDATE bundle_semantic_source_state SET revision = revision + 1 WHERE id = 1;
CREATE TRIGGER trg_bundle_tag_semantic_ad AFTER DELETE ON bundle_tags FOR EACH ROW
UPDATE bundle_semantic_source_state SET revision = revision + 1 WHERE id = 1;
