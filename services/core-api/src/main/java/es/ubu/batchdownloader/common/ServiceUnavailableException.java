package es.ubu.batchdownloader.common;

/**
 * Representa falta temporal de capacidad o disponibilidad de un colaborador y conserva un plazo
 * seguro de reintento.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.common.ApiExceptionHandler
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
public class ServiceUnavailableException extends RuntimeException {
    /**
     * Código estable y seguro que permite al cliente identificar el fallo.
     */
    private final String code;
    /**
     * Segundos antes del siguiente intento; se limita por abajo a uno.
     */
    private final int retryAfterSeconds;

    /**
     * Conserva el fallo temporal con una espera predeterminada de un segundo.
     *
     * @param code Código estable y seguro que permite al cliente identificar el fallo.
     * @param message Explicación del fallo apta para mostrarse al usuario, sin detalles sensibles.
     */
    public ServiceUnavailableException(String code, String message) {
        this(code, message, 1);
    }

    /**
     * Conserva el código funcional y el mensaje seguro del fallo y acota el plazo de reintento a un
     * mínimo de un segundo.
     *
     * @param code Código estable y seguro que permite al cliente identificar el fallo.
     * @param message Explicación del fallo apta para mostrarse al usuario, sin detalles sensibles.
     * @param retryAfterSeconds Segundos antes del siguiente intento; se limita por abajo a uno.
     */
    public ServiceUnavailableException(String code, String message, int retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /**
     * Identifica el fallo para que el cliente decida cómo presentarlo o recuperarse.
     *
     * @return código funcional estable.
     */
    public String code() {
        return code;
    }

    /**
     * Expone el plazo que se enviará en Retry-After.
     *
     * @return segundos de espera, siempre al menos uno.
     */
    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
