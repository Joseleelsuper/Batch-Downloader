package es.ubu.batchdownloader.downloads.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class DownloadJob {
    private final UUID id;
    private final UUID ownerId;
    private final String anonymousOwnerHash;
    private final String anonymousIpHash;
    private DownloadJobStatus status;
    private int progress;
    private String objectKey;
    private String failureCode;
    private boolean cancellationRequested;
    private final boolean notifyWhenReady;
    private final int requestedCount;
    private final int acceptedCount;
    private final int omittedCount;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant expiresAt;
    private final List<DownloadJobItem> items;
    private long version;

    private DownloadJob(
            UUID id,
            UUID ownerId,
            String anonymousOwnerHash,
            String anonymousIpHash,
            DownloadJobStatus status,
            int progress,
            String objectKey,
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
                DownloadJobStatus.QUEUED, 0, null, null, false,
                notifyWhenReady, requestedCount, items.size(), omittedCount,
                now, now, expiresAt, items, 0);
    }

    public static DownloadJob rehydrate(
            UUID id, UUID ownerId, String anonymousOwnerHash, String anonymousIpHash,
            DownloadJobStatus status, int progress, String objectKey, String failureCode,
            boolean cancellationRequested, boolean notifyWhenReady,
            int requestedCount, int acceptedCount, int omittedCount,
            Instant createdAt, Instant updatedAt, Instant expiresAt, List<DownloadJobItem> items, long version) {
        return new DownloadJob(
                id, ownerId, anonymousOwnerHash, anonymousIpHash, status, progress, objectKey, failureCode,
                cancellationRequested, notifyWhenReady, requestedCount, acceptedCount, omittedCount,
                createdAt, updatedAt, expiresAt, items, version);
    }

    public void updateItem(
            UUID itemId, DownloadItemStatus itemStatus, long bytesDownloaded, String sha256,
            String errorCode, Instant now) {
        if (status.terminal()) return;
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

    public void markReady(DownloadJobStatus result, String key, Instant workerExpiry, Instant now) {
        if (status == DownloadJobStatus.CANCELLED || status == DownloadJobStatus.EXPIRED) return;
        if (!result.downloadable()) throw new IllegalArgumentException("invalid_download_result_status");
        objectKey = requireText(key, "objectKey");
        status = result;
        progress = 100;
        expiresAt = workerExpiry.isBefore(expiresAt) ? workerExpiry : expiresAt;
        updatedAt = now;
    }

    public void fail(String code, Instant now) {
        if (status.terminal()) return;
        failureCode = requireText(code, "failureCode");
        status = DownloadJobStatus.FAILED;
        updatedAt = now;
    }

    public boolean requestCancellation(Instant now) {
        if (status.terminal()) return false;
        cancellationRequested = true;
        status = DownloadJobStatus.CANCELLED;
        items.forEach(item -> item.cancel(now));
        updatedAt = now;
        return true;
    }

    public boolean expire(Instant now) {
        if (!expiresAt.isAfter(now) && status.downloadable()) {
            status = DownloadJobStatus.EXPIRED;
            objectKey = null;
            updatedAt = now;
            return true;
        }
        return false;
    }

    private static DownloadJobStatus deriveActiveStatus(DownloadItemStatus itemStatus) {
        return switch (itemStatus) {
            case QUEUED -> DownloadJobStatus.QUEUED;
            case RESOLVING -> DownloadJobStatus.RESOLVING;
            case DOWNLOADING, COMPLETED, FAILED, CANCELLED -> DownloadJobStatus.DOWNLOADING;
        };
    }

    private static int activeStage(DownloadJobStatus candidate) {
        return switch (candidate) {
            case QUEUED -> 0;
            case RESOLVING -> 1;
            case DOWNLOADING -> 2;
            case PACKAGING -> 3;
            case READY, PARTIAL, MANUAL_ONLY, FAILED, CANCELLED, EXPIRED -> 4;
        };
    }

    private static int clampProgress(int value) { return Math.max(0, Math.min(100, value)); }
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    public UUID id() { return id; }
    public UUID ownerId() { return ownerId; }
    public String anonymousOwnerHash() { return anonymousOwnerHash; }
    public String anonymousIpHash() { return anonymousIpHash; }
    public DownloadJobStatus status() { return status; }
    public int progress() { return progress; }
    public String objectKey() { return objectKey; }
    public String failureCode() { return failureCode; }
    public boolean cancellationRequested() { return cancellationRequested; }
    public boolean notifyWhenReady() { return notifyWhenReady; }
    public int requestedCount() { return requestedCount; }
    public int acceptedCount() { return acceptedCount; }
    public int omittedCount() { return omittedCount; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant expiresAt() { return expiresAt; }
    public List<DownloadJobItem> items() { return items; }
    public long version() { return version; }
}
