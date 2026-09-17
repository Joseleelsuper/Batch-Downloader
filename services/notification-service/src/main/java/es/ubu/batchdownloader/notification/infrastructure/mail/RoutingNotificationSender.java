package es.ubu.batchdownloader.notification.infrastructure.mail;

import es.ubu.batchdownloader.notification.application.port.NotificationSender;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import org.springframework.stereotype.Component;

/**
 * Selecciona Resend para identidad y SMTP para resultados de descarga según la plantilla del
 * evento.
 *
 * @see es.ubu.batchdownloader.notification.application.port.NotificationSender
 * @see es.ubu.batchdownloader.notification.infrastructure.mail.ResendNotificationSender
 * @see es.ubu.batchdownloader.notification.infrastructure.mail.SmtpNotificationSender
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@Component
public class RoutingNotificationSender implements NotificationSender {
    private final ResendNotificationSender resend;
    private final SmtpNotificationSender smtp;

    /**
     * Asocia cada familia de plantillas con su proveedor configurado.
     *
     * @param resend Proveedor HTTP de correos de verificación y restablecimiento de contraseña.
     * @param smtp Proveedor SMTP de avisos sobre descargas.
     */
    public RoutingNotificationSender(ResendNotificationSender resend, SmtpNotificationSender smtp) {
        this.resend = resend;
        this.smtp = smtp;
    }

    /**
     * Entrega verificaciones y restablecimientos a Resend, y avisos de descarga a SMTP; propaga los
     * errores del proveedor.
     *
     * @param notification Evento validado, con destinatario, plantilla y parámetros necesarios para
     *     el envío.
     */
    @Override
    public void send(EmailNotification notification) {
        switch (notification.template()) {
            case EMAIL_VERIFICATION, PASSWORD_RESET -> resend.send(notification);
            case DOWNLOAD_READY, DOWNLOAD_FAILED -> smtp.send(notification);
        }
    }
}
