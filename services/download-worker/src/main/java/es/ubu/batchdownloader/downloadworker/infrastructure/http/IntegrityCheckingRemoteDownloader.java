package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import java.nio.file.Path;

/** Comprueba la expectativa de integridad después de producir el artefacto funcional. */
public final class IntegrityCheckingRemoteDownloader implements RemoteDownloader {
    private final RemoteDownloader delegate;

    /** Inicializa el wrapper de integridad. */
    public IntegrityCheckingRemoteDownloader(RemoteDownloader delegate) {
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
