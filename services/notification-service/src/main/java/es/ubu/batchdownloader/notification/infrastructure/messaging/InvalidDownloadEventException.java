package es.ubu.batchdownloader.notification.infrastructure.messaging;

/**
 * Implementa el componente {@code InvalidDownloadEventException}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class InvalidDownloadEventException extends RuntimeException {

    /**
     * Inicializa una instancia de {@code InvalidDownloadEventException}.
     *
     * @param message Mensaje que debe procesarse.
     */
    public InvalidDownloadEventException(String message) {
        super(message);
    }

    /**
     * Inicializa una instancia de {@code InvalidDownloadEventException}.
     *
     * @param message Mensaje que debe procesarse.
     * @param cause Valor de {@code cause} utilizado por la operación.
     */
    public InvalidDownloadEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
