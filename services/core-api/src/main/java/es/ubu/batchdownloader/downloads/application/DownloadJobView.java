package es.ubu.batchdownloader.downloads.application;

import es.ubu.batchdownloader.downloads.domain.DownloadItemStatus;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DownloadJobView(
        UUID id,
        DownloadJobStatus status,
        int progress,
        int requestedCount,
        int acceptedCount,
        int omittedCount,
        String failureCode,
        List<Item> items,
        Instant createdAt,
        Instant expiresAt) {

    public record Item(
            UUID id,
            UUID appId,
            String appName,
            String officialPageUrl,
            DownloadItemStatus status,
            long bytesDownloaded,
            String sha256,
            String errorCode) {}

    public static DownloadJobView from(DownloadJob job) {
        return new DownloadJobView(
                job.id(),
                job.status(),
                job.progress(),
                job.requestedCount(),
                job.acceptedCount(),
                job.omittedCount(),
                job.failureCode(),
                job.items().stream().map(item -> new Item(
                        item.id(), item.appId(), item.appName(), item.officialPageUrl(),
                        item.status(), item.bytesDownloaded(), item.sha256(), item.errorCode()))
                        .toList(),
                job.createdAt(),
                job.expiresAt());
    }
}
