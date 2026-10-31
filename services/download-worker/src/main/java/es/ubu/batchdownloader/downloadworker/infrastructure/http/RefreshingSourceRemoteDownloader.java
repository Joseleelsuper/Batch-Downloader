package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import es.ubu.batchdownloader.downloadworker.ports.SourceReferenceResolver;
import java.nio.file.Path;
import java.util.Objects;

/** Renueva una vez la misma fuente ante un enlace temporal caducado (HTTP 618). */
public final class RefreshingSourceRemoteDownloader implements RemoteDownloader {
    private final RemoteDownloader delegate;
    private final SourceReferenceResolver resolver;

    public RefreshingSourceRemoteDownloader(RemoteDownloader delegate, SourceReferenceResolver resolver) {
        this.delegate = delegate;
        this.resolver = resolver;
    }

    @Override
    public DownloadedArtifact download(ResolvedDownloadItem item, String filename, Path target,
            DownloadBudget totalBudget, long maxFileBytes) {
        try {
            return delegate.download(item, filename, target, totalBudget, maxFileBytes);
        } catch (DownloadRejectedException failure) {
            if (!"remote_http_618".equals(failure.code()) || item.sourceRef() == null) throw failure;
            ResolvedDownloadItem fresh = resolver.resolve(
                    new DownloadItemRequest(item.itemId(), item.appId(), item.sourceRef()));
            if (!Objects.equals(item.itemId(), fresh.itemId())
                    || !Objects.equals(item.appId(), fresh.appId())
                    || !Objects.equals(item.sourceRef(), fresh.sourceRef())
                    || !Objects.equals(item.operatingSystem(), fresh.operatingSystem())
                    || !Objects.equals(item.architecture(), fresh.architecture())) {
                throw new DownloadRejectedException("source_resolution_mismatch");
            }
            return delegate.download(fresh, filename, target, totalBudget, maxFileBytes);
        }
    }
}
