package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.ports.RemoteExchange;
import java.net.URI;

/**
 * Comprueba cada destino antes de abrir la respuesta, por lo que la misma validación se aplica a
 * URI inicial y redirecciones.
 *
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.PublicHttpsUriPolicy
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.DefaultRemoteDownloader
 * @since 0.1.0
 * @version 0.1.0
 * @category Transporte de descargas
 */
public final class PublicHttpsRemoteExchange implements RemoteExchange {
    private final RemoteExchange delegate;
    private final PublicHttpsUriPolicy uriPolicy;

    /**
     * Conecta el intercambio real y la política pública que debe superarse antes de cada petición.
     *
     * @param delegate Siguiente política o transporte de la cadena de descarga.
     * @param uriPolicy Política que comprueba HTTPS, credenciales y direcciones de cada destino.
     */
    public PublicHttpsRemoteExchange(RemoteExchange delegate, PublicHttpsUriPolicy uriPolicy) {
        this.delegate = delegate;
        this.uriPolicy = uriPolicy;
    }

    /**
     * Valida la URI y solo después delega la petición HTTP.
     *
     * @param uri URI de destino que debe superar la política de acceso a recursos públicos.
     * @return respuesta del destino que superó la política.
     */
    @Override
    public Response get(URI uri) {
        uriPolicy.validate(uri);
        return delegate.get(uri);
    }
}
