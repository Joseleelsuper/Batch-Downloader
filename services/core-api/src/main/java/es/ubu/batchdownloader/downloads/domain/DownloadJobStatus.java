package es.ubu.batchdownloader.downloads.domain;

/**
 * Representa admisión, transferencia, empaquetado y resultado global del ZIP; un resultado parcial
 * o manual puede descargarse.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.domain.DownloadJob
 * @see es.ubu.batchdownloader.downloads.domain.DownloadItemStatus
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
public enum DownloadJobStatus {
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
     * Escribe el ZIP tras terminar todos los elementos.
     */
    PACKAGING,
    /**
     * El ZIP está completo y disponible.
     */
    READY,
    /**
     * El ZIP contiene resultados utilizables junto con elementos fallidos.
     */
    PARTIAL,
    /**
     * El ZIP contiene únicamente alternativas manuales.
     */
    MANUAL_ONLY,
    /**
     * El procesamiento terminó con fallo.
     */
    FAILED,
    /**
     * El solicitante canceló el procesamiento.
     */
    CANCELLED,
    /**
     * Terminó la vigencia del ZIP y se retiró su clave de entrega.
     */
    EXPIRED;

    /**
     * Identifica trabajos cuyo resultado impide aplicar más progreso ordinario.
     *
     * @return true para READY, PARTIAL, MANUAL_ONLY, FAILED, CANCELLED o EXPIRED.
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
     * Distingue resultados que pueden conservar un ZIP utilizable, incluso con instaladores
     * omitidos o accesos manuales.
     *
     * @return true para READY, PARTIAL o MANUAL_ONLY.
     */
    public boolean downloadable() {
        return this == READY || this == PARTIAL || this == MANUAL_ONLY;
    }
}
