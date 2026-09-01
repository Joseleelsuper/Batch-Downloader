package es.ubu.batchdownloader.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifica las migraciones Flyway contra el mismo MySQL 8.4 utilizado en producción. */
@Testcontainers(disabledWithoutDocker = true)
class CoreMySqlMigrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("batch_downloader")
            .withUsername("batch")
            .withPassword("batch-test")
            .withCommand("--log-bin-trust-function-creators=1");

    /** Prepara un snapshot V11, prueba el preflight y completa las migraciones vigentes. */
    @BeforeAll
    static void migrateCoreSchema() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE software_apps (
                        id BINARY(16) NOT NULL,
                        app_status VARCHAR(32) NOT NULL,
                        catalog_status VARCHAR(16) NULL,
                        catalog_review_priority TINYINT(1)
                            GENERATED ALWAYS AS (
                                CASE WHEN catalog_status = 'review' THEN 1 ELSE 0 END
                            ) STORED,
                        catalog_review_source_count INT UNSIGNED NOT NULL DEFAULT 0,
                        normalized_name VARCHAR(180) NOT NULL,
                        PRIMARY KEY (id)
                    )
                    """);
        }
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .locations("classpath:db/migration")
                .load();
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .locations("classpath:db/migration")
                .target("11")
                .load()
                .migrate();

        try (Connection connection = connection()) {
            execute(connection, """
                    INSERT INTO bundles (
                        id, slug, name, description, type, visibility, owner_username, owner_id,
                        created_at, updated_at
                    ) VALUES (UUID_TO_BIN(?), 'unresolved-owner', 'Unresolved owner', NULL,
                        'user', 'private', 'missing-user', NULL, NOW(), NOW())
                    """, UUID.randomUUID().toString());
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(flyway::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V12");

        try (Connection connection = connection()) {
            assertThat(flywayVersion(connection)).isEqualTo("11");
            execute(connection, "DELETE FROM bundles WHERE slug = 'unresolved-owner'");
            execute(connection, """
                    INSERT INTO SPRING_SESSION (
                        PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME,
                        MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME
                    ) VALUES (?, ?, 0, 0, 1800, 1800000, 'legacy-principal')
                    """, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        }
        flyway.repair();
        flyway.migrate();
    }

    /**
     * Comprueba catálogo, administración, cascadas de bundles y atomicidad de triggers.
     *
     * @throws SQLException si MySQL rechaza una operación del contrato migrado
     */
    @Test
    void migratesCatalogAdminBundlesTriggersDeletionAndRollback() throws SQLException {
        UUID appId = UUID.randomUUID();
        String userId = UUID.randomUUID().toString();
        UUID bundleId = UUID.randomUUID();

        try (Connection connection = connection()) {
            insertCatalogApp(connection, appId);
            insertUser(connection, userId);
            insertBundleGraph(connection, bundleId, appId, userId);
            assertThat(count(connection, "SPRING_SESSION")).isZero();
            assertThat(columnCount(connection, "bundles", "owner_username")).isZero();
            assertThat(columnNullable(connection, "download_job_items", "source_ref")).isTrue();
            assertThat(columnNullable(connection, "download_jobs", "artifact_size_bytes")).isTrue();
            assertThat(columnNullable(connection, "download_jobs", "artifact_sha256")).isTrue();
            assertThat(columnNullable(connection, "download_jobs", "wait_reason")).isTrue();
            assertThat(columnNullable(connection, "download_jobs", "retry_at")).isTrue();
            assertThat(tableExists(connection, "oauth_identities")).isFalse();
            assertThat(columnNullable(connection, "core_users", "password_hash")).isFalse();
            execute(connection, """
                    INSERT INTO admin_audit_logs (
                        id, actor, action, target_type, target_id, safe_metadata, created_at
                    ) VALUES (UUID_TO_BIN(?), 'migration-admin', 'VERIFY', 'schema', NULL,
                        JSON_OBJECT('safe', TRUE), NOW())
                    """, UUID.randomUUID().toString());
            assertThat(count(connection, "admin_audit_logs")).isEqualTo(1L);

            execute(connection, "DELETE FROM bundles WHERE id = UUID_TO_BIN(?)", bundleId.toString());
            assertThat(count(connection, "bundle_items")).isZero();
            assertThat(count(connection, "bundle_tags")).isZero();
            assertThat(count(connection, "bundle_stars")).isZero();

            UUID firstJob = UUID.randomUUID();
            insertDownloadJob(connection, firstJob, userId);
            insertDownloadItem(connection, firstJob, appId, "PENDING");
            execute(connection, "UPDATE download_job_items SET status = 'COMPLETED' WHERE job_id = ?",
                    firstJob.toString());
            assertThat(downloadCount(connection, appId)).isEqualTo(1L);

            UUID secondJob = UUID.randomUUID();
            insertDownloadJob(connection, secondJob, userId);
            insertDownloadItem(connection, secondJob, appId, "COMPLETED");
            assertThat(downloadCount(connection, appId)).isEqualTo(2L);

            connection.setAutoCommit(false);
            UUID rolledBackJob = UUID.randomUUID();
            insertDownloadJob(connection, rolledBackJob, userId);
            insertDownloadItem(connection, rolledBackJob, appId, "COMPLETED");
            assertThat(downloadCount(connection, appId)).isEqualTo(3L);
            connection.rollback();
            connection.setAutoCommit(true);
            assertThat(downloadCount(connection, appId)).isEqualTo(2L);

            assertThat(flywayVersion(connection)).isEqualTo("15");
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static void insertCatalogApp(Connection connection, UUID appId) throws SQLException {
        execute(connection, """
                INSERT INTO software_apps (
                    id, app_status, catalog_status, catalog_review_source_count, normalized_name
                ) VALUES (UUID_TO_BIN(?), 'active', 'available', 0, 'migration-test')
                """, appId.toString());
    }

    private static void insertUser(Connection connection, String userId) throws SQLException {
        execute(connection, """
                INSERT INTO core_users (
                    id, username, normalized_username, email, normalized_email, password_hash,
                    email_verified, role, enabled, created_at, updated_at
                ) VALUES (?, 'migration-user', 'migration-user', 'migration@example.test',
                    'migration@example.test', 'migration-password-hash', TRUE, 'USER', TRUE, NOW(6), NOW(6))
                """, userId);
    }

    private static void insertBundleGraph(
            Connection connection, UUID bundleId, UUID appId, String userId) throws SQLException {
        execute(connection, """
                INSERT INTO bundles (
                    id, slug, name, description, type, visibility, owner_id,
                    created_at, updated_at
                ) VALUES (UUID_TO_BIN(?), 'migration-bundle', 'Migration bundle', NULL,
                    'USER', 'PRIVATE', ?, NOW(), NOW())
                """, bundleId.toString(), userId);
        execute(connection, """
                INSERT INTO bundle_items (id, bundle_id, software_app_id, sort_order, created_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), 0, NOW())
                """, UUID.randomUUID().toString(), bundleId.toString(), appId.toString());
        execute(connection, """
                INSERT INTO bundle_tags (id, bundle_id, tag, normalized_tag, created_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'Test', 'test', NOW())
                """, UUID.randomUUID().toString(), bundleId.toString());
        execute(connection, """
                INSERT INTO bundle_stars (id, bundle_id, user_key, created_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'migration-user', NOW())
                """, UUID.randomUUID().toString(), bundleId.toString());
    }

    private static void insertDownloadJob(Connection connection, UUID jobId, String userId)
            throws SQLException {
        execute(connection, """
                INSERT INTO download_jobs (
                    id, owner_id, status, progress, cancellation_requested, notify_when_ready,
                    requested_count, accepted_count, omitted_count, created_at, updated_at, expires_at
                ) VALUES (?, ?, 'QUEUED', 0, FALSE, FALSE, 1, 1, 0, NOW(6), NOW(6), ?)
                """, jobId.toString(), userId, LocalDateTime.now().plusHours(1));
    }

    private static void insertDownloadItem(
            Connection connection, UUID jobId, UUID appId, String status) throws SQLException {
        execute(connection, """
                INSERT INTO download_job_items (
                    id, job_id, app_id, source_ref, app_name, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'Migration app', ?, NOW(6), NOW(6))
                """, UUID.randomUUID().toString(), jobId.toString(), appId.toString(),
                UUID.randomUUID().toString(), status);
    }

    private static long downloadCount(Connection connection, UUID appId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT download_count FROM software_apps WHERE id = UUID_TO_BIN(?)")) {
            statement.setString(1, appId.toString());
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        String query = switch (table) {
            case "admin_audit_logs" -> "SELECT COUNT(*) FROM admin_audit_logs";
            case "bundle_items" -> "SELECT COUNT(*) FROM bundle_items";
            case "bundle_tags" -> "SELECT COUNT(*) FROM bundle_tags";
            case "bundle_stars" -> "SELECT COUNT(*) FROM bundle_stars";
            case "SPRING_SESSION" -> "SELECT COUNT(*) FROM SPRING_SESSION";
            default -> throw new IllegalArgumentException("Tabla no permitida: " + table);
        };
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(query)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static long columnCount(Connection connection, String table, String column)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private static boolean columnNullable(Connection connection, String table, String column)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT IS_NULLABLE
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return "YES".equals(result.getString(1));
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1) == 1L;
            }
        }
    }

    private static String flywayVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT version FROM flyway_schema_history WHERE success = TRUE "
                                + "ORDER BY installed_rank DESC LIMIT 1")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }
}
