package es.ubu.batchdownloader.downloadworker.application;

/**
 * Distingue una falta temporal o desconocida de capacidad de un fallo definitivo del trabajo para
 * que el mensaje pueda aplazarse y reintentarse.
 *
 * @see es.ubu.batchdownloader.downloadworker.application.ArtifactCapacity
 * @see es.ubu.batchdownloader.downloadworker.application.TemporaryDiskCapacity
 * @see es.ubu.batchdownloader.downloadworker.application.InfrastructureException
 * @since 0.1.0
 * @version 0.1.0
 * @category Capacidad y coordinación de descargas
 */
public final class CapacityDeferredException extends InfrastructureException {
    /**
     * Código de la condición que impidió reservar espacio.
     */
    private final String reason;

    /**
     * Conserva el motivo concreto y la causa bajo el código común storage_busy.
     *
     * @param reason Código que permite distinguir la causa de aplazamiento por capacidad.
     * @param cause Fallo original conservado para diagnóstico y política de reintentos.
     */
    public CapacityDeferredException(String reason, Throwable cause) {
        super("storage_busy", cause);
        this.reason = reason;
    }

    /**
     * Expone el motivo de capacidad para eventos de aplazamiento y métricas.
     *
     * @return código de la condición que impidió reservar espacio.
     */
    public String reason() {
        return reason;
    }
}
