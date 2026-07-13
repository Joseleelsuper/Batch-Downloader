package es.ubu.batchdownloader.downloadworker.ports;

import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;

public interface SourceReferenceResolver {
    ResolvedDownloadItem resolve(DownloadItemRequest item);
}
