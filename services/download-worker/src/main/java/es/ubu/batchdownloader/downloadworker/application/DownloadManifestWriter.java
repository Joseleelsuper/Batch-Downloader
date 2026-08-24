package es.ubu.batchdownloader.downloadworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadItemMetadata;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadManifest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.FailedDownload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ManifestItem;
import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Serializa el manifiesto estable incluido en el ZIP y publicado en almacenamiento. */
final class DownloadManifestWriter {
    private static final int MANIFEST_VERSION = 2;

    private final ObjectMapper objectMapper;
    private final Clock clock;

    DownloadManifestWriter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    byte[] write(
            DownloadJobRequestedEvent event,
            String status,
            List<DownloadedArtifact> downloaded,
            List<FailedDownload> failed,
            Map<UUID, DownloadItemMetadata> failedMetadata,
            Map<UUID, String> manualShortcutPaths) {
        Map<UUID, ManifestItem> items = new HashMap<>();
        for (DownloadedArtifact artifact : downloaded) {
            items.put(artifact.itemId(), new ManifestItem(
                    artifact.itemId(), artifact.appId(), artifact.sourceRef(), null,
                    artifact.filename(), "COMPLETED", artifact.sizeBytes(), artifact.sha256(),
                    artifact.filename(), null, null, null));
        }
        for (FailedDownload failure : failed) {
            DownloadItemMetadata metadata = failedMetadata.get(failure.itemId());
            String shortcut = manualShortcutPaths.get(failure.itemId());
            items.put(failure.itemId(), new ManifestItem(
                    failure.itemId(), failure.appId(), failure.sourceRef(),
                    metadata == null ? failure.appId().toString() : metadata.appName(),
                    failure.filename(), "FAILED", null, null,
                    shortcut, null, failure.errorCode(), shortcut));
        }
        List<ManifestItem> ordered = event.payload().items().stream()
                .map(item -> items.get(item.itemId()))
                .toList();
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(new DownloadManifest(
                    MANIFEST_VERSION, event.payload().jobId(), clock.instant(), status, ordered));
        } catch (IOException exception) {
            throw new InfrastructureException("manifest_creation_failed", exception);
        }
    }
}
