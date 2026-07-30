package es.ubu.batchdownloader.common;

/**
 * Implementa el componente {@code UnauthorizedException}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class UnauthorizedException extends RuntimeException {
    /**
     * Estado {@code code} mantenido por {@code UnauthorizedException}.
     */
    private final String code;

    /**
     * Inicializa una instancia de {@code UnauthorizedException}.
     *
     * @param code Valor de {@code code} utilizado por la operación.
     * @param message Mensaje que debe procesarse.
     */
    public UnauthorizedException(String code, String message) {
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
