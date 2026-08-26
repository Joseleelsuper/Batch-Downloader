package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Gestiona temporales y compensaciones de almacenamiento de un trabajo. */
final class DownloadJobFiles {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadJobFiles.class);

    private final ArtifactStore artifactStore;
    private final DownloadWorkerMetrics metrics;
    private final DownloadProperties properties;

    DownloadJobFiles(
            ArtifactStore artifactStore,
            DownloadWorkerMetrics metrics,
            DownloadProperties properties) {
        this.artifactStore = artifactStore;
        this.metrics = metrics;
        this.properties = properties;
    }

    Path createDirectory(UUID jobId) {
        try {
            Path base = Path.of(properties.tempDirectory());
            Files.createDirectories(base);
            return Files.createTempDirectory(base, jobId + "-");
        } catch (IOException exception) {
            throw new InfrastructureException("temp_directory_creation_failed", exception);
        }
    }

    void deleteTemporary(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOGGER.debug("Could not delete temporary download path {}", path, exception);
        }
    }

    void deleteStored(String objectKey) {
        try {
            artifactStore.delete(objectKey);
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not delete incomplete object {}", objectKey, exception);
        }
    }

    void removeDirectory(Path root) {
        metrics.temporaryRemoved(size(root));
        deleteRecursively(root);
    }

    private long size(Path root) {
        if (root == null || !Files.exists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException ignored) {
                    return 0;
                }
            }).sum();
        } catch (IOException exception) {
            return 0;
        }
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    LOGGER.debug("Could not delete temporary download path {}", path, exception);
                }
            });
        } catch (IOException exception) {
            LOGGER.debug("Could not traverse temporary download directory {}", root, exception);
        }
    }
}
