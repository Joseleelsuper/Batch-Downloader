package es.ubu.batchdownloader.downloadworker.application;

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

    /**
     * Inicializa una instancia de {@code DownloadRejectedException}.
     *
     * @param code Valor de {@code code} utilizado por la operación.
     */
    public DownloadRejectedException(String code) {
        super(code);
        this.code = code;
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
