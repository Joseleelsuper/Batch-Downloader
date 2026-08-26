package es.ubu.batchdownloader.downloadworker.ports;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import java.nio.file.Path;

/**
 * Define el contrato de {@code RemoteDownloader}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface RemoteDownloader {
    /**
     * Ejecuta la operación {@code download}.
     *
     * @param item Elemento sobre el que se realiza la operación.
     * @param filename Valor de {@code filename} utilizado por la operación.
     * @param target Valor de {@code target} utilizado por la operación.
     * @param totalBudget Valor de {@code totalBudget} utilizado por la operación.
     * @param maxFileBytes Valor de {@code maxFileBytes} utilizado por la operación.
     * @return Resultado producido por {@code download}.
     */
    DownloadedArtifact download(
            ResolvedDownloadItem item,
            String filename,
            Path target,
            DownloadBudget totalBudget,
            long maxFileBytes);
}
