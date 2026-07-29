package es.ubu.batchdownloader.downloadworker.domain;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DownloadModels {
    private DownloadModels() {
    }

    public record ResolvedDownloadItem(
            UUID itemId,
            UUID appId,
            UUID sourceRef,
            URI url,
            String filename,
            String operatingSystem,
            String architecture,
            Long expectedSizeBytes,
            String expectedSha256,
            String expectedMime) {
    }

    public record DownloadedArtifact(
            UUID itemId,
            UUID appId,
            UUID sourceRef,
            String filename,
            Path path,
            long sizeBytes,
            String sha256,
            String objectKey) {
    }

    public record FailedDownload(
            UUID itemId,
            UUID appId,
            UUID sourceRef,
            String filename,
            String errorCode) {
    }

    public record DownloadItemMetadata(
            UUID itemId,
            UUID appId,
            String appName,
            String officialPageUrl) {
    }

    public record ArchiveEntry(
            String path,
            Path source) {
    }

    public record ManifestItem(
            UUID itemId,
            UUID appId,
            UUID sourceRef,
            String appName,
            String filename,
            String status,
            Long sizeBytes,
            String sha256,
            String objectKey,
            String error,
            String manualShortcut) {
    }

    public record DownloadManifest(
            int schemaVersion,
            UUID jobId,
            Instant generatedAt,
            String status,
            List<ManifestItem> items) {
    }
}
