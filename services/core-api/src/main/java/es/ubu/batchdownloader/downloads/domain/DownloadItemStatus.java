package es.ubu.batchdownloader.downloads.domain;

/**
 * Enumera los valores admitidos por {@code DownloadItemStatus}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public enum DownloadItemStatus {
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
     * Constante que define {@code COMPLETED}.
     */
    COMPLETED,
    /**
     * Constante que define {@code FAILED}.
     */
    FAILED,
    /**
     * Constante que define {@code CANCELLED}.
     */
    CANCELLED;

    /**
     * Ejecuta la operación {@code terminal}.
     *
     * @return Indica si se cumple la condición evaluada.
     */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
