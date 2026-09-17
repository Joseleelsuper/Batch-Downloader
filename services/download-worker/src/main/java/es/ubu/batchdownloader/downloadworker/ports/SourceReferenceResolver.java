package es.ubu.batchdownloader.downloadworker.ports;

import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;

/**
 * Resuelve y revalida la fuente exacta admitida antes de descargarla, manteniendo separada la
 * referencia pública de su URI final privada.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader
 * @since 0.1.0
 * @version 0.1.0
 * @category Puertos del worker
 */
public interface SourceReferenceResolver {
    /**
     * Obtiene la URI vigente y expectativas de integridad de la fuente seleccionada para el
     * elemento.
     *
     * @param item Elemento admitido o resuelto cuya fuente exacta se procesa.
     * @return elemento resuelto que conserva itemId, appId y sourceRef.
     */
    ResolvedDownloadItem resolve(DownloadItemRequest item);
}
