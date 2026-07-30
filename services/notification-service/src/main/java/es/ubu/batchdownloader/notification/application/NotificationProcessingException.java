package es.ubu.batchdownloader.notification.application;

/**
 * Implementa el componente {@code NotificationProcessingException}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class NotificationProcessingException extends RuntimeException {

    /**
     * Inicializa una instancia de {@code NotificationProcessingException}.
     *
     * @param message Mensaje que debe procesarse.
     */
    public NotificationProcessingException(String message) {
        super(message);
    }

    /**
     * Inicializa una instancia de {@code NotificationProcessingException}.
     *
     * @param message Mensaje que debe procesarse.
     * @param cause Valor de {@code cause} utilizado por la operación.
     */
    public NotificationProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
