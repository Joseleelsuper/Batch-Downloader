package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.ports.RemoteExchange;
import java.net.URI;

/** Aplica la política HTTPS/SSRF justo antes de cada salto de red. */
public final class PublicHttpsRemoteExchange implements RemoteExchange {
    private final RemoteExchange delegate;
    private final PublicHttpsUriPolicy uriPolicy;

    /** Inicializa el wrapper de seguridad. */
    public PublicHttpsRemoteExchange(RemoteExchange delegate, PublicHttpsUriPolicy uriPolicy) {
        this.delegate = delegate;
        this.uriPolicy = uriPolicy;
    }

    /** {@inheritDoc} */
    @Override
    public Response get(URI uri) {
        uriPolicy.validate(uri);
        return delegate.get(uri);
    }
}
