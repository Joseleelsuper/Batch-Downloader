package es.ubu.batchdownloader.notification.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import es.ubu.batchdownloader.notification.domain.EmailNotification;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Comprueba métricas de envío y propagación de fallos sin introducir etiquetas con datos de
 * destinatarios.
 *
 * @see es.ubu.batchdownloader.notification.infrastructure.mail.MeteredNotificationSender
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
class MeteredNotificationSenderTest {

    /**
     * Comprueba que el éxito queda medido por plantilla y resultado sin etiquetas de destinatario
     * ni evento.
     */
    @Test
    void recordsSuccessWithoutRecipientOrEventTags() {
        RoutingNotificationSender delegate = mock(RoutingNotificationSender.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredNotificationSender sender = new MeteredNotificationSender(
                delegate,
                Optional.of(registry));
        EmailNotification notification = notification();

        sender.send(notification);

        verify(delegate).send(notification);
        assertThat(registry.get("notification_send")
                .tags("template", "magic_link", "outcome", "success")
                .timer()
                .count()).isEqualTo(1);
        assertThat(registry.get("notification_send").timer().getId().getTags())
                .noneMatch(tag -> tag.getKey().equals("recipient")
                        || tag.getKey().equals("eventId"));
    }

    /**
     * Comprueba que la instrumentación registra el fallo y vuelve a propagar la misma excepción.
     */
    @Test
    void preservesFailureAndRecordsIt() {
        RoutingNotificationSender delegate = mock(RoutingNotificationSender.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredNotificationSender sender = new MeteredNotificationSender(
                delegate,
                Optional.of(registry));
        EmailNotification notification = notification();
        IllegalStateException failure = new IllegalStateException("mail unavailable");
        doThrow(failure).when(delegate).send(notification);

        assertThatThrownBy(() -> sender.send(notification)).isSameAs(failure);

        assertThat(registry.get("notification_send")
                .tags("template", "magic_link", "outcome", "failure")
                .timer()
                .count()).isEqualTo(1);
    }

    /**
     * Crea una solicitud de correo válida para medir el envío sin contactar con ningún proveedor.
     *
     * @return evento de prueba con destinatario y parámetros locales.
     */
    private EmailNotification notification() {
        return new EmailNotification(
                UUID.randomUUID(),
                Instant.parse("2026-08-22T08:00:00Z"),
                "magic-link-request",
                null,
                "person@example.test",
                "es",
                EmailNotification.Template.MAGIC_LINK,
                Map.of("username", "person", "token", "enc:v1:test", "expiresInMinutes", 15));
    }
}
