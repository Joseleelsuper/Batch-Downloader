package es.ubu.batchdownloader.downloads.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class DownloadJobItem {
    private final UUID id;
    private final UUID appId;
    private final UUID sourceRef;
    private final String appName;
    private final String officialPageUrl;
    private DownloadItemStatus status;
    private long bytesDownloaded;
    private String sha256;
    private String errorCode;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private DownloadJobItem(
            UUID id,
            UUID appId,
            UUID sourceRef,
            String appName,
            String officialPageUrl,
            DownloadItemStatus status,
            long bytesDownloaded,
            String sha256,
            String errorCode,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id);
        this.appId = Objects.requireNonNull(appId);
        this.sourceRef = Objects.requireNonNull(sourceRef);
        this.appName = normalizedName(appName, appId);
        this.officialPageUrl = normalizedOptionalText(officialPageUrl);
        this.status = Objects.requireNonNull(status);
        this.bytesDownloaded = Math.max(0, bytesDownloaded);
        this.sha256 = sha256;
        this.errorCode = errorCode;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.version = version;
    }

    public static DownloadJobItem queued(UUID appId, UUID sourceRef, Instant now) {
        return queued(appId, sourceRef, appId.toString(), null, now);
    }

    public static DownloadJobItem queued(
            UUID appId, UUID sourceRef, String appName, String officialPageUrl, Instant now) {
        return new DownloadJobItem(
                UUID.randomUUID(), appId, sourceRef, appName, officialPageUrl,
                DownloadItemStatus.QUEUED, 0, null, null, now, now, 0);
    }

    public static DownloadJobItem rehydrate(
            UUID id, UUID appId, UUID sourceRef, String appName, String officialPageUrl,
            DownloadItemStatus status, long bytesDownloaded,
            String sha256, String errorCode, Instant createdAt, Instant updatedAt, long version) {
        return new DownloadJobItem(
                id, appId, sourceRef, appName, officialPageUrl,
                status, bytesDownloaded, sha256, errorCode, createdAt, updatedAt, version);
    }

    public void progress(DownloadItemStatus next, long downloaded, String checksum, String failure, Instant now) {
        if (status.terminal()) return;
        status = Objects.requireNonNull(next);
        bytesDownloaded = Math.max(bytesDownloaded, downloaded);
        sha256 = checksum;
        errorCode = failure;
        updatedAt = Objects.requireNonNull(now);
    }

    public void cancel(Instant now) {
        if (!status.terminal()) progress(DownloadItemStatus.CANCELLED, bytesDownloaded, sha256, null, now);
    }

    public UUID id() { return id; }
    public UUID appId() { return appId; }
    public UUID sourceRef() { return sourceRef; }
    public String appName() { return appName; }
    public String officialPageUrl() { return officialPageUrl; }
    public DownloadItemStatus status() { return status; }
    public long bytesDownloaded() { return bytesDownloaded; }
    public String sha256() { return sha256; }
    public String errorCode() { return errorCode; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }

    private static String normalizedName(String value, UUID appId) {
        return value == null || value.isBlank() ? appId.toString() : value.strip();
    }

    private static String normalizedOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
