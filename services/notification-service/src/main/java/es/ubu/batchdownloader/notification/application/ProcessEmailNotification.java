package es.ubu.batchdownloader.notification.application;

import es.ubu.batchdownloader.notification.application.port.NotificationInbox;
import es.ubu.batchdownloader.notification.application.port.NotificationSender;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Implementa el componente {@code ProcessEmailNotification}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Service
public class ProcessEmailNotification implements NotificationHandler {

    /**
     * Constante que define {@code LOGGER}.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessEmailNotification.class);

    /**
     * Estado {@code inbox} mantenido por {@code ProcessEmailNotification}.
     */
    private final NotificationInbox inbox;
    /**
     * Estado {@code sender} mantenido por {@code ProcessEmailNotification}.
     */
    private final NotificationSender sender;

    /**
     * Inicializa una instancia de {@code ProcessEmailNotification}.
     *
     * @param inbox Valor de {@code inbox} utilizado por la operación.
     * @param sender Valor de {@code sender} utilizado por la operación.
     */
    public ProcessEmailNotification(NotificationInbox inbox, NotificationSender sender) {
        this.inbox = inbox;
        this.sender = sender;
    }

    /**
     * Ejecuta la operación {@code execute}.
     *
     * @param notification Valor de {@code notification} utilizado por la operación.
     * @throws NotificationProcessingException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    public void execute(EmailNotification notification) {
        handle(notification);
    }

    /** {@inheritDoc} */
    @Override
    public void handle(EmailNotification notification) {
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
            if (exception instanceof PermanentNotificationException permanent) {
                throw permanent;
            }
            if (exception instanceof RetryableNotificationException retryable) {
                throw retryable;
            }
            throw new NotificationProcessingException(
                    "No se pudo enviar la notificación del evento " + notification.eventId(), exception);
        }
    }

    /**
     * Marca el recurso solicitado mediante {@code markFailure}.
     *
     * @param notification Valor de {@code notification} utilizado por la operación.
     * @param originalException Valor de {@code originalException} utilizado por la operación.
     */
    private void markFailure(EmailNotification notification, RuntimeException originalException) {
        try {
            inbox.markFailed(notification.eventId(), errorDescription(originalException));
        } catch (RuntimeException inboxException) {
            originalException.addSuppressed(inboxException);
        }
    }

    /**
     * Ejecuta la operación {@code errorDescription}.
     *
     * @param exception Valor de {@code exception} utilizado por la operación.
     * @return Resultado producido por {@code errorDescription}.
     */
    private String errorDescription(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
