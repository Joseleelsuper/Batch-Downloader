package es.ubu.batchdownloader.downloadworker.application;

import java.time.Duration;

/**
 * Implementa el componente {@code DownloadRejectedException}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class DownloadRejectedException extends RuntimeException {
    /**
     * Estado {@code code} mantenido por {@code DownloadRejectedException}.
     */
    private final String code;
    /** Espera sugerida por el origen para un error transitorio. */
    private final Duration retryAfter;

    /**
     * Inicializa una instancia de {@code DownloadRejectedException}.
     *
     * @param code Valor de {@code code} utilizado por la operación.
     */
    public DownloadRejectedException(String code) {
        super(code);
        this.code = code;
        this.retryAfter = null;
    }

    /**
     * Inicializa una instancia de {@code DownloadRejectedException}.
     *
     * @param code Valor de {@code code} utilizado por la operación.
     * @param cause Valor de {@code cause} utilizado por la operación.
     */
    public DownloadRejectedException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
        this.retryAfter = null;
    }

    /** Inicializa un rechazo transitorio con la espera indicada por Retry-After. */
    public DownloadRejectedException(String code, Duration retryAfter) {
        super(code);
        this.code = code;
        this.retryAfter = retryAfter;
    }

    /**
     * Ejecuta la operación {@code code}.
     *
     * @return Resultado producido por {@code code}.
     */
    public String code() {
        return code;
    }

    /** @return Espera sugerida por el servidor remoto, si existe. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
