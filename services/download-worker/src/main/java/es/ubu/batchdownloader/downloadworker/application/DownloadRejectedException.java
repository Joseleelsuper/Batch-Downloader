package es.ubu.batchdownloader.downloadworker.application;

import java.time.Duration;

/**
 * Comunica que una fuente o transferencia incumple una condición de descarga y conserva su código
 * para el resultado individual y la política de reintentos.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadPipeline
 * @see es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader
 * @since 0.1.0
 * @version 0.1.0
 * @category Procesamiento de descargas
 */
public class DownloadRejectedException extends RuntimeException {
    /**
     * Código del rechazo de descarga.
     */
    private final String code;
    /**
     * Duración de espera o null si el rechazo no la proporciona.
     */
    private final Duration retryAfter;

    /**
     * Registra un rechazo sin causa adicional ni demora sugerida.
     *
     * @param code Código estable del rechazo que puede incluirse en el resultado individual.
     */
    public DownloadRejectedException(String code) {
        super(code);
        this.code = code;
        this.retryAfter = null;
    }

    /**
     * Registra el rechazo y su causa sin una demora de reintento sugerida.
     *
     * @param code Código estable del rechazo que puede incluirse en el resultado individual.
     * @param cause Fallo original que explica el rechazo de la descarga.
     */
    public DownloadRejectedException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
        this.retryAfter = null;
    }

    /**
     * Registra el rechazo junto a la espera sugerida por el proveedor.
     *
     * @param code Código estable del rechazo que puede incluirse en el resultado individual.
     * @param retryAfter Duración sugerida por el proveedor antes de reintentar; puede ser null.
     */
    public DownloadRejectedException(String code, Duration retryAfter) {
        super(code);
        this.code = code;
        this.retryAfter = retryAfter;
    }

    /**
     * Expone el diagnóstico estable que se conserva al rechazar un instalador.
     *
     * @return código del rechazo de descarga.
     */
    public String code() {
        return code;
    }

    /**
     * Consulta la demora sugerida antes de otro intento de transferencia.
     *
     * @return duración de espera o null si el rechazo no la proporciona.
     */
    public Duration retryAfter() {
        return retryAfter;
    }
}
