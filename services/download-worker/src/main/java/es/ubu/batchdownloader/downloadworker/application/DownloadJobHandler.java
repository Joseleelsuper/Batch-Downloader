package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;

/** Puerto de entrada para procesar una solicitud de descarga ya deserializada. */
@FunctionalInterface
public interface DownloadJobHandler {

    /**
     * Procesa el evento recibido.
     *
     * @param event evento que se debe procesar.
     */
    void handle(DownloadJobRequestedEvent event);
}
