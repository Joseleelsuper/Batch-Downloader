package es.ubu.batchdownloader.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Comprueba el borrado por aplicación con reservas y transacciones reales de MySQL. */
@Testcontainers(disabledWithoutDocker = true)
class AdminAppDeletionMySqlTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.13");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC);
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private AdminAppRepository repository;

    @BeforeAll
    static void createSchema() {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        for (String definition : List.of(
                "software_apps (id BINARY(16) PRIMARY KEY, winstall_id VARCHAR(180) NOT NULL)",
                "download_sources (id BINARY(16) PRIMARY KEY, software_app_id BINARY(16))",
                "software_app_tags (software_app_id BINARY(16))",
                "bundle_items (bundle_id BINARY(16), software_app_id BINARY(16))",
                "manual_installer_inspections (id BINARY(16) PRIMARY KEY, software_app_id BINARY(16),"
                        + " FOREIGN KEY (software_app_id) REFERENCES software_apps(id) ON DELETE CASCADE)",
                "scraper_work_items (id BINARY(16) PRIMARY KEY, queue VARCHAR(32), package_id VARCHAR(180),"
                        + " status VARCHAR(32), lease_expires_at DATETIME(6))")) {
            jdbc.execute("CREATE TABLE " + definition);
        }
    }

    @BeforeEach
    void resetCatalog() {
        for (String table : List.of("scraper_work_items", "manual_installer_inspections", "software_apps")) {
            jdbc.update("DELETE FROM " + table);
        }
        var catalog = mock(CatalogRepository.class);
        repository = new AdminAppRepository(jdbc, catalog, new AdminAppExportRepository(jdbc),
                mock(InstallerAbsenceRepository.class), new AdminAppSourceRepository(jdbc, catalog, CLOCK), CLOCK);
        when(catalog.softwareAppId(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> UUID.fromString(invocation.getArgument(0)));
    }

    @Test
    void deletesOnlyTheSelectedAppAndItsQueuedOrExpiredWork() {
        UUID selected = app("Microsoft.Promptflow");
        UUID other = app("Other.App");
        work("searcher_filter", "Microsoft.Promptflow", "queued", null);
        work("filter_scraper", "Microsoft.Promptflow", "in_progress", CLOCK.instant().minusSeconds(1));
        work("filter_scraper", "Other.App", "in_progress", CLOCK.instant().plusSeconds(60));

        transactions.executeWithoutResult(status -> repository.delete(selected.toString()));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM software_apps WHERE id = ?", Integer.class,
                UuidBytes.fromUuid(selected))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM software_apps WHERE id = ?", Integer.class,
                UuidBytes.fromUuid(other))).isOne();
        assertThat(jdbc.queryForList("SELECT package_id FROM scraper_work_items", String.class))
                .containsExactly("Other.App");
    }

    @Test
    void rejectsAnActiveLeaseAndKeepsTheAppAndItsQueueIntact() {
        UUID selected = app("Microsoft.Promptflow");
        work("searcher_filter", "Microsoft.Promptflow", "queued", null);
        work("filter_scraper", "Microsoft.Promptflow", "in_progress", CLOCK.instant().plusSeconds(60));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> repository.delete(selected.toString())))
                .isInstanceOf(ConflictException.class)
                .extracting(exception -> ((ConflictException) exception).code()).isEqualTo("app_processing");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM software_apps", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scraper_work_items", Integer.class)).isEqualTo(2);
    }

    @Test
    void removesManualInspectionWorkBeforeItsApplicationIsDeleted() {
        UUID selected = app("Manual.App");
        UUID inspection = UUID.randomUUID();
        jdbc.update("INSERT INTO manual_installer_inspections VALUES (?, ?)",
                UuidBytes.fromUuid(inspection), UuidBytes.fromUuid(selected));
        work("manual_installer_enrichment", inspection.toString(), "queued", null);
        work("searcher_filter", "Other.App", "queued", null);

        transactions.executeWithoutResult(status -> repository.delete(selected.toString()));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manual_installer_inspections", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT package_id FROM scraper_work_items", String.class))
                .containsExactly("Other.App");
    }

    private UUID app(String packageId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO software_apps VALUES (?, ?)", UuidBytes.fromUuid(id), packageId);
        return id;
    }

    private void work(String queue, String packageId, String status, Instant leaseExpiresAt) {
        jdbc.update("INSERT INTO scraper_work_items VALUES (?, ?, ?, ?, ?)",
                UuidBytes.fromUuid(UUID.randomUUID()), queue, packageId, status,
                leaseExpiresAt == null ? null : Timestamp.from(leaseExpiresAt));
    }
}
