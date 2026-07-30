package es.ubu.batchdownloader.downloads.application.port;

import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.identity.domain.UserAccount;

/**
 * Define el contrato de {@code DownloadEventPublisher}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface DownloadEventPublisher {
    /**
     * Ejecuta la operación {@code jobRequested}.
     *
     * @param job Trabajo de descarga sobre el que se actúa.
     */
    void jobRequested(DownloadJob job);
    /**
     * Indica si puede realizarse la operación mediante {@code cancellationRequested}.
     *
     * @param job Trabajo de descarga sobre el que se actúa.
     */
    void cancellationRequested(DownloadJob job);
    /**
     * Ejecuta la operación {@code terminalNotificationRequested}.
     *
     * @param owner Valor de {@code owner} utilizado por la operación.
     * @param job Trabajo de descarga sobre el que se actúa.
     */
    void terminalNotificationRequested(UserAccount owner, DownloadJob job);
}
