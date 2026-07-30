package es.ubu.batchdownloader.downloads.domain;

/**
 * Enumera los valores admitidos por {@code DownloadJobStatus}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public enum DownloadJobStatus {
    /**
     * Constante que define {@code QUEUED}.
     */
    QUEUED,
    /**
     * Constante que define {@code RESOLVING}.
     */
    RESOLVING,
    /**
     * Constante que define {@code DOWNLOADING}.
     */
    DOWNLOADING,
    /**
     * Constante que define {@code PACKAGING}.
     */
    PACKAGING,
    /**
     * Constante que define {@code READY}.
     */
    READY,
    /**
     * Constante que define {@code PARTIAL}.
     */
    PARTIAL,
    /**
     * Constante que define {@code MANUAL_ONLY}.
     */
    MANUAL_ONLY,
    /**
     * Constante que define {@code FAILED}.
     */
    FAILED,
    /**
     * Constante que define {@code CANCELLED}.
     */
    CANCELLED,
    /**
     * Constante que define {@code EXPIRED}.
     */
    EXPIRED;

    /**
     * Ejecuta la operación {@code terminal}.
     *
     * @return Indica si se cumple la condición evaluada.
     */
    public boolean terminal() {
        return this == READY
                || this == PARTIAL
                || this == MANUAL_ONLY
                || this == FAILED
                || this == CANCELLED
                || this == EXPIRED;
    }

    /**
     * Ejecuta la operación {@code downloadable}.
     *
     * @return Indica si se cumple la condición evaluada.
     */
    public boolean downloadable() {
        return this == READY || this == PARTIAL || this == MANUAL_ONLY;
    }
}
