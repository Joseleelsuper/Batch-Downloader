package es.ubu.batchdownloader.downloadworker.ports;

import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadItemMetadata;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface JobItemMetadataLookup {
    Map<UUID, DownloadItemMetadata> find(
            UUID jobId,
            List<DownloadItemRequest> requestedItems);
}
