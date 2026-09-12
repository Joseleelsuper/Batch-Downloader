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
 * Comprueba conversión y rechazo del contrato de mensajería antes de reservar o enviar un evento.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see
 *     es.ubu.batchdownloader.notification.infrastructure.messaging.NotificationRequestedMessageMapper
 *
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
class NotificationRequestedMessageMapperTest {

    /**
     * Constante de protocolo que identifica o protege r o u t i n g  k e y.
     */
    private static final String ROUTING_KEY = "notification.email.requested";
    /**
     * Valor compartido que fija e v e n t  i d para el comportamiento del componente.
     */
    private static final UUID EVENT_ID = UUID.fromString("83e7ddfe-0fb4-4f19-9694-137ada2bb39c");
    /**
     * Valor compartido que fija o c c u r r e d  a t para el comportamiento del componente.
     */
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-11T10:00:00Z");

    /**
     * Dato compartido {@code mapper} para los escenarios de prueba.
     */
    private NotificationRequestedMessageMapper mapper;

    /**
     * Configura el conversor con la clave de enrutamiento canónica de la cola de correo.
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
     * Comprueba que un aviso canónico de ZIP disponible conserva identificadores y parámetros al
     * convertirse al dominio.
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
     * Comprueba que las plantillas de identidad publicadas por Core aceptan sus parámetros y sobres
     * cifrados.
     */
    @Test
    void supportsTheIdentityTemplatesPublishedByCoreApi() {
        NotificationRequestedMessage message = message(
                "EMAIL_VERIFICATION",
                Map.of("username", "Ada", "token", "enc:v1:contract-envelope"));

        EmailNotification result = mapper.map(message, ROUTING_KEY);

        assertThat(result.template()).isEqualTo(EmailNotification.Template.EMAIL_VERIFICATION);
        assertThat(result.requiredParameter("username")).isEqualTo("Ada");
    }

    /**
     * Comprueba la compatibilidad del alias errorCode en avisos de fallo.
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
     * Comprueba que una versión de sobre no soportada se rechaza antes del procesamiento.
     */
    @Test
    void rejectsUnsupportedSchemaVersions() {
        NotificationRequestedMessage original = message(
                "PASSWORD_RESET",
                Map.of("username", "Ada", "token", "enc:v1:contract-envelope"));
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
     * Comprueba que una clave de enrutamiento ajena al contrato impide convertir el mensaje.
     */
    @Test
    void rejectsARoutingKeyThatDoesNotMatchTheContract() {
        NotificationRequestedMessage message = message(
                "PASSWORD_RESET",
                Map.of("username", "Ada", "token", "enc:v1:contract-envelope"));

        assertThatThrownBy(() -> mapper.map(message, "batch.events.v1.download.job.ready"))
                .isInstanceOf(InvalidDownloadEventException.class)
                .hasMessageContaining("Routing key");
    }

    /**
     * Comprueba que no se admiten objetos o colecciones como parámetros de plantilla.
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
     * Comprueba que los tokens de identidad en texto claro se rechazan: deben llegar en el sobre
     * cifrado.
     */
    @Test
    void rejectsPlaintextIdentityTokens() {
        NotificationRequestedMessage message = message(
                "PASSWORD_RESET",
                Map.of("username", "Ada", "token", "legacy-plaintext-token"));

        assertThatThrownBy(() -> mapper.map(message, ROUTING_KEY))
                .isInstanceOf(InvalidDownloadEventException.class)
                .hasMessageContaining("enc:v1");
    }

    /**
     * Crea un sobre de transporte con plantilla y parámetros controlados para probar la validación.
     *
     * @param template Plantilla de correo elegida por el escenario.
     * @param parameters Parámetros controlados que se validan o renderizan.
     * @return mensaje todavía pendiente de conversión al dominio.
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
