package es.ubu.batchdownloader.notification.infrastructure.mail;

import es.ubu.batchdownloader.notification.application.port.NotificationSender;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import org.springframework.stereotype.Component;

/** Conserva SMTP para descargas y reserva Resend para identidad. */
@Component
public class RoutingNotificationSender implements NotificationSender {
    private final ResendNotificationSender resend;
    private final SmtpNotificationSender smtp;

    public RoutingNotificationSender(ResendNotificationSender resend, SmtpNotificationSender smtp) {
        this.resend = resend;
        this.smtp = smtp;
    }

    @Override
    public void send(EmailNotification notification) {
        switch (notification.template()) {
            case EMAIL_VERIFICATION, PASSWORD_RESET -> resend.send(notification);
            case DOWNLOAD_READY, DOWNLOAD_FAILED -> smtp.send(notification);
        }
    }
}
