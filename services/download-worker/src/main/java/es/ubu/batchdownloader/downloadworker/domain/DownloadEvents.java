package es.ubu.batchdownloader.downloadworker.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Implementa el componente {@code DownloadEvents}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public final class DownloadEvents {
    /**
     * Inicializa una instancia de {@code DownloadEvents}.
     */
    private DownloadEvents() {
    }

    /**
     * Representa los datos inmutables de {@code DownloadJobRequestedEvent}.
     *
     * @param eventId Valor de {@code eventId} incluido en el record.
     * @param type Valor de {@code type} incluido en el record.
     * @param schemaVersion Valor de {@code schemaVersion} incluido en el record.
     * @param occurredAt Valor de {@code occurredAt} incluido en el record.
     * @param correlationId Valor de {@code correlationId} incluido en el record.
     * @param causationId Valor de {@code causationId} incluido en el record.
     * @param payload Valor de {@code payload} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record DownloadJobRequestedEvent(
            @NotNull UUID eventId,
            @NotBlank String type,
            @Positive int schemaVersion,
            @NotNull Instant occurredAt,
            @NotBlank String correlationId,
            String causationId,
            @NotNull @Valid DownloadJobPayload payload) {
    }

    /**
     * Representa los datos inmutables de {@code DownloadJobPayload}.
     *
     * @param jobId Valor de {@code jobId} incluido en el record.
     * @param items Valor de {@code items} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record DownloadJobPayload(
            @NotNull UUID jobId,
            @NotEmpty @Size(max = 100) List<@Valid DownloadItemRequest> items) {
    }

    /**
     * Representa los datos inmutables de {@code DownloadItemRequest}.
     *
     * @param itemId Valor de {@code itemId} incluido en el record.
     * @param appId Valor de {@code appId} incluido en el record.
     * @param sourceRef Valor de {@code sourceRef} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record DownloadItemRequest(
            @NotNull UUID itemId,
            @NotNull UUID appId,
            UUID sourceRef) {
    }

    /**
     * Representa los datos inmutables de {@code DownloadJobProgressedEvent}.
     *
     * @param eventId Valor de {@code eventId} incluido en el record.
     * @param type Valor de {@code type} incluido en el record.
     * @param schemaVersion Valor de {@code schemaVersion} incluido en el record.
     * @param occurredAt Valor de {@code occurredAt} incluido en el record.
     * @param correlationId Valor de {@code correlationId} incluido en el record.
     * @param causationId Valor de {@code causationId} incluido en el record.
     * @param payload Valor de {@code payload} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record DownloadJobProgressedEvent(
            UUID eventId,
            String type,
            int schemaVersion,
            Instant occurredAt,
            String correlationId,
            String causationId,
            DownloadProgressPayload payload) {
    }

    /**
     * Representa los datos inmutables de {@code DownloadProgressPayload}.
     *
     * @param jobId Valor de {@code jobId} incluido en el record.
     * @param itemId Valor de {@code itemId} incluido en el record.
     * @param status Valor de {@code status} incluido en el record.
     * @param bytesDownloaded Valor de {@code bytesDownloaded} incluido en el record.
     * @param sizeBytes Valor de {@code sizeBytes} incluido en el record.
     * @param sha256 Valor de {@code sha256} incluido en el record.
     * @param errorCode Valor de {@code errorCode} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record DownloadProgressPayload(
            UUID jobId,
            UUID itemId,
            String status,
            long bytesDownloaded,
            Long sizeBytes,
            String sha256,
            String errorCode) {
    }

    /**
     * Representa los datos inmutables de {@code DownloadJobReadyEvent}.
     *
     * @param eventId Valor de {@code eventId} incluido en el record.
     * @param type Valor de {@code type} incluido en el record.
     * @param schemaVersion Valor de {@code schemaVersion} incluido en el record.
     * @param occurredAt Valor de {@code occurredAt} incluido en el record.
     * @param correlationId Valor de {@code correlationId} incluido en el record.
     * @param causationId Valor de {@code causationId} incluido en el record.
     * @param payload Valor de {@code payload} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record DownloadJobReadyEvent(
            UUID eventId,
            String type,
            int schemaVersion,
            Instant occurredAt,
            String correlationId,
            String causationId,
            DownloadReadyPayload payload) {
    }

    /** Evento no terminal emitido al devolver un trabajo a la cola de capacidad. */
    public record DownloadJobDeferredEvent(
            UUID eventId,
            String type,
            int schemaVersion,
            Instant occurredAt,
            String correlationId,
            String causationId,
            DownloadDeferredPayload payload) {
    }

    /** Datos públicos de la espera temporal de capacidad. */
    public record DownloadDeferredPayload(
            UUID jobId,
            String waitReason,
            Instant retryAt) {
    }

    /**
     * Representa los datos inmutables de {@code DownloadReadyPayload}.
     *
     * @param jobId Valor de {@code jobId} incluido en el record.
     * @param status Valor de {@code status} incluido en el record.
     * @param objectKey Valor de {@code objectKey} incluido en el record.
     * @param sizeBytes Valor de {@code sizeBytes} incluido en el record.
     * @param sha256 Valor de {@code sha256} incluido en el record.
     * @param successfulItems Valor de {@code successfulItems} incluido en el record.
     * @param failedItems Valor de {@code failedItems} incluido en el record.
     * @param expiresAt Valor de {@code expiresAt} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
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

    /**
     * Representa los datos inmutables de {@code DownloadJobFailedEvent}.
     *
     * @param eventId Valor de {@code eventId} incluido en el record.
     * @param type Valor de {@code type} incluido en el record.
     * @param schemaVersion Valor de {@code schemaVersion} incluido en el record.
     * @param occurredAt Valor de {@code occurredAt} incluido en el record.
     * @param correlationId Valor de {@code correlationId} incluido en el record.
     * @param causationId Valor de {@code causationId} incluido en el record.
     * @param payload Valor de {@code payload} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record DownloadJobFailedEvent(
            UUID eventId,
            String type,
            int schemaVersion,
            Instant occurredAt,
            String correlationId,
            String causationId,
            DownloadFailedPayload payload) {
    }

    /**
     * Representa los datos inmutables de {@code DownloadFailedPayload}.
     *
     * @param jobId Valor de {@code jobId} incluido en el record.
     * @param errorCode Valor de {@code errorCode} incluido en el record.
     * @param failedItems Valor de {@code failedItems} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record DownloadFailedPayload(UUID jobId, String errorCode, int failedItems) {
    }
}
