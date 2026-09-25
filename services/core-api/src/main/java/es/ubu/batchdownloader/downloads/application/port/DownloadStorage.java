package es.ubu.batchdownloader.downloads.application.port;

import java.util.List;
import java.util.UUID;

/** Inventario, borrado confirmado y metadatos externos; nunca se invoca dentro de una transacción. */
public interface DownloadStorage {
    record StoredJob(UUID jobId, long bytes, boolean active) {}
    record Inventory(List<StoredJob> jobs, long availableBytes) {}
    Inventory inventory();
    void delete(UUID jobId);
    Long revalidateSize(UUID sourceRef);
}
