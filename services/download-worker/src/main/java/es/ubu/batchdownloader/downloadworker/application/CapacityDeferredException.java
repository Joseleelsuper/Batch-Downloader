package es.ubu.batchdownloader.downloadworker.application;

/** Señala una falta temporal de capacidad que debe volver a la cola sin consumir reintentos. */
public final class CapacityDeferredException extends InfrastructureException {
    private final String reason;

    /** Inicializa una espera de capacidad con una causa comprobable. */
    public CapacityDeferredException(String reason, Throwable cause) {
        super("storage_busy", cause);
        this.reason = reason;
    }

    /** @return Motivo estable que se publica en el contrato del trabajo. */
    public String reason() {
        return reason;
    }
}
