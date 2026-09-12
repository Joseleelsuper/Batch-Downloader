package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;

/**
 * Recibe una solicitud de descarga para componer validación, deduplicación y procesamiento sin
 * acoplar el listener al procesador concreto.
 *
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @since 0.1.0
 * @version 0.1.0
 * @category Procesamiento de descargas
 */
@FunctionalInterface
public interface DownloadJobHandler {

    /**
     * Gestiona la solicitud según la política de esta etapa y propaga los fallos que debe
     * clasificar el consumidor.
     *
     * @param event Solicitud validada con identidad del trabajo, selección exacta y correlación de
     *     eventos.
     */
    void handle(DownloadJobRequestedEvent event);
}
