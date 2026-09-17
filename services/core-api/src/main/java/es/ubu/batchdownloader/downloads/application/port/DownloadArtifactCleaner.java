package es.ubu.batchdownloader.downloads.application.port;

import java.util.UUID;

/**
 * Retira los objetos asociados a un trabajo cuya disponibilidad ya se ha revocado en persistencia.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobExpiration
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
public interface DownloadArtifactCleaner {
    /**
     * Elimina artefactos y temporales del trabajo indicado; propaga el fallo para que la expiración
     * gestione sus reintentos.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     */
    void deleteJobArtifacts(UUID jobId);
}
