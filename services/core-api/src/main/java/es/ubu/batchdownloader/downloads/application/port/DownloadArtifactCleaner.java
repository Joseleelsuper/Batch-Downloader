package es.ubu.batchdownloader.downloads.application.port;

import java.util.UUID;

/**
 * Define el contrato de {@code DownloadArtifactCleaner}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface DownloadArtifactCleaner {
    /**
     * Elimina el recurso solicitado mediante {@code deleteJobArtifacts}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     */
    void deleteJobArtifacts(UUID jobId);
}
