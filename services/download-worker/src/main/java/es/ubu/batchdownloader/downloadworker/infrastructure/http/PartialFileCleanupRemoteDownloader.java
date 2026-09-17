package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compensa transferencias fallidas intentando borrar el destino parcial sin ocultar el fallo que
 * causó la descarga.
 *
 * @see es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.IntegrityCheckingRemoteDownloader
 * @since 0.1.0
 * @version 0.1.0
 * @category Transporte de descargas
 */
public final class PartialFileCleanupRemoteDownloader implements RemoteDownloader {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            PartialFileCleanupRemoteDownloader.class);
    private final RemoteDownloader delegate;

    /**
     * Conecta las políticas cuyo fallo requiere limpiar el archivo local.
     *
     * @param delegate Siguiente política o transporte de la cadena de descarga.
     */
    public PartialFileCleanupRemoteDownloader(RemoteDownloader delegate) {
        this.delegate = delegate;
    }

    /**
     * Delega y conserva archivos satisfactorios; ante RuntimeException intenta borrar el destino y
     * añade cualquier fallo de limpieza como causa suprimida.
     *
     * @param item Elemento admitido o resuelto cuya fuente exacta se procesa.
     * @param filename Nombre seguro y deduplicado asignado al instalador descargado.
     * @param target Ruta local de destino del instalador.
     * @param totalBudget Presupuesto compartido de bytes del trabajo, consumido durante la
     *     transferencia.
     * @param maxFileBytes Límite máximo permitido para este archivo, en bytes.
     * @return artefacto terminado de la siguiente política.
     */
    @Override
    public DownloadedArtifact download(
            ResolvedDownloadItem item,
            String filename,
            Path target,
            DownloadBudget totalBudget,
            long maxFileBytes) {
        try {
            return delegate.download(item, filename, target, totalBudget, maxFileBytes);
        } catch (RuntimeException exception) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
                LOGGER.debug("Could not delete partial download {}", target, cleanupFailure);
            }
            throw exception;
        }
    }
}
