package es.ubu.batchdownloader.notification.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import es.ubu.batchdownloader.notification.application.port.NotificationInbox;
import es.ubu.batchdownloader.notification.config.InboxProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class JdbcNotificationInboxTest {

    private static final Instant NOW = Instant.parse("2026-07-11T10:00:00Z");

    private EmbeddedDatabase database;
    private JdbcNotificationInbox inbox;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .build();
        DataSource dataSource = database;
        jdbcTemplate = new JdbcTemplate(dataSource);
        inbox = new JdbcNotificationInbox(
                jdbcTemplate,
                new InboxProperties(Duration.ofMinutes(5)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsAndDeduplicatesAProcessedEvent() {
        UUID eventId = UUID.randomUUID();

        assertThat(inbox.claim(eventId, "notification.email.requested"))
                .isEqualTo(NotificationInbox.ClaimResult.ACQUIRED);
        inbox.markProcessed(eventId);

        assertThat(inbox.claim(eventId, "notification.email.requested"))
                .isEqualTo(NotificationInbox.ClaimResult.ALREADY_PROCESSED);
        assertThat(statusOf(eventId)).isEqualTo("PROCESSED");
    }

    @Test
    void makesAFailedEventAvailableToTheNextRetry() {
        UUID eventId = UUID.randomUUID();

        assertThat(inbox.claim(eventId, "notification.email.requested"))
                .isEqualTo(NotificationInbox.ClaimResult.ACQUIRED);
        inbox.markFailed(eventId, "SMTP no disponible");

        assertThat(inbox.claim(eventId, "notification.email.requested"))
                .isEqualTo(NotificationInbox.ClaimResult.ACQUIRED);
        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM notification_inbox WHERE event_id = ?",
                Integer.class,
                eventId.toString());
        assertThat(attempts).isEqualTo(2);
    }

    @Test
    void reportsAnActiveLeaseAsBusy() {
        UUID eventId = UUID.randomUUID();

        assertThat(inbox.claim(eventId, "notification.email.requested"))
                .isEqualTo(NotificationInbox.ClaimResult.ACQUIRED);

        assertThat(inbox.claim(eventId, "notification.email.requested"))
                .isEqualTo(NotificationInbox.ClaimResult.BUSY);
    }

    private String statusOf(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM notification_inbox WHERE event_id = ?",
                String.class,
                eventId.toString());
    }
}
