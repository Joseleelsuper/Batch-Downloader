package es.ubu.batchdownloader.notification.infrastructure.mail;

import static org.mockito.Mockito.verify;

import es.ubu.batchdownloader.notification.domain.EmailNotification;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Comprueba el envío de los correos de acceso mediante el proveedor configurado.
 *
 * @see es.ubu.batchdownloader.notification.infrastructure.mail.RoutingNotificationSender
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
class RoutingNotificationSenderTest {
    /**
     * Comprueba que los enlaces mágicos de acceso se envían únicamente mediante Resend.
     */
    @Test
    void routesOnlyAuthenticationMailThroughResend() {
        ResendNotificationSender resend = Mockito.mock(ResendNotificationSender.class);
        RoutingNotificationSender routing = new RoutingNotificationSender(resend);
        EmailNotification magicLink = notification(EmailNotification.Template.MAGIC_LINK);

        routing.send(magicLink);

        verify(resend).send(magicLink);
    }

    /**
     * Crea una solicitud con la plantilla elegida para comprobar el enrutamiento sin contactar con
     * proveedores.
     *
     * @param template Plantilla de correo elegida por el escenario.
     * @return evento de dominio utilizado por el escenario de selección.
     */
    private static EmailNotification notification(EmailNotification.Template template) {
        return new EmailNotification(
                UUID.randomUUID(), Instant.parse("2026-08-08T10:00:00Z"),
                UUID.randomUUID().toString(), null, "person@example.com", template,
                Map.of("username", "person", "token", "encrypted-token"));
    }
}
