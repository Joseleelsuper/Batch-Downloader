package es.ubu.batchdownloader.common.http;

/**
 * Añade la credencial de servicio a cada petición interna mediante una copia inmutable.
 *
 * @see es.ubu.batchdownloader.common.http.InternalHttpExecutor
 * @see es.ubu.batchdownloader.common.http.InternalHttpRequest
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
public final class ServiceTokenInternalHttpExecutor implements InternalHttpExecutor {
    private final InternalHttpExecutor delegate;
    private final String token;

    /**
     * Conecta el siguiente ejecutor y la credencial que debe acompañar sus peticiones.
     *
     * @param delegate Siguiente ejecutor de la cadena de transporte y políticas.
     * @param token Credencial interna que se añade en X-Internal-Service-Token.
     */
    public ServiceTokenInternalHttpExecutor(InternalHttpExecutor delegate, String token) {
        this.delegate = delegate;
        this.token = token;
    }

    /**
     * Sustituye X-Internal-Service-Token por la credencial configurada antes de delegar.
     *
     * @param request Solicitud HTTP fallida o petición interna según la firma del método.
     * @return respuesta del siguiente ejecutor.
     */
    @Override
    public InternalHttpResponse execute(InternalHttpRequest request) {
        return delegate.execute(request.withHeader("X-Internal-Service-Token", token));
    }
}
