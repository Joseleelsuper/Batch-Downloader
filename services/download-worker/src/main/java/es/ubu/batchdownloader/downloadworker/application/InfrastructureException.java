package es.ubu.batchdownloader.downloadworker.application;

/**
 * Implementa el componente {@code InfrastructureException}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class InfrastructureException extends RuntimeException {
    /**
     * Inicializa una instancia de {@code InfrastructureException}.
     *
     * @param message Mensaje que debe procesarse.
     * @param cause Valor de {@code cause} utilizado por la operación.
     */
    public InfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
