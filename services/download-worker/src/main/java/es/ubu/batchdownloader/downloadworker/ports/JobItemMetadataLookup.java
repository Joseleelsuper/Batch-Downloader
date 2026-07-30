package es.ubu.batchdownloader.downloadworker.ports;

import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadItemMetadata;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Define el contrato de {@code JobItemMetadataLookup}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface JobItemMetadataLookup {
    /**
     * Busca el resultado solicitado mediante {@code find}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @param requestedItems Valor de {@code requestedItems} utilizado por la operación.
     * @return Mapa con los datos producidos por la operación.
     */
    Map<UUID, DownloadItemMetadata> find(
            UUID jobId,
            List<DownloadItemRequest> requestedItems);
}
