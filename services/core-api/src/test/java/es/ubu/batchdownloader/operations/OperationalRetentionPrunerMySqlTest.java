package es.ubu.batchdownloader.operations;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Ejecuta los borrados reales y protege sesiones válidas, trabajos activos e historial de cuenta. */
@Testcontainers(disabledWithoutDocker = true)
class OperationalRetentionPrunerMySqlTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.13");

    @Test
    void deletesOnlyExpiredAuxiliaryDataAndCascadesExpiredSessionAttributes() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Instant now = Instant.parse("2026-10-24T12:00:00Z");
        Timestamp recent = Timestamp.from(now.minusSeconds(60));
        Timestamp old = Timestamp.from(now.minusSeconds(86400));
        jdbc.execute("CREATE TABLE core_outbox_events (id VARCHAR(36) PRIMARY KEY, event_type VARCHAR(120), published_at DATETIME(6), occurred_at DATETIME(6), claim_token VARCHAR(36))");
        jdbc.execute("CREATE TABLE core_inbox_messages (message_id VARCHAR(36) PRIMARY KEY, processed_at DATETIME(6))");
        jdbc.execute("CREATE TABLE download_jobs (id VARCHAR(36) PRIMARY KEY, status VARCHAR(24), updated_at DATETIME(6))");
        jdbc.execute("CREATE TABLE download_job_storage (job_id VARCHAR(36) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE identity_tokens (id VARCHAR(36) PRIMARY KEY, consumed_at DATETIME(6), expires_at DATETIME(6))");
        jdbc.execute("CREATE TABLE SPRING_SESSION (PRIMARY_ID VARCHAR(36) PRIMARY KEY, EXPIRY_TIME BIGINT)");
        jdbc.execute("CREATE TABLE SPRING_SESSION_ATTRIBUTES (SESSION_PRIMARY_ID VARCHAR(36), FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE admin_audit_logs (id VARCHAR(36) PRIMARY KEY, created_at DATETIME(6))");
        jdbc.execute("CREATE TABLE user_download_history (id VARCHAR(36) PRIMARY KEY)");
        jdbc.update("INSERT INTO user_download_history VALUES ('history-kept')");
        jdbc.update("INSERT INTO core_outbox_events VALUES ('published','download.job.requested',?,?,NULL)", recent, recent);
        jdbc.update("INSERT INTO core_outbox_events VALUES ('expired-mail','notification.email.requested',NULL,?,NULL)", old);
        jdbc.update("INSERT INTO core_outbox_events VALUES ('pending-job','download.job.requested',NULL,?,NULL)", old);
        jdbc.update("INSERT INTO core_outbox_events VALUES ('valid-mail','notification.email.requested',NULL,?,NULL)", recent);
        jdbc.update("INSERT INTO core_inbox_messages VALUES ('processed-old',?),('processed-recent',?),('pending',NULL)", old, recent);
        jdbc.update("INSERT INTO download_jobs VALUES ('finished','READY',?),('active','DOWNLOADING',?),('stored','READY',?)", old, old, old);
        jdbc.update("INSERT INTO download_job_storage VALUES ('stored')");
        jdbc.update("INSERT INTO identity_tokens VALUES ('expired',NULL,?),('consumed',?,?),('valid',NULL,?)",
                old, recent, Timestamp.from(now.plusSeconds(600)), Timestamp.from(now.plusSeconds(600)));
        jdbc.update("INSERT INTO SPRING_SESSION VALUES ('expired',?),('valid',?)", now.minusSeconds(1).toEpochMilli(), now.plusSeconds(600).toEpochMilli());
        jdbc.update("INSERT INTO SPRING_SESSION_ATTRIBUTES VALUES ('expired'),('valid')");
        jdbc.update("INSERT INTO admin_audit_logs VALUES ('old',?),('recent',?)", old, recent);

        var pruner = new OperationalRetentionPruner(jdbc, Clock.fixed(now, ZoneOffset.UTC),
                new SimpleMeterRegistry(), new DataSourceTransactionManager(source),
                Duration.ofMinutes(1), Duration.ofHours(24), Duration.ofHours(24), Duration.ofMinutes(15));
        pruner.runScheduled();

        assertThat(jdbc.queryForList("SELECT id FROM core_outbox_events", String.class)).containsExactlyInAnyOrder("pending-job", "valid-mail");
        assertThat(jdbc.queryForList("SELECT message_id FROM core_inbox_messages", String.class)).containsExactlyInAnyOrder("processed-recent", "pending");
        assertThat(jdbc.queryForList("SELECT id FROM download_jobs", String.class)).containsExactlyInAnyOrder("active", "stored");
        assertThat(jdbc.queryForList("SELECT id FROM identity_tokens", String.class)).containsExactly("valid");
        assertThat(jdbc.queryForList("SELECT SESSION_PRIMARY_ID FROM SPRING_SESSION_ATTRIBUTES", String.class)).containsExactly("valid");
        assertThat(jdbc.queryForList("SELECT id FROM admin_audit_logs", String.class)).containsExactly("recent");
        assertThat(jdbc.queryForList("SELECT id FROM user_download_history", String.class)).containsExactly("history-kept");
    }
}
