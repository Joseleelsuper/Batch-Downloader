package es.ubu.batchdownloader.downloads.domain;

/**
 * Distingue el progreso de un instalador de su resultado terminal para mantener resultados
 * parciales del lote.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.domain.DownloadJobItem
 * @see es.ubu.batchdownloader.downloads.domain.DownloadJobStatus
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
public enum DownloadItemStatus {
    /**
     * Espera capacidad o reserva para comenzar.
     */
    QUEUED,
    /**
     * Resuelve y revalida la fuente antes de transferir bytes.
     */
    RESOLVING,
    /**
     * Transfiere los instaladores admitidos.
     */
    DOWNLOADING,
    /**
     * El instalador o acceso manual del elemento se completó.
     */
    COMPLETED,
    /**
     * El procesamiento terminó con fallo.
     */
    FAILED,
    /**
     * El solicitante canceló el procesamiento.
     */
    CANCELLED;

    /**
     * Identifica elementos que ya terminaron y no deben aceptar eventos posteriores de progreso.
     *
     * @return true para COMPLETED, FAILED o CANCELLED.
     */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
