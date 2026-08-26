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

/** Elimina el fichero parcial cuando cualquier capa interna rechaza o falla la descarga. */
public final class PartialFileCleanupRemoteDownloader implements RemoteDownloader {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            PartialFileCleanupRemoteDownloader.class);
    private final RemoteDownloader delegate;

    /** Inicializa el wrapper de cleanup. */
    public PartialFileCleanupRemoteDownloader(RemoteDownloader delegate) {
        this.delegate = delegate;
    }

    /** {@inheritDoc} */
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
