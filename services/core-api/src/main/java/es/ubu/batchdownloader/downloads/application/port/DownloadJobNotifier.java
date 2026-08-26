package es.ubu.batchdownloader.downloads.application.port;

import es.ubu.batchdownloader.downloads.application.DownloadJobView;

/**
 * Define el contrato de {@code DownloadJobNotifier}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface DownloadJobNotifier {
    /**
     * Ejecuta la operación {@code changed}.
     *
     * @param job Trabajo de descarga sobre el que se actúa.
     */
    void changed(DownloadJobView job);
}
