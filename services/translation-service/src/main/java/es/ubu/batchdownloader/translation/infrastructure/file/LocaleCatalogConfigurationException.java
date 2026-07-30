package es.ubu.batchdownloader.translation.infrastructure.file;

/**
 * Implementa el componente {@code LocaleCatalogConfigurationException}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class LocaleCatalogConfigurationException extends RuntimeException {

    /**
     * Inicializa una instancia de {@code LocaleCatalogConfigurationException}.
     *
     * @param message Mensaje que debe procesarse.
     */
    public LocaleCatalogConfigurationException(String message) {
        super(message);
    }

    /**
     * Inicializa una instancia de {@code LocaleCatalogConfigurationException}.
     *
     * @param message Mensaje que debe procesarse.
     * @param cause Valor de {@code cause} utilizado por la operación.
     */
    public LocaleCatalogConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
