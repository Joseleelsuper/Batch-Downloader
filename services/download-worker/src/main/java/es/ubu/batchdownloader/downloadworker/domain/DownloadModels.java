package es.ubu.batchdownloader.downloadworker.domain;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Implementa el componente {@code DownloadModels}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public final class DownloadModels {
    /**
     * Inicializa una instancia de {@code DownloadModels}.
     */
    private DownloadModels() {
    }

    /**
     * Representa los datos inmutables de {@code ResolvedDownloadItem}.
     *
     * @param itemId Valor de {@code itemId} incluido en el record.
     * @param appId Valor de {@code appId} incluido en el record.
     * @param sourceRef Valor de {@code sourceRef} incluido en el record.
     * @param url Valor de {@code url} incluido en el record.
     * @param filename Valor de {@code filename} incluido en el record.
     * @param operatingSystem Valor de {@code operatingSystem} incluido en el record.
     * @param architecture Valor de {@code architecture} incluido en el record.
     * @param expectedSizeBytes Valor de {@code expectedSizeBytes} incluido en el record.
     * @param expectedSha256 Valor de {@code expectedSha256} incluido en el record.
     * @param expectedMime Valor de {@code expectedMime} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
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

    /**
     * Representa los datos inmutables de {@code DownloadedArtifact}.
     *
     * @param itemId Valor de {@code itemId} incluido en el record.
     * @param appId Valor de {@code appId} incluido en el record.
     * @param sourceRef Valor de {@code sourceRef} incluido en el record.
     * @param filename Valor de {@code filename} incluido en el record.
     * @param path Valor de {@code path} incluido en el record.
     * @param sizeBytes Valor de {@code sizeBytes} incluido en el record.
     * @param sha256 Valor de {@code sha256} incluido en el record.
     * @param archivePath Ruta de la entrada dentro del ZIP.
     * @param objectKey Valor de {@code objectKey} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
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

    /**
     * Representa los datos inmutables de {@code FailedDownload}.
     *
     * @param itemId Valor de {@code itemId} incluido en el record.
     * @param appId Valor de {@code appId} incluido en el record.
     * @param sourceRef Valor de {@code sourceRef} incluido en el record.
     * @param filename Valor de {@code filename} incluido en el record.
     * @param errorCode Valor de {@code errorCode} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record FailedDownload(
            UUID itemId,
            UUID appId,
            UUID sourceRef,
            String filename,
            String errorCode) {
    }

    /**
     * Representa los datos inmutables de {@code DownloadItemMetadata}.
     *
     * @param itemId Valor de {@code itemId} incluido en el record.
     * @param appId Valor de {@code appId} incluido en el record.
     * @param appName Valor de {@code appName} incluido en el record.
     * @param officialPageUrl Valor de {@code officialPageUrl} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record DownloadItemMetadata(
            UUID itemId,
            UUID appId,
            String appName,
            String officialPageUrl) {
    }

    /**
     * Representa los datos inmutables de {@code ArchiveEntry}.
     *
     * @param path Valor de {@code path} incluido en el record.
     * @param source Valor de {@code source} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record ArchiveEntry(
            String path,
            Path source) {
    }

    /**
     * Representa los datos inmutables de {@code ManifestItem}.
     *
     * @param itemId Valor de {@code itemId} incluido en el record.
     * @param appId Valor de {@code appId} incluido en el record.
     * @param sourceRef Valor de {@code sourceRef} incluido en el record.
     * @param appName Valor de {@code appName} incluido en el record.
     * @param filename Valor de {@code filename} incluido en el record.
     * @param status Valor de {@code status} incluido en el record.
     * @param sizeBytes Valor de {@code sizeBytes} incluido en el record.
     * @param sha256 Valor de {@code sha256} incluido en el record.
     * @param objectKey Valor de {@code objectKey} incluido en el record.
     * @param error Valor de {@code error} incluido en el record.
     * @param manualShortcut Valor de {@code manualShortcut} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record ManifestItem(
            UUID itemId,
            UUID appId,
            UUID sourceRef,
            String appName,
            String filename,
            String status,
            Long sizeBytes,
            String sha256,
            String archivePath,
            String objectKey,
            String error,
            String manualShortcut) {
    }

    /**
     * Representa los datos inmutables de {@code DownloadManifest}.
     *
     * @param manifestVersion Versión del formato de manifiesto.
     * @param jobId Valor de {@code jobId} incluido en el record.
     * @param generatedAt Valor de {@code generatedAt} incluido en el record.
     * @param status Valor de {@code status} incluido en el record.
     * @param items Valor de {@code items} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record DownloadManifest(
            int manifestVersion,
            UUID jobId,
            Instant generatedAt,
            String status,
            List<ManifestItem> items) {
    }
}
