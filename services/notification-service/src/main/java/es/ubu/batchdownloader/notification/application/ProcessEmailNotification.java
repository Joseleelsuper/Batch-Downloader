package es.ubu.batchdownloader.notification.application;

import es.ubu.batchdownloader.notification.application.port.NotificationInbox;
import es.ubu.batchdownloader.notification.application.port.NotificationSender;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reserva cada evento de correo, realiza el envío y confirma su resultado en el inbox.
 *
 * <p>Ignora eventos completados, permite reintentar reservas ocupadas y conserva la
 * clasificación de fallos permanentes o temporales del proveedor. Un fallo al registrar
 * el error se añade como excepción suprimida sin sustituir la causa original.</p>
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see NotificationHandler
 * @see NotificationInbox
 * @see NotificationSender
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@Service
public class ProcessEmailNotification implements NotificationHandler {

    /**
     * Registra identificadores y resultado del procesamiento, sin destinatarios ni parámetros de
     * plantilla.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessEmailNotification.class);

    /**
     * Reserva y confirma eventos para controlar entregas repetidas.
     */
    private final NotificationInbox inbox;
    /**
     * Realiza el envío y conserva la clasificación de errores del proveedor.
     */
    private final NotificationSender sender;

    /**
     * Asocia la reserva idempotente de eventos con el puerto de envío de correo.
     *
     * @param inbox Registro persistente de reservas, intentos y confirmaciones por evento.
     * @param sender Puerto que selecciona el proveedor y realiza el envío.
     */
    public ProcessEmailNotification(NotificationInbox inbox, NotificationSender sender) {
        this.inbox = inbox;
        this.sender = sender;
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
     * Registra el fallo en el inbox; si ese registro también falla, añade la excepción como
     * suprimida a la original.
     *
     * @param notification Evento validado, con destinatario, plantilla y parámetros necesarios para
     *     el envío.
     *
     * @param originalException Fallo de envío o confirmación cuya identidad y causa deben
     *     conservarse.
     */
    private void markFailure(EmailNotification notification, RuntimeException originalException) {
        try {
            inbox.markFailed(notification.eventId(), errorDescription(originalException));
        } catch (RuntimeException inboxException) {
            originalException.addSuppressed(inboxException);
        }
    }

    /**
     * Obtiene una descripción persistible del fallo sin perder los errores que no tienen mensaje.
     *
     * @param exception Fallo que se describe o cuya cadena de causas se examina.
     * @return mensaje original no vacío, o nombre simple de la excepción.
     */
    private String errorDescription(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
