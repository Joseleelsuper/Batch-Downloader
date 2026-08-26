package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.file.Path;

/** Registra duración y resultado sin introducir etiquetas de alta cardinalidad. */
public final class MeteredRemoteDownloader implements RemoteDownloader {
    private final RemoteDownloader delegate;
    private final MeterRegistry registry;

    /** Inicializa el wrapper de métricas. */
    public MeteredRemoteDownloader(RemoteDownloader delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    /** {@inheritDoc} */
    @Override
    public DownloadedArtifact download(
            ResolvedDownloadItem item,
            String filename,
            Path target,
            DownloadBudget totalBudget,
            long maxFileBytes) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            return delegate.download(item, filename, target, totalBudget, maxFileBytes);
        } catch (DownloadRejectedException exception) {
            outcome = "rejected";
            throw exception;
        } catch (RuntimeException exception) {
            outcome = "failure";
            throw exception;
        } finally {
            sample.stop(Timer.builder("download_worker_remote_download")
                    .tag("outcome", outcome)
                    .register(registry));
        }
    }
}
