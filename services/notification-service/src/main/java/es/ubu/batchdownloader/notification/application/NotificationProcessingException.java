package es.ubu.batchdownloader.notification.application;

/**
 * Propaga al consumidor de correo un fallo que impide completar un evento.
 *
 * <p>Se utiliza cuando otro consumidor mantiene ocupada la reserva del inbox o se
 * produce un fallo no clasificado durante el envío o la confirmación del procesamiento.
 * Conserva la causa disponible para que la política del listener gestione el reintento.</p>
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see ProcessEmailNotification
 * @see es.ubu.batchdownloader.notification.application.port.NotificationInbox
 * @see RetryableNotificationException
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
public class NotificationProcessingException extends RuntimeException {

    /**
     * Propaga la condición que impide completar el evento, conservando la causa cuando está
     * disponible.
     *
     * @param message Descripción de la condición que impide completar el procesamiento.
     */
    public NotificationProcessingException(String message) {
        super(message);
    }

    /**
     * Propaga la condición que impide completar el evento, conservando la causa cuando está
     * disponible.
     *
     * @param message Descripción de la condición que impide completar el procesamiento.
     * @param cause Causa original que debe conservarse para clasificar o diagnosticar el fallo.
     */
    public NotificationProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
