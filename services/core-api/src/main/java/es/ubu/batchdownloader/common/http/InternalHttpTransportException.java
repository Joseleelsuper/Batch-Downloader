package es.ubu.batchdownloader.common.http;

/** Fallo técnico del transporte que cada cliente traduce a su contrato público. */
public final class InternalHttpTransportException extends RuntimeException {
    private final boolean interrupted;

    /** Inicializa un fallo de I/O o interrupción sin exponer datos de la petición. */
    public InternalHttpTransportException(boolean interrupted, Throwable cause) {
        super(interrupted ? "internal_http_interrupted" : "internal_http_io_error", cause);
        this.interrupted = interrupted;
    }

    /** @return si la espera fue interrumpida. */
    public boolean interrupted() {
        return interrupted;
    }
}
