CREATE TABLE identity_auth_attempt_guard (id TINYINT NOT NULL PRIMARY KEY);
INSERT INTO identity_auth_attempt_guard VALUES (1);

CREATE TABLE identity_auth_attempts (
    scope VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (scope, fingerprint),
    KEY ix_auth_attempt_expiry (scope, expires_at)
);
