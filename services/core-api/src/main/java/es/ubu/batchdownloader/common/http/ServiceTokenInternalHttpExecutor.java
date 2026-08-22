package es.ubu.batchdownloader.common.http;

/** Añade la credencial compartida inmediatamente antes del transporte. */
public final class ServiceTokenInternalHttpExecutor implements InternalHttpExecutor {
    private final InternalHttpExecutor delegate;
    private final String token;

    /** Inicializa el wrapper de autenticación entre servicios. */
    public ServiceTokenInternalHttpExecutor(InternalHttpExecutor delegate, String token) {
        this.delegate = delegate;
        this.token = token;
    }

    /** {@inheritDoc} */
    @Override
    public InternalHttpResponse execute(InternalHttpRequest request) {
        return delegate.execute(request.withHeader("X-Internal-Service-Token", token));
    }
}
