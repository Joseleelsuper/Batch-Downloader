package es.ubu.batchdownloader.downloads.application;

import es.ubu.batchdownloader.downloads.domain.DownloadItemStatus;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Representa los datos inmutables de {@code DownloadJobView}.
 *
 * @param id Valor de {@code id} incluido en el record.
 * @param status Valor de {@code status} incluido en el record.
 * @param progress Valor de {@code progress} incluido en el record.
 * @param requestedCount Valor de {@code requestedCount} incluido en el record.
 * @param acceptedCount Valor de {@code acceptedCount} incluido en el record.
 * @param omittedCount Valor de {@code omittedCount} incluido en el record.
 * @param failureCode Valor de {@code failureCode} incluido en el record.
 * @param items Valor de {@code items} incluido en el record.
 * @param createdAt Valor de {@code createdAt} incluido en el record.
 * @param expiresAt Valor de {@code expiresAt} incluido en el record.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
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

    /**
     * Representa los datos inmutables de {@code Item}.
     *
     * @param id Valor de {@code id} incluido en el record.
     * @param appId Valor de {@code appId} incluido en el record.
     * @param appName Valor de {@code appName} incluido en el record.
     * @param officialPageUrl Valor de {@code officialPageUrl} incluido en el record.
     * @param status Valor de {@code status} incluido en el record.
     * @param bytesDownloaded Valor de {@code bytesDownloaded} incluido en el record.
     * @param sha256 Valor de {@code sha256} incluido en el record.
     * @param errorCode Valor de {@code errorCode} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record Item(
            UUID id,
            UUID appId,
            String appName,
            String officialPageUrl,
            DownloadItemStatus status,
            long bytesDownloaded,
            String sha256,
            String errorCode) {}

    /**
     * Ejecuta la operación {@code from}.
     *
     * @param job Trabajo de descarga sobre el que se actúa.
     * @return Resultado producido por {@code from}.
     */
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
