package es.ubu.batchdownloader.downloadworker.ports;

import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;

/**
 * Define el contrato de {@code SourceReferenceResolver}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface SourceReferenceResolver {
    /**
     * Resuelve el recurso solicitado mediante {@code resolve}.
     *
     * @param item Elemento sobre el que se realiza la operación.
     * @return Resultado producido por {@code resolve}.
     */
    ResolvedDownloadItem resolve(DownloadItemRequest item);
}
