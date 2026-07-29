package es.ubu.batchdownloader.downloadworker.ports;

import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ArchiveEntry;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import java.nio.file.Path;
import java.util.List;

public interface ArchiveBuilder {
    void build(
            Path target,
            List<DownloadedArtifact> artifacts,
            List<ArchiveEntry> supplementalEntries,
            Path manifest);
}
