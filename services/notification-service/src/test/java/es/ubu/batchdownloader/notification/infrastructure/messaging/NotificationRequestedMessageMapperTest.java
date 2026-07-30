package es.ubu.batchdownloader.notification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ubu.batchdownloader.notification.config.RabbitTopologyProperties;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Agrupa los escenarios de prueba de {@code NotificationRequestedMessageMapperTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class NotificationRequestedMessageMapperTest {

    /**
     * Constante que define {@code ROUTING_KEY}.
     */
    private static final String ROUTING_KEY = "notification.email.requested";
    /**
     * Constante que define {@code EVENT_ID}.
     */
    private static final UUID EVENT_ID = UUID.fromString("83e7ddfe-0fb4-4f19-9694-137ada2bb39c");
    /**
     * Constante que define {@code OCCURRED_AT}.
     */
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-11T10:00:00Z");

    /**
     * Dato compartido {@code mapper} para los escenarios de prueba.
     */
    private NotificationRequestedMessageMapper mapper;

    /**
     * Prepara el estado necesario para los escenarios de prueba.
     */
    @BeforeEach
    void setUp() {
        RabbitTopologyProperties topology = new RabbitTopologyProperties(
                "batch.commands.v1",
                ROUTING_KEY,
                "notification.email-requests.v1",
                "batch-downloader.dlx",
                "batch.commands.v1.notification.email.requested.dead",
                "notification.email-requests.dlq.v1");
        mapper = new NotificationRequestedMessageMapper(topology);
    }

    /**
     * Comprueba el escenario {@code mapsTheCanonicalDownloadReadyRequest}.
     */
    @Test
    void mapsTheCanonicalDownloadReadyRequest() {
        NotificationRequestedMessage message = message(
                "DOWNLOAD_READY",
                Map.of(
                        "jobId", "84338aa2-b2f0-47d1-9054-5760ac883d74",
                        "expiresAt", "2026-07-12T10:00:00Z"));

        EmailNotification result = mapper.map(message, ROUTING_KEY);

        assertThat(result.eventId()).isEqualTo(EVENT_ID);
        assertThat(result.template()).isEqualTo(EmailNotification.Template.DOWNLOAD_READY);
        assertThat(result.recipient()).isEqualTo("persona@example.com");
        assertThat(result.parameters()).doesNotContainKey("downloadUrl");
    }

    /**
     * Comprueba el escenario {@code supportsTheIdentityTemplatesPublishedByCoreApi}.
     */
    @Test
    void supportsTheIdentityTemplatesPublishedByCoreApi() {
        NotificationRequestedMessage message = message(
                "EMAIL_VERIFICATION",
                Map.of("username", "Ada", "token", "secret-token"));

        EmailNotification result = mapper.map(message, ROUTING_KEY);

        assertThat(result.template()).isEqualTo(EmailNotification.Template.EMAIL_VERIFICATION);
        assertThat(result.requiredParameter("username")).isEqualTo("Ada");
    }

    /**
     * Comprueba el escenario {@code acceptsErrorCodeAsTheFailureCodeFallback}.
     */
    @Test
    void acceptsErrorCodeAsTheFailureCodeFallback() {
        NotificationRequestedMessage message = message(
                "DOWNLOAD_FAILED",
                Map.of(
                        "jobId", "84338aa2-b2f0-47d1-9054-5760ac883d74",
                        "errorCode", "REMOTE_DOWNLOAD_FAILED",
                        "failureMessage", "No se pudo recuperar un instalador"));

        EmailNotification result = mapper.map(message, ROUTING_KEY);

        assertThat(result.template()).isEqualTo(EmailNotification.Template.DOWNLOAD_FAILED);
        assertThat(result.parameters()).containsEntry("errorCode", "REMOTE_DOWNLOAD_FAILED");
    }

    /**
     * Comprueba el escenario {@code rejectsUnsupportedSchemaVersions}.
     */
    @Test
    void rejectsUnsupportedSchemaVersions() {
        NotificationRequestedMessage original = message(
                "PASSWORD_RESET",
                Map.of("username", "Ada", "token", "secret-token"));
        NotificationRequestedMessage unsupported = new NotificationRequestedMessage(
                original.eventId(),
                original.type(),
                2,
                original.occurredAt(),
                original.correlationId(),
                original.causationId(),
                original.payload());

        assertThatThrownBy(() -> mapper.map(unsupported, ROUTING_KEY))
                .isInstanceOf(InvalidDownloadEventException.class)
                .hasMessageContaining("Versión");
    }

    /**
     * Comprueba el escenario {@code rejectsARoutingKeyThatDoesNotMatchTheContract}.
     */
    @Test
    void rejectsARoutingKeyThatDoesNotMatchTheContract() {
        NotificationRequestedMessage message = message(
                "PASSWORD_RESET",
                Map.of("username", "Ada", "token", "secret-token"));

        assertThatThrownBy(() -> mapper.map(message, "batch.events.v1.download.job.ready"))
                .isInstanceOf(InvalidDownloadEventException.class)
                .hasMessageContaining("Routing key");
    }

    /**
     * Comprueba el escenario {@code rejectsNonScalarParameters}.
     */
    @Test
    void rejectsNonScalarParameters() {
        NotificationRequestedMessage message = message(
                "PASSWORD_RESET",
                Map.of("username", "Ada", "token", Map.of("nested", "invalid")));

        assertThatThrownBy(() -> mapper.map(message, ROUTING_KEY))
                .isInstanceOf(InvalidDownloadEventException.class)
                .hasMessageContaining("string, number o boolean");
    }

    /**
     * Ejecuta la operación {@code message}.
     *
     * @param template Valor de {@code template} utilizado por la operación.
     * @param parameters Valor de {@code parameters} utilizado por la operación.
     * @return Resultado producido por {@code message}.
     */
    private NotificationRequestedMessage message(String template, Map<String, Object> parameters) {
        return new NotificationRequestedMessage(
                EVENT_ID,
                EmailNotification.EVENT_TYPE,
                EmailNotification.SCHEMA_VERSION,
                OCCURRED_AT,
                "correlation-123",
                "download-ready-event-456",
                new NotificationRequestedMessage.Payload(
                        "persona@example.com", template, parameters));
    }
}
