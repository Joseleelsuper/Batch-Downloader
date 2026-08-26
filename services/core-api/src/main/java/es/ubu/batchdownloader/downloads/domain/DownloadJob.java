package es.ubu.batchdownloader.downloads.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementa el componente {@code DownloadJob}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public final class DownloadJob {
    /**
     * Estado {@code id} mantenido por {@code DownloadJob}.
     */
    private final UUID id;
    /**
     * Estado {@code ownerId} mantenido por {@code DownloadJob}.
     */
    private final UUID ownerId;
    /**
     * Estado {@code anonymousOwnerHash} mantenido por {@code DownloadJob}.
     */
    private final String anonymousOwnerHash;
    /**
     * Estado {@code anonymousIpHash} mantenido por {@code DownloadJob}.
     */
    private final String anonymousIpHash;
    /**
     * Estado {@code status} mantenido por {@code DownloadJob}.
     */
    private DownloadJobStatus status;
    /**
     * Estado {@code progress} mantenido por {@code DownloadJob}.
     */
    private int progress;
    /**
     * Estado {@code objectKey} mantenido por {@code DownloadJob}.
     */
    private String objectKey;
    /** Tamaño persistido del ZIP publicado, en bytes. */
    private Long artifactSizeBytes;
    /** SHA-256 hexadecimal del ZIP publicado. */
    private String artifactSha256;
    /** Motivo temporal por el que el trabajo continúa en cola. */
    private String waitReason;
    /** Instante a partir del cual el worker puede volver a intentar el trabajo. */
    private Instant retryAt;
    /**
     * Estado {@code failureCode} mantenido por {@code DownloadJob}.
     */
    private String failureCode;
    /**
     * Estado {@code cancellationRequested} mantenido por {@code DownloadJob}.
     */
    private boolean cancellationRequested;
    /**
     * Estado {@code notifyWhenReady} mantenido por {@code DownloadJob}.
     */
    private final boolean notifyWhenReady;
    /**
     * Estado {@code requestedCount} mantenido por {@code DownloadJob}.
     */
    private final int requestedCount;
    /**
     * Estado {@code acceptedCount} mantenido por {@code DownloadJob}.
     */
    private final int acceptedCount;
    /**
     * Estado {@code omittedCount} mantenido por {@code DownloadJob}.
     */
    private final int omittedCount;
    /**
     * Estado {@code createdAt} mantenido por {@code DownloadJob}.
     */
    private final Instant createdAt;
    /**
     * Estado {@code updatedAt} mantenido por {@code DownloadJob}.
     */
    private Instant updatedAt;
    /**
     * Estado {@code expiresAt} mantenido por {@code DownloadJob}.
     */
    private Instant expiresAt;
    /**
     * Estado {@code items} mantenido por {@code DownloadJob}.
     */
    private final List<DownloadJobItem> items;
    /**
     * Estado {@code version} mantenido por {@code DownloadJob}.
     */
    private long version;

    /**
     * Inicializa una instancia de {@code DownloadJob}.
     *
     * @param id Identificador del recurso sobre el que se actúa.
     * @param ownerId Identificador de {@code owner} utilizado por la operación.
     * @param anonymousOwnerHash Valor de {@code anonymousOwnerHash} utilizado por la operación.
     * @param anonymousIpHash Valor de {@code anonymousIpHash} utilizado por la operación.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param progress Valor de {@code progress} utilizado por la operación.
     * @param objectKey Valor de {@code objectKey} utilizado por la operación.
     * @param failureCode Valor de {@code failureCode} utilizado por la operación.
     * @param cancellationRequested Valor de {@code cancellationRequested} utilizado por la
     *     operación.
     * @param notifyWhenReady Valor de {@code notifyWhenReady} utilizado por la operación.
     * @param requestedCount Valor de {@code requestedCount} utilizado por la operación.
     * @param acceptedCount Valor de {@code acceptedCount} utilizado por la operación.
     * @param omittedCount Valor de {@code omittedCount} utilizado por la operación.
     * @param createdAt Valor de {@code createdAt} utilizado por la operación.
     * @param updatedAt Valor de {@code updatedAt} utilizado por la operación.
     * @param expiresAt Valor de {@code expiresAt} utilizado por la operación.
     * @param items Colección de elementos que debe procesarse.
     * @param version Valor de {@code version} utilizado por la operación.
     * @throws IllegalArgumentException Si los argumentos recibidos no cumplen las restricciones
     *     requeridas.
     */
    private DownloadJob(
            UUID id,
            UUID ownerId,
            String anonymousOwnerHash,
            String anonymousIpHash,
            DownloadJobStatus status,
            int progress,
            String objectKey,
            Long artifactSizeBytes,
            String artifactSha256,
            String waitReason,
            Instant retryAt,
            String failureCode,
            boolean cancellationRequested,
            boolean notifyWhenReady,
            int requestedCount,
            int acceptedCount,
            int omittedCount,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt,
            List<DownloadJobItem> items,
            long version) {
        this.id = Objects.requireNonNull(id);
        this.ownerId = ownerId;
        this.anonymousOwnerHash = anonymousOwnerHash;
        this.anonymousIpHash = anonymousIpHash;
        this.status = Objects.requireNonNull(status);
        this.progress = clampProgress(progress);
        this.objectKey = objectKey;
        this.artifactSizeBytes = artifactSizeBytes;
        this.artifactSha256 = artifactSha256;
        this.waitReason = waitReason;
        this.retryAt = retryAt;
        this.failureCode = failureCode;
        this.cancellationRequested = cancellationRequested;
        this.notifyWhenReady = notifyWhenReady;
        this.requestedCount = requestedCount;
        this.acceptedCount = acceptedCount;
        this.omittedCount = omittedCount;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.items = List.copyOf(items);
        this.version = version;
        if ((ownerId == null) == (anonymousOwnerHash == null || anonymousOwnerHash.isBlank())) {
            throw new IllegalArgumentException("download_job_requires_exactly_one_owner");
        }
        if (items.isEmpty()) throw new IllegalArgumentException("download_job_requires_items");
        if (requestedCount < acceptedCount || acceptedCount != items.size() || omittedCount < 0
                || requestedCount != acceptedCount + omittedCount) {
            throw new IllegalArgumentException("invalid_download_job_counts");
        }
    }

    /**
     * Ejecuta la operación {@code queue}.
     *
     * @param ownerId Identificador de {@code owner} utilizado por la operación.
     * @param anonymousOwnerHash Valor de {@code anonymousOwnerHash} utilizado por la operación.
     * @param anonymousIpHash Valor de {@code anonymousIpHash} utilizado por la operación.
     * @param items Colección de elementos que debe procesarse.
     * @param requestedCount Valor de {@code requestedCount} utilizado por la operación.
     * @param omittedCount Valor de {@code omittedCount} utilizado por la operación.
     * @param notifyWhenReady Valor de {@code notifyWhenReady} utilizado por la operación.
     * @param now Valor de {@code now} utilizado por la operación.
     * @param expiresAt Valor de {@code expiresAt} utilizado por la operación.
     * @return Resultado producido por {@code queue}.
     */
    public static DownloadJob queue(
            UUID ownerId,
            String anonymousOwnerHash,
            String anonymousIpHash,
            List<DownloadJobItem> items,
            int requestedCount,
            int omittedCount,
            boolean notifyWhenReady,
            Instant now,
            Instant expiresAt) {
        return new DownloadJob(
                UUID.randomUUID(), ownerId, anonymousOwnerHash, anonymousIpHash,
                DownloadJobStatus.QUEUED, 0, null, null, null, null, null, null, false,
                notifyWhenReady, requestedCount, items.size(), omittedCount,
                now, now, expiresAt, items, 0);
    }

    /**
     * Ejecuta la operación {@code rehydrate}.
     *
     * @param id Identificador del recurso sobre el que se actúa.
     * @param ownerId Identificador de {@code owner} utilizado por la operación.
     * @param anonymousOwnerHash Valor de {@code anonymousOwnerHash} utilizado por la operación.
     * @param anonymousIpHash Valor de {@code anonymousIpHash} utilizado por la operación.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param progress Valor de {@code progress} utilizado por la operación.
     * @param objectKey Valor de {@code objectKey} utilizado por la operación.
     * @param failureCode Valor de {@code failureCode} utilizado por la operación.
     * @param cancellationRequested Valor de {@code cancellationRequested} utilizado por la
     *     operación.
     * @param notifyWhenReady Valor de {@code notifyWhenReady} utilizado por la operación.
     * @param requestedCount Valor de {@code requestedCount} utilizado por la operación.
     * @param acceptedCount Valor de {@code acceptedCount} utilizado por la operación.
     * @param omittedCount Valor de {@code omittedCount} utilizado por la operación.
     * @param createdAt Valor de {@code createdAt} utilizado por la operación.
     * @param updatedAt Valor de {@code updatedAt} utilizado por la operación.
     * @param expiresAt Valor de {@code expiresAt} utilizado por la operación.
     * @param items Colección de elementos que debe procesarse.
     * @param version Valor de {@code version} utilizado por la operación.
     * @return Resultado producido por {@code rehydrate}.
     */
    public static DownloadJob rehydrate(
            UUID id, UUID ownerId, String anonymousOwnerHash, String anonymousIpHash,
            DownloadJobStatus status, int progress, String objectKey, String failureCode,
            boolean cancellationRequested, boolean notifyWhenReady,
            int requestedCount, int acceptedCount, int omittedCount,
            Instant createdAt, Instant updatedAt, Instant expiresAt, List<DownloadJobItem> items, long version) {
        return new DownloadJob(
                id, ownerId, anonymousOwnerHash, anonymousIpHash, status, progress, objectKey,
                null, null, null, null, failureCode,
                cancellationRequested, notifyWhenReady, requestedCount, acceptedCount, omittedCount,
                createdAt, updatedAt, expiresAt, items, version);
    }

    /** Rehidrata también los metadatos aditivos del artefacto y de espera. */
    public static DownloadJob rehydrate(
            UUID id, UUID ownerId, String anonymousOwnerHash, String anonymousIpHash,
            DownloadJobStatus status, int progress, String objectKey,
            Long artifactSizeBytes, String artifactSha256, String waitReason, Instant retryAt,
            String failureCode, boolean cancellationRequested, boolean notifyWhenReady,
            int requestedCount, int acceptedCount, int omittedCount,
            Instant createdAt, Instant updatedAt, Instant expiresAt,
            List<DownloadJobItem> items, long version) {
        return new DownloadJob(
                id, ownerId, anonymousOwnerHash, anonymousIpHash, status, progress, objectKey,
                artifactSizeBytes, artifactSha256, waitReason, retryAt, failureCode,
                cancellationRequested, notifyWhenReady, requestedCount, acceptedCount, omittedCount,
                createdAt, updatedAt, expiresAt, items, version);
    }

    /**
     * Actualiza el recurso solicitado mediante {@code updateItem}.
     *
     * @param itemId Identificador de {@code item} utilizado por la operación.
     * @param itemStatus Valor de {@code itemStatus} utilizado por la operación.
     * @param bytesDownloaded Valor de {@code bytesDownloaded} utilizado por la operación.
     * @param sha256 Valor de {@code sha256} utilizado por la operación.
     * @param errorCode Valor de {@code errorCode} utilizado por la operación.
     * @param now Valor de {@code now} utilizado por la operación.
     */
    public void updateItem(
            UUID itemId, DownloadItemStatus itemStatus, long bytesDownloaded, String sha256,
            String errorCode, Instant now) {
        if (status.terminal()) return;
        waitReason = null;
        retryAt = null;
        DownloadJobItem item = items.stream().filter(candidate -> candidate.id().equals(itemId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown_download_item"));
        item.progress(itemStatus, bytesDownloaded, sha256, errorCode, now);
        long terminalItems = items.stream().filter(candidate -> candidate.status().terminal()).count();
        progress = Math.max(progress, (int) ((terminalItems * 90L) / items.size()));
        DownloadJobStatus next = terminalItems == items.size()
                ? DownloadJobStatus.PACKAGING
                : deriveActiveStatus(itemStatus);
        if (activeStage(next) >= activeStage(status)) {
            status = next;
        }
        updatedAt = now;
    }

    /**
     * Marca el recurso solicitado mediante {@code markReady}.
     *
     * @param result Resultado que debe procesarse.
     * @param key Valor de {@code key} utilizado por la operación.
     * @param workerExpiry Valor de {@code workerExpiry} utilizado por la operación.
     * @param now Valor de {@code now} utilizado por la operación.
     * @throws IllegalArgumentException Si los argumentos recibidos no cumplen las restricciones
     *     requeridas.
     */
    public void markReady(DownloadJobStatus result, String key, Instant workerExpiry, Instant now) {
        markReady(result, key, null, null, workerExpiry, now);
    }

    /** Marca el trabajo como descargable y conserva la integridad publicada por el worker. */
    public void markReady(
            DownloadJobStatus result,
            String key,
            Long sizeBytes,
            String sha256,
            Instant workerExpiry,
            Instant now) {
        if (status == DownloadJobStatus.CANCELLED || status == DownloadJobStatus.EXPIRED) return;
        if (!result.downloadable()) throw new IllegalArgumentException("invalid_download_result_status");
        if (sizeBytes != null && sizeBytes < 0) throw new IllegalArgumentException("invalid_artifact_size");
        if (sha256 != null && !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("invalid_artifact_sha256");
        }
        objectKey = requireText(key, "objectKey");
        artifactSizeBytes = sizeBytes;
        artifactSha256 = sha256 == null ? null : sha256.toLowerCase(java.util.Locale.ROOT);
        waitReason = null;
        retryAt = null;
        status = result;
        progress = 100;
        expiresAt = Objects.requireNonNull(workerExpiry);
        updatedAt = now;
    }

    /** Mantiene el trabajo en cola cuando la capacidad es temporalmente insuficiente. */
    public void defer(String reason, Instant nextAttempt, Instant now) {
        if (status.terminal()) return;
        waitReason = requireText(reason, "waitReason");
        retryAt = Objects.requireNonNull(nextAttempt);
        status = DownloadJobStatus.QUEUED;
        progress = 0;
        items.forEach(item -> item.requeue(now));
        updatedAt = now;
    }

    /**
     * Ejecuta la operación {@code fail}.
     *
     * @param code Valor de {@code code} utilizado por la operación.
     * @param now Valor de {@code now} utilizado por la operación.
     */
    public void fail(String code, Instant now) {
        if (status.terminal()) return;
        failureCode = requireText(code, "failureCode");
        status = DownloadJobStatus.FAILED;
        updatedAt = now;
    }

    /**
     * Ejecuta la operación {@code requestCancellation}.
     *
     * @param now Valor de {@code now} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    public boolean requestCancellation(Instant now) {
        if (status.terminal()) return false;
        cancellationRequested = true;
        status = DownloadJobStatus.CANCELLED;
        items.forEach(item -> item.cancel(now));
        updatedAt = now;
        return true;
    }

    /**
     * Ejecuta la operación {@code expire}.
     *
     * @param now Valor de {@code now} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    public boolean expire(Instant now) {
        if (!expiresAt.isAfter(now) && status.downloadable()) {
            status = DownloadJobStatus.EXPIRED;
            objectKey = null;
            updatedAt = now;
            return true;
        }
        return false;
    }

    /**
     * Ejecuta la operación {@code deriveActiveStatus}.
     *
     * @param itemStatus Valor de {@code itemStatus} utilizado por la operación.
     * @return Resultado producido por {@code deriveActiveStatus}.
     */
    private static DownloadJobStatus deriveActiveStatus(DownloadItemStatus itemStatus) {
        return switch (itemStatus) {
            case QUEUED -> DownloadJobStatus.QUEUED;
            case RESOLVING -> DownloadJobStatus.RESOLVING;
            case DOWNLOADING, COMPLETED, FAILED, CANCELLED -> DownloadJobStatus.DOWNLOADING;
        };
    }

    /**
     * Ejecuta la operación {@code activeStage}.
     *
     * @param candidate Valor de {@code candidate} utilizado por la operación.
     * @return Resultado producido por {@code activeStage}.
     */
    private static int activeStage(DownloadJobStatus candidate) {
        return switch (candidate) {
            case QUEUED -> 0;
            case RESOLVING -> 1;
            case DOWNLOADING -> 2;
            case PACKAGING -> 3;
            case READY, PARTIAL, MANUAL_ONLY, FAILED, CANCELLED, EXPIRED -> 4;
        };
    }

    /**
     * Ejecuta la operación {@code clampProgress}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code clampProgress}.
     */
    private static int clampProgress(int value) { return Math.max(0, Math.min(100, value)); }
    /**
     * Ejecuta la operación {@code requireText}.
     *
     * @param value Valor que debe procesarse.
     * @param name Nombre del elemento sobre el que se actúa.
     * @return Resultado producido por {@code requireText}.
     * @throws IllegalArgumentException Si los argumentos recibidos no cumplen las restricciones
     *     requeridas.
     */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    /**
     * Ejecuta la operación {@code id}.
     *
     * @return Resultado producido por {@code id}.
     */
    public UUID id() { return id; }
    /**
     * Ejecuta la operación {@code ownerId}.
     *
     * @return Resultado producido por {@code ownerId}.
     */
    public UUID ownerId() { return ownerId; }
    /**
     * Ejecuta la operación {@code anonymousOwnerHash}.
     *
     * @return Resultado producido por {@code anonymousOwnerHash}.
     */
    public String anonymousOwnerHash() { return anonymousOwnerHash; }
    /**
     * Ejecuta la operación {@code anonymousIpHash}.
     *
     * @return Resultado producido por {@code anonymousIpHash}.
     */
    public String anonymousIpHash() { return anonymousIpHash; }
    /**
     * Ejecuta la operación {@code status}.
     *
     * @return Resultado producido por {@code status}.
     */
    public DownloadJobStatus status() { return status; }
    /**
     * Ejecuta la operación {@code progress}.
     *
     * @return Resultado producido por {@code progress}.
     */
    public int progress() { return progress; }
    /**
     * Ejecuta la operación {@code objectKey}.
     *
     * @return Resultado producido por {@code objectKey}.
     */
    public String objectKey() { return objectKey; }
    /** @return Tamaño del ZIP publicado, o {@code null} si todavía no existe. */
    public Long artifactSizeBytes() { return artifactSizeBytes; }
    /** @return SHA-256 del ZIP publicado, o {@code null} si todavía no existe. */
    public String artifactSha256() { return artifactSha256; }
    /** @return Motivo temporal de espera, o {@code null}. */
    public String waitReason() { return waitReason; }
    /** @return Próximo instante de reintento por capacidad, o {@code null}. */
    public Instant retryAt() { return retryAt; }
    /**
     * Ejecuta la operación {@code failureCode}.
     *
     * @return Resultado producido por {@code failureCode}.
     */
    public String failureCode() { return failureCode; }
    /**
     * Indica si puede realizarse la operación mediante {@code cancellationRequested}.
     *
     * @return Indica si se cumple la condición evaluada.
     */
    public boolean cancellationRequested() { return cancellationRequested; }
    /**
     * Ejecuta la operación {@code notifyWhenReady}.
     *
     * @return Indica si se cumple la condición evaluada.
     */
    public boolean notifyWhenReady() { return notifyWhenReady; }
    /**
     * Ejecuta la operación {@code requestedCount}.
     *
     * @return Resultado producido por {@code requestedCount}.
     */
    public int requestedCount() { return requestedCount; }
    /**
     * Ejecuta la operación {@code acceptedCount}.
     *
     * @return Resultado producido por {@code acceptedCount}.
     */
    public int acceptedCount() { return acceptedCount; }
    /**
     * Ejecuta la operación {@code omittedCount}.
     *
     * @return Resultado producido por {@code omittedCount}.
     */
    public int omittedCount() { return omittedCount; }
    /**
     * Crea el recurso solicitado mediante {@code createdAt}.
     *
     * @return Resultado producido por {@code createdAt}.
     */
    public Instant createdAt() { return createdAt; }
    /**
     * Actualiza el recurso solicitado mediante {@code updatedAt}.
     *
     * @return Resultado producido por {@code updatedAt}.
     */
    public Instant updatedAt() { return updatedAt; }
    /**
     * Ejecuta la operación {@code expiresAt}.
     *
     * @return Resultado producido por {@code expiresAt}.
     */
    public Instant expiresAt() { return expiresAt; }
    /**
     * Ejecuta la operación {@code items}.
     *
     * @return Colección de elementos obtenidos por la operación.
     */
    public List<DownloadJobItem> items() { return items; }
    /**
     * Ejecuta la operación {@code version}.
     *
     * @return Resultado producido por {@code version}.
     */
    public long version() { return version; }
}
