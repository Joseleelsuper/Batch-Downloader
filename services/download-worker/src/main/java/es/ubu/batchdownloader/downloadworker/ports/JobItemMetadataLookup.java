package es.ubu.batchdownloader.downloadworker.ports;

import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadItemMetadata;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Obtiene en lote nombres y páginas oficiales para describir los elementos y generar alternativas
 * manuales.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @since 0.1.0
 * @version 0.1.0
 * @category Puertos del worker
 */
public interface JobItemMetadataLookup {
    /**
     * Consulta los metadatos públicos de los elementos admitidos sin incorporar URLs privadas de
     * instaladores.
     *
     * @param jobId UUID del trabajo cuyos metadatos públicos se necesitan.
     * @param requestedItems Elementos admitidos de los que se solicita nombre y alternativa manual.
     * @return metadatos por UUID de elemento disponibles para el trabajo.
     */
    Map<UUID, DownloadItemMetadata> find(
            UUID jobId,
            List<DownloadItemRequest> requestedItems);
}
