package es.ubu.batchdownloader.notification.infrastructure.mail;

import es.ubu.batchdownloader.notification.application.port.NotificationSender;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import org.springframework.stereotype.Component;

/**
 * Entrega los correos de acceso al proveedor Resend.
 *
 * @see es.ubu.batchdownloader.notification.application.port.NotificationSender
 * @see es.ubu.batchdownloader.notification.infrastructure.mail.ResendNotificationSender
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@Component
public class RoutingNotificationSender implements NotificationSender {
    private final ResendNotificationSender resend;

    /**
     * Asocia el proveedor HTTP de enlaces mágicos.
     *
     * @param resend Proveedor HTTP de enlaces mágicos de acceso.
     */
    public RoutingNotificationSender(ResendNotificationSender resend) {
        this.resend = resend;
    }

    /**
     * Entrega el enlace mágico a Resend y propaga los errores del proveedor.
     *
     * @param notification Evento validado, con destinatario, plantilla y parámetros necesarios para
     *     el envío.
     */
    @Override
    public void send(EmailNotification notification) {
        resend.send(notification);
    }
}
