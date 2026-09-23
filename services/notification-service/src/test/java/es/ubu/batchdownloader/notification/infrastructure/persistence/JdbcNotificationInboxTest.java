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
 * Comprueba reservas y transiciones idempotentes del inbox sobre una base de datos aislada.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.notification.infrastructure.persistence.JdbcNotificationInbox
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
class JdbcNotificationInboxTest {

    /**
     * Valor compartido que fija n o w para el comportamiento del componente.
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
     * Crea una base de datos local vacía con el esquema de inbox y un reloj determinista.
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
     * Cierra la base de datos de la prueba para liberar sus recursos.
     */
    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    /**
     * Comprueba que un evento nuevo se reserva, se confirma y después se reconoce como duplicado
     * completado.
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
     * Comprueba que un intento fallido puede reservarse de nuevo para reintentar el envío.
     */
    @Test
    void makesAFailedEventAvailableToTheNextRetry() {
        UUID eventId = UUID.randomUUID();

        assertThat(inbox.claim(eventId, "notification.email.requested"))
                .isEqualTo(NotificationInbox.ClaimResult.ACQUIRED);
        inbox.markFailed(eventId, "Proveedor no disponible");

        assertThat(inbox.claim(eventId, "notification.email.requested"))
                .isEqualTo(NotificationInbox.ClaimResult.ACQUIRED);
        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM notification_inbox WHERE event_id = ?",
                Integer.class,
                eventId.toString());
        assertThat(attempts).isEqualTo(2);
    }

    /**
     * Comprueba que una segunda reserva del mismo evento se informa como ocupada mientras la
     * primera sigue vigente.
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
     * Consulta directamente el estado persistido para comprobar la transición de un evento.
     *
     * @param eventId UUID de la fila del inbox que se comprueba.
     * @return estado actual de la fila del inbox.
     */
    private String statusOf(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM notification_inbox WHERE event_id = ?",
                String.class,
                eventId.toString());
    }
}
