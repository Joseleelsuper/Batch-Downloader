package es.ubu.batchdownloader.downloads.application.port;

import es.ubu.batchdownloader.downloads.domain.DownloadJob;

/**
 * Registra las solicitudes de procesamiento y cancelación que deben sobrevivir al commit del
 * trabajo.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobService
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobNotifications
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
public interface DownloadEventPublisher {
    /**
     * Publica la selección admitida para que el worker descargue exactamente los elementos
     * persistidos.
     *
     * @param job Agregado o vista persistida del trabajo cuya identidad y estado se procesan.
     */
    void jobRequested(DownloadJob job);
    /**
     * Solicita al worker detener cooperativamente el trabajo y liberar sus recursos.
     *
     * @param job Agregado o vista persistida del trabajo cuya identidad y estado se procesan.
     */
    void cancellationRequested(DownloadJob job);
}
