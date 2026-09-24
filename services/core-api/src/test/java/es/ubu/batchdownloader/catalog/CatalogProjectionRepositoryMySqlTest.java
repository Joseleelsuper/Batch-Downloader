package es.ubu.batchdownloader.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Comprueba el enriquecimiento real con pools pequeños sin adquisiciones JDBC anidadas. */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class CatalogProjectionRepositoryMySqlTest {
    private static final String APP_ID = "00000000-0000-0000-0000-000000000001";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    /** Crea las tablas de lectura y una aplicación con fuente, etiquetas y plataforma. */
    @BeforeAll
    static void prepareCatalog() {
        try (HikariDataSource pool = pool(1)) {
            JdbcTemplate jdbc = new JdbcTemplate(pool);
            jdbc.execute("""
                    CREATE TABLE software_apps (
                        id BINARY(16) PRIMARY KEY, winstall_id VARCHAR(180), slug VARCHAR(180),
                        name VARCHAR(180), publisher VARCHAR(180), description TEXT,
                        long_description TEXT, icon_url TEXT, official_url TEXT,
                        latest_version VARCHAR(32), catalog_status VARCHAR(16),
                        updated_at DATETIME, app_status VARCHAR(16), operating_systems_json JSON
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE download_sources (
                        id BINARY(16) PRIMARY KEY, software_app_id BINARY(16), initial_url TEXT,
                        resolution_status VARCHAR(32), validation_status VARCHAR(32),
                        catalog_available BOOLEAN, operating_system VARCHAR(16), architecture VARCHAR(16)
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE resolved_sources (
                        id BINARY(16) PRIMARY KEY, download_source_id BINARY(16), filename VARCHAR(180),
                        extension VARCHAR(16), content_type VARCHAR(180), size_bytes BIGINT,
                        final_domain VARCHAR(180), score INT, checked_at DATETIME, expires_at DATETIME,
                        metadata_json JSON, release_rank INT, is_latest BOOLEAN, catalog_downloadable BOOLEAN,
                        status VARCHAR(32), version VARCHAR(32), version_status VARCHAR(32)
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE linux_install_profiles (
                        source_ref BINARY(16), status VARCHAR(32), profile_json JSON
                    )
                    """);
            jdbc.execute("CREATE TABLE software_app_tags (software_app_id BINARY(16), tag VARCHAR(180))");
            jdbc.update("""
                    INSERT INTO software_apps VALUES (UUID_TO_BIN(?), 'Example.App', 'example',
                        'Example', 'Publisher', 'Description', 'Long description', NULL,
                        'https://example.test', '1.0', 'available', NOW(), 'active', JSON_ARRAY('windows'))
                    """, APP_ID);
            jdbc.update("""
                    INSERT INTO download_sources VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?),
                        'https://example.test/download', 'direct', 'valid', TRUE, 'windows', 'x64')
                    """, APP_ID, APP_ID);
            jdbc.update("""
                    INSERT INTO resolved_sources VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'example.exe',
                        '.exe', 'application/octet-stream', 1024, 'example.test', 100, NOW(),
                        DATE_ADD(NOW(), INTERVAL 1 DAY), JSON_OBJECT('is_primary', TRUE), 1, TRUE,
                        TRUE, 'direct', '1.0', 'current')
                    """, APP_ID, APP_ID);
            jdbc.update("INSERT INTO software_app_tags VALUES (UUID_TO_BIN(?), 'utility')", APP_ID);
        }
    }

    /** Una única conexión debe bastar para resolver identidad y todo el detalle. */
    @Test
    void detailsEnrichesAfterReleasingItsOnlyConnection() {
        try (HikariDataSource pool = pool(1)) {
            CatalogProjectionRepository repository = new CatalogProjectionRepository(new JdbcTemplate(pool));

            assertDetails(repository.details("example"));
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }

    /** Sincroniza cinco lectores para reproducir la contención de seis solicitudes y cinco conexiones. */
    @Test
    void sixConcurrentDetailsCompleteWithFiveConnections() throws Exception {
        try (HikariDataSource pool = pool(5);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch readers = new CountDownLatch(5);
            CatalogProjectionRepository repository = new CatalogProjectionRepository(new JdbcTemplate(pool)) {
                @Override
                AppBasics readBasics(ResultSet rs) throws SQLException {
                    AppBasics app = super.readBasics(rs);
                    readers.countDown();
                    try {
                        if (!readers.await(5, TimeUnit.SECONDS)) {
                            throw new SQLException("No se reunieron los cinco lectores simultáneos.");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new SQLException(exception);
                    }
                    return app;
                }
            };
            List<Future<AppDetails>> requests = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                requests.add(executor.submit(() -> repository.details(APP_ID)));
            }
            for (Future<AppDetails> request : requests) {
                assertDetails(request.get(10, TimeUnit.SECONDS));
            }
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }

    private static HikariDataSource pool(int size) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MYSQL.getJdbcUrl());
        config.setUsername(MYSQL.getUsername());
        config.setPassword(MYSQL.getPassword());
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(size);
        config.setConnectionTimeout(2_000);
        return new HikariDataSource(config);
    }

    private static void assertDetails(AppDetails details) {
        assertThat(details.id()).isEqualTo(APP_ID);
        assertThat(details.name()).isEqualTo("Example");
        assertThat(details.tags()).containsExactly("utility");
        assertThat(details.operatingSystems()).containsExactly("windows");
        assertThat(details.downloadable()).isTrue();
        assertThat(details.installerFilename()).isEqualTo("example.exe");
        assertThat(details.sizeBytes()).isEqualTo(1024);
        assertThat(details.downloadOptions()).singleElement().satisfies(option -> {
            assertThat(option.filename()).isEqualTo("example.exe");
            assertThat(option.operatingSystem()).isEqualTo("windows");
        });
    }
}
