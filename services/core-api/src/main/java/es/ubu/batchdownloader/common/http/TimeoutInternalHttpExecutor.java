package es.ubu.batchdownloader.common.http;

import java.time.Duration;

/**
 * Aplica un plazo común a las solicitudes internas sin modificar el objeto recibido.
 *
 * @see es.ubu.batchdownloader.common.http.InternalHttpExecutor
 * @see es.ubu.batchdownloader.common.http.InternalHttpRequest
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
public final class TimeoutInternalHttpExecutor implements InternalHttpExecutor {
    private final InternalHttpExecutor delegate;
    private final Duration timeout;

    /**
     * Conecta la siguiente política y el plazo de ejecución configurado para el servicio.
     *
     * @param delegate Siguiente ejecutor de la cadena de transporte y políticas.
     * @param timeout Plazo máximo de ejecución HTTP; null mantiene el comportamiento del cliente
     *     subyacente.
     */
    public TimeoutInternalHttpExecutor(InternalHttpExecutor delegate, Duration timeout) {
        this.delegate = delegate;
        this.timeout = timeout;
    }

    /**
     * Delega una copia de la petición con el plazo configurado, sustituyendo cualquier plazo
     * anterior.
     *
     * @param request Solicitud HTTP fallida o petición interna según la firma del método.
     * @return respuesta del siguiente ejecutor.
     */
    @Override
    public InternalHttpResponse execute(InternalHttpRequest request) {
        return delegate.execute(request.withTimeout(timeout));
    }
}
