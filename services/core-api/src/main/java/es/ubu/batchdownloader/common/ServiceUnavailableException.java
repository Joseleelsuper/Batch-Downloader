package es.ubu.batchdownloader.common;

/**
 * Implementa el componente {@code ServiceUnavailableException}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class ServiceUnavailableException extends RuntimeException {
    /**
     * Estado {@code code} mantenido por {@code ServiceUnavailableException}.
     */
    private final String code;

    /**
     * Inicializa una instancia de {@code ServiceUnavailableException}.
     *
     * @param code Valor de {@code code} utilizado por la operación.
     * @param message Mensaje que debe procesarse.
     */
    public ServiceUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Ejecuta la operación {@code code}.
     *
     * @return Resultado producido por {@code code}.
     */
    public String code() {
        return code;
    }
}
