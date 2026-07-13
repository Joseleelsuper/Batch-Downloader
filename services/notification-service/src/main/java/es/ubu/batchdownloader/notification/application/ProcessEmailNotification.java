package es.ubu.batchdownloader.notification.application;

import es.ubu.batchdownloader.notification.application.port.NotificationInbox;
import es.ubu.batchdownloader.notification.application.port.NotificationSender;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProcessEmailNotification {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessEmailNotification.class);

    private final NotificationInbox inbox;
    private final NotificationSender sender;

    public ProcessEmailNotification(NotificationInbox inbox, NotificationSender sender) {
        this.inbox = inbox;
        this.sender = sender;
    }

    public void execute(EmailNotification notification) {
        NotificationInbox.ClaimResult claim = inbox.claim(notification.eventId(), notification.eventType());
        if (claim == NotificationInbox.ClaimResult.ALREADY_PROCESSED) {
            LOGGER.info("Evento de notificación duplicado ignorado: eventId={}", notification.eventId());
            return;
        }
        if (claim == NotificationInbox.ClaimResult.BUSY) {
            throw new NotificationProcessingException(
                    "El evento ya está siendo procesado: " + notification.eventId());
        }

        try {
            sender.send(notification);
            inbox.markProcessed(notification.eventId());
            LOGGER.info(
                    "Notificación enviada: eventId={}, correlationId={}, template={}",
                    notification.eventId(),
                    notification.correlationId(),
                    notification.template());
        } catch (RuntimeException exception) {
            markFailure(notification, exception);
            throw new NotificationProcessingException(
                    "No se pudo enviar la notificación del evento " + notification.eventId(), exception);
        }
    }

    private void markFailure(EmailNotification notification, RuntimeException originalException) {
        try {
            inbox.markFailed(notification.eventId(), errorDescription(originalException));
        } catch (RuntimeException inboxException) {
            originalException.addSuppressed(inboxException);
        }
    }

    private String errorDescription(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
