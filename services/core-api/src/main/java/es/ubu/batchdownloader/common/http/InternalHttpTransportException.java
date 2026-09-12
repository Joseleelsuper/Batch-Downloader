package es.ubu.batchdownloader.common.http;

/**
 * Conserva la causa de un fallo HTTP sin respuesta y distingue interrupción cooperativa de error de
 * entrada o salida.
 *
 * @see es.ubu.batchdownloader.common.http.InternalHttpExecutor
 * @see es.ubu.batchdownloader.common.http.JdkInternalHttpExecutor
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
public final class InternalHttpTransportException extends RuntimeException {
    /**
     * True para interrupción; false para fallo de entrada o salida.
     */
    private final boolean interrupted;

    /**
     * Clasifica el fallo de transporte sin incorporar URI, credenciales ni contenido a su mensaje.
     *
     * @param interrupted El hilo solicitante fue interrumpido durante el transporte y conserva esa
     *     marca.
     * @param cause Causa original del fallo de transporte para diagnóstico interno.
     */
    public InternalHttpTransportException(boolean interrupted, Throwable cause) {
        super(interrupted ? "internal_http_interrupted" : "internal_http_io_error", cause);
        this.interrupted = interrupted;
    }

    /**
     * Indica si el transporte terminó porque se interrumpió el hilo solicitante.
     *
     * @return true para interrupción; false para fallo de entrada o salida.
     */
    public boolean interrupted() {
        return interrupted;
    }
}
