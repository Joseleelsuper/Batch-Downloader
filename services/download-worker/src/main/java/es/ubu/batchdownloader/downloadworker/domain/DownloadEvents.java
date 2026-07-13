package es.ubu.batchdownloader.downloadworker.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DownloadEvents {
    private DownloadEvents() {
    }

    public record DownloadJobRequestedEvent(
            @NotNull UUID eventId,
            @NotBlank String type,
            @Positive int schemaVersion,
            @NotNull Instant occurredAt,
            @NotBlank String correlationId,
            String causationId,
            @NotNull @Valid DownloadJobPayload payload) {
    }

    public record DownloadJobPayload(
            @NotNull UUID jobId,
            @NotEmpty @Size(max = 100) List<@Valid DownloadItemRequest> items,
            @NotNull @Valid DownloadLimits limits) {
    }

    public record DownloadItemRequest(
            @NotNull UUID itemId,
            @NotNull UUID appId,
            @NotNull UUID sourceRef,
            String operatingSystem,
            String architecture) {
    }

    public record DownloadLimits(
            @Min(1) @Max(4_294_967_296L) long maxFileBytes,
            @Min(1) @Max(21_474_836_480L) long maxJobBytes,
            @Min(1) @Max(8) int maxParallelDownloads) {
    }

    public record DownloadJobProgressedEvent(
            UUID eventId,
            String type,
            int schemaVersion,
            Instant occurredAt,
            String correlationId,
            String causationId,
            DownloadProgressPayload payload) {
    }

    public record DownloadProgressPayload(
            UUID jobId,
            UUID itemId,
            String status,
            long bytesDownloaded,
            Long sizeBytes,
            String sha256,
            String errorCode) {
    }

    public record DownloadJobReadyEvent(
            UUID eventId,
            String type,
            int schemaVersion,
            Instant occurredAt,
            String correlationId,
            String causationId,
            DownloadReadyPayload payload) {
    }

    public record DownloadReadyPayload(
            UUID jobId,
            String status,
            String objectKey,
            long sizeBytes,
            String sha256,
            int successfulItems,
            int failedItems,
            Instant expiresAt) {
    }

    public record DownloadJobFailedEvent(
            UUID eventId,
            String type,
            int schemaVersion,
            Instant occurredAt,
            String correlationId,
            String causationId,
            DownloadFailedPayload payload) {
    }

    public record DownloadFailedPayload(UUID jobId, String errorCode, int failedItems) {
    }
}
