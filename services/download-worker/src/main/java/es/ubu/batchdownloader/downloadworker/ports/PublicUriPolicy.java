package es.ubu.batchdownloader.downloadworker.ports;

import java.net.URI;

/**
 * Separa la decisión de si una URI puede consultarse de la implementación de transporte, para que
 * la aplicación valide destinos sin depender del adaptador HTTP.
 *
 * @see es.ubu.batchdownloader.downloadworker.ports.SourceReferenceResolver
 * @see es.ubu.batchdownloader.downloadworker.ports.RemoteExchange
 * @since 0.1.0
 * @version 0.1.0
 * @category Puertos del worker
 */
@FunctionalInterface
public interface PublicUriPolicy {
    /**
     * Acepta la URI si cumple la política de acceso público o propaga su rechazo antes de efectuar
     * la petición.
     *
     * @param uri URI de destino que debe superar la política de acceso a recursos públicos.
     */
    void validate(URI uri);
}
