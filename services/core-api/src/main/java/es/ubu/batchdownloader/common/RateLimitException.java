package es.ubu.batchdownloader.common;

/**
 * Comunica agotamiento de una cuota de solicitudes e incorpora la espera que el cliente debe
 * respetar antes de reintentar.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.common.ApiExceptionHandler
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
public class RateLimitException extends RuntimeException {
    /**
     * Código estable y seguro que permite al cliente identificar el fallo.
     */
    private final String code;
    /**
     * Segundos antes del siguiente intento; se limita por abajo a uno.
     */
    private final int retryAfterSeconds;

    /**
     * Conserva el fallo de cuota con una espera predeterminada de sesenta segundos.
     *
     * @param code Código estable y seguro que permite al cliente identificar el fallo.
     * @param message Explicación del fallo apta para mostrarse al usuario, sin detalles sensibles.
     */
    public RateLimitException(String code, String message) {
        this(code, message, 60);
    }

    /**
     * Conserva el código funcional y el mensaje seguro del fallo y acota el plazo de reintento a un
     * mínimo de un segundo.
     *
     * @param code Código estable y seguro que permite al cliente identificar el fallo.
     * @param message Explicación del fallo apta para mostrarse al usuario, sin detalles sensibles.
     * @param retryAfterSeconds Segundos antes del siguiente intento; se limita por abajo a uno.
     */
    public RateLimitException(String code, String message, int retryAfterSeconds) {
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
