package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import java.nio.file.Path;

/**
 * Comprueba el SHA-256 esperado después de completar la transferencia sin repetir la lectura del
 * archivo.
 *
 * @see es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.PartialFileCleanupRemoteDownloader
 * @since 0.1.0
 * @version 0.1.0
 * @category Transporte de descargas
 */
public final class IntegrityCheckingRemoteDownloader implements RemoteDownloader {
    private final RemoteDownloader delegate;

    /**
     * Conecta la transferencia que calcula la huella real del archivo.
     *
     * @param delegate Siguiente política o transporte de la cadena de descarga.
     */
    public IntegrityCheckingRemoteDownloader(RemoteDownloader delegate) {
        this.delegate = delegate;
    }

    /**
     * Compara la huella calculada con la esperada cuando existe, sin distinguir mayúsculas.
     *
     * @param item Elemento admitido o resuelto cuya fuente exacta se procesa.
     * @param filename Nombre seguro y deduplicado asignado al instalador descargado.
     * @param target Ruta local de destino del instalador.
     * @param totalBudget Presupuesto compartido de bytes del trabajo, consumido durante la
     *     transferencia.
     * @param maxFileBytes Límite máximo permitido para este archivo, en bytes.
     * @return artefacto íntegro o sin huella esperada para comparar.
     * @throws es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException si no
     *     coincide SHA-256, con código source_sha256_mismatch.
     */
    @Override
    public DownloadedArtifact download(
            ResolvedDownloadItem item,
            String filename,
            Path target,
            DownloadBudget totalBudget,
            long maxFileBytes) {
        DownloadedArtifact artifact = delegate.download(
                item,
                filename,
                target,
                totalBudget,
                maxFileBytes);
        if (item.expectedSha256() != null
                && !artifact.sha256().equalsIgnoreCase(item.expectedSha256())) {
            throw new DownloadRejectedException("source_sha256_mismatch");
        }
        return artifact;
    }
}
