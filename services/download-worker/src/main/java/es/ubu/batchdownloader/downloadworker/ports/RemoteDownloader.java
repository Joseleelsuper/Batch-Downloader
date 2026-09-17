package es.ubu.batchdownloader.downloadworker.ports;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import java.nio.file.Path;

/**
 * Transfiere un instalador resuelto a un archivo local aplicando límites e integridad y conserva la
 * identidad exacta de su fuente.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.ports.SourceReferenceResolver
 * @see es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact
 * @since 0.1.0
 * @version 0.1.0
 * @category Puertos del worker
 */
public interface RemoteDownloader {
    /**
     * Descarga el elemento al destino local dentro del presupuesto total y límite por archivo y
     * devuelve su identidad e integridad comprobadas.
     *
     * @param item Elemento admitido o resuelto cuya fuente exacta se procesa.
     * @param filename Nombre seguro y deduplicado asignado al instalador descargado.
     * @param target Ruta local de destino del instalador.
     * @param totalBudget Presupuesto compartido de bytes del trabajo, consumido durante la
     *     transferencia.
     * @param maxFileBytes Límite máximo permitido para este archivo, en bytes.
     * @return artefacto local completo listo para almacenar y archivar.
     */
    DownloadedArtifact download(
            ResolvedDownloadItem item,
            String filename,
            Path target,
            DownloadBudget totalBudget,
            long maxFileBytes);
}
