package es.ubu.batchdownloader.common;

/**
 * Implementa el componente {@code RateLimitException}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class RateLimitException extends RuntimeException {
    /**
     * Estado {@code code} mantenido por {@code RateLimitException}.
     */
    private final String code;
    /** Segundos que debe esperar el cliente antes de reintentar. */
    private final int retryAfterSeconds;

    /**
     * Inicializa una instancia de {@code RateLimitException}.
     *
     * @param code Valor de {@code code} utilizado por la operación.
     * @param message Mensaje que debe procesarse.
     */
    public RateLimitException(String code, String message) {
        this(code, message, 60);
    }

    /**
     * Inicializa un límite con una espera explícita.
     *
     * @param code Código estable de la respuesta.
     * @param message Mensaje seguro para el cliente.
     * @param retryAfterSeconds Segundos mínimos antes del reintento.
     */
    public RateLimitException(String code, String message, int retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /**
     * Ejecuta la operación {@code code}.
     *
     * @return Resultado producido por {@code code}.
     */
    public String code() {
        return code;
    }

    /**
     * Obtiene el valor de la cabecera {@code Retry-After}.
     *
     * @return Segundos mínimos antes del reintento.
     */
    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
