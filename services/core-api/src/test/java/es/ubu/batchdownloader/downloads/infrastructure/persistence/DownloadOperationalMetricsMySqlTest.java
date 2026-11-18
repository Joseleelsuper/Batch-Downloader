package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DownloadOperationalMetricsMySqlTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.13");
    static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    static JdbcTemplate jdbc;

    @BeforeAll static void schema() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.execute("CREATE TABLE download_jobs(id CHAR(36) PRIMARY KEY,status VARCHAR(24),created_at DATETIME(6))");
        jdbc.execute("CREATE TABLE download_job_storage(job_id CHAR(36),phase VARCHAR(24),reserved_bytes BIGINT)");
    }

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM download_job_storage");
        jdbc.update("DELETE FROM download_jobs");
        jdbc.update("INSERT INTO download_jobs VALUES('ready','READY','2026-11-06 09:59:00'),('failed','FAILED','2026-11-06 09:59:00'),('queued','QUEUED','2026-11-06 09:59:00')");
        jdbc.update("INSERT INTO download_job_storage VALUES('ready','READY',700),('queued','QUEUED',0)");
    }

    @Test void exposesStateCapacityAndFifoWaitingFromRealSqlAndResetsRemovedStates() {
        var registry = new SimpleMeterRegistry();
        var metrics = new DownloadOperationalMetrics(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), registry);
        metrics.refresh();
        for (String state : new String[]{"READY", "FAILED", "QUEUED"}) {
            assertThat(registry.get("download_jobs").tag("state", state).gauge().value()).isEqualTo(1);
        }
        assertThat(registry.get("download_jobs").tag("state", "PACKAGING").gauge().value()).isZero();
        assertThat(registry.get("download_storage_reserved_bytes").gauge().value()).isEqualTo(700);
        assertThat(registry.get("download_storage_queue_depth").gauge().value()).isEqualTo(1);
        assertThat(registry.get("download_storage_oldest_queued_seconds").gauge().value()).isEqualTo(60);
        assertThat(registry.get("download_storage_limit_bytes").gauge().value()).isEqualTo(10L * 1024 * 1024 * 1024);
        jdbc.update("DELETE FROM download_jobs WHERE status='FAILED'");
        metrics.refresh();
        assertThat(registry.get("download_jobs").tag("state", "FAILED").gauge().value()).isZero();
    }

    @Test void databaseFailuresPreserveTheLastSnapshotAndExposeItsFreshness() {
        var registry = new SimpleMeterRegistry();
        var metrics = new DownloadOperationalMetrics(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), registry);
        assertThat(registry.get("download_metrics_last_success_timestamp_seconds").gauge().value()).isZero();
        metrics.refresh();
        jdbc.execute("RENAME TABLE download_jobs TO download_jobs_unavailable");
        try { metrics.refresh(); }
        finally { jdbc.execute("RENAME TABLE download_jobs_unavailable TO download_jobs"); }
        assertThat(registry.get("download_jobs").tag("state", "READY").gauge().value()).isEqualTo(1);
        assertThat(registry.get("download_metrics_refresh_failures").counter().count()).isEqualTo(1);
        assertThat(registry.get("download_metrics_last_success_timestamp_seconds").gauge().value()).isEqualTo(NOW.getEpochSecond());
    }

    @Test void unknownDatabaseValuesCannotCreateMetricLabels() {
        jdbc.update("INSERT INTO download_jobs VALUES('unexpected','untrusted-input','2026-11-06 09:59:00')");
        var registry = new SimpleMeterRegistry();
        new DownloadOperationalMetrics(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), registry).refresh();
        assertThat(registry.find("download_jobs").gauges()).hasSize(DownloadJobStatus.values().length);
        assertThat(registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getValue())).doesNotContain("untrusted-input", "ready", "failed", "queued");
    }
}
