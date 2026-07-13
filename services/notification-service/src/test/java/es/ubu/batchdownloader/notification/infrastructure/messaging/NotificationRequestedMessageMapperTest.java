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

class NotificationRequestedMessageMapperTest {

    private static final String ROUTING_KEY = "notification.email.requested";
    private static final UUID EVENT_ID = UUID.fromString("83e7ddfe-0fb4-4f19-9694-137ada2bb39c");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-11T10:00:00Z");

    private NotificationRequestedMessageMapper mapper;

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

    @Test
    void supportsTheIdentityTemplatesPublishedByCoreApi() {
        NotificationRequestedMessage message = message(
                "EMAIL_VERIFICATION",
                Map.of("username", "Ada", "token", "secret-token"));

        EmailNotification result = mapper.map(message, ROUTING_KEY);

        assertThat(result.template()).isEqualTo(EmailNotification.Template.EMAIL_VERIFICATION);
        assertThat(result.requiredParameter("username")).isEqualTo("Ada");
    }

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

    @Test
    void rejectsARoutingKeyThatDoesNotMatchTheContract() {
        NotificationRequestedMessage message = message(
                "PASSWORD_RESET",
                Map.of("username", "Ada", "token", "secret-token"));

        assertThatThrownBy(() -> mapper.map(message, "batch.events.v1.download.job.ready"))
                .isInstanceOf(InvalidDownloadEventException.class)
                .hasMessageContaining("Routing key");
    }

    @Test
    void rejectsNonScalarParameters() {
        NotificationRequestedMessage message = message(
                "PASSWORD_RESET",
                Map.of("username", "Ada", "token", Map.of("nested", "invalid")));

        assertThatThrownBy(() -> mapper.map(message, ROUTING_KEY))
                .isInstanceOf(InvalidDownloadEventException.class)
                .hasMessageContaining("string, number o boolean");
    }

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
