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

/**
 * Agrupa los escenarios de prueba de {@code JdbcNotificationInboxTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class JdbcNotificationInboxTest {

    /**
     * Constante que define {@code NOW}.
     */
    private static final Instant NOW = Instant.parse("2026-07-11T10:00:00Z");

    /**
     * Dato compartido {@code database} para los escenarios de prueba.
     */
    private EmbeddedDatabase database;
    /**
     * Dato compartido {@code inbox} para los escenarios de prueba.
     */
    private JdbcNotificationInbox inbox;
    /**
     * Dato compartido {@code jdbcTemplate} para los escenarios de prueba.
     */
    private JdbcTemplate jdbcTemplate;

    /**
     * Prepara el estado necesario para los escenarios de prueba.
     */
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

    /**
     * Libera el estado utilizado por los escenarios de prueba.
     */
    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    /**
     * Comprueba el escenario {@code persistsAndDeduplicatesAProcessedEvent}.
     */
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

    /**
     * Comprueba el escenario {@code makesAFailedEventAvailableToTheNextRetry}.
     */
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

    /**
     * Comprueba el escenario {@code reportsAnActiveLeaseAsBusy}.
     */
    @Test
    void reportsAnActiveLeaseAsBusy() {
        UUID eventId = UUID.randomUUID();

        assertThat(inbox.claim(eventId, "notification.email.requested"))
                .isEqualTo(NotificationInbox.ClaimResult.ACQUIRED);

        assertThat(inbox.claim(eventId, "notification.email.requested"))
                .isEqualTo(NotificationInbox.ClaimResult.BUSY);
    }

    /**
     * Ejecuta la operación {@code statusOf}.
     *
     * @param eventId Identificador de {@code event} utilizado por la operación.
     * @return Resultado producido por {@code statusOf}.
     */
    private String statusOf(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM notification_inbox WHERE event_id = ?",
                String.class,
                eventId.toString());
    }
}
