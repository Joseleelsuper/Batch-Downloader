package es.ubu.batchdownloader.common.http;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Mide las llamadas internas por servicio, operación y familia de estado o fallo de transporte, sin
 * registrar sus datos sensibles.
 *
 * @see es.ubu.batchdownloader.common.http.InternalHttpExecutor
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
public final class MeteredInternalHttpExecutor implements InternalHttpExecutor {
    private final InternalHttpExecutor delegate;
    private final MeterRegistry registry;

    /**
     * Conecta la siguiente política y el registro de duración y resultado de cada llamada.
     *
     * @param delegate Siguiente ejecutor de la cadena de transporte y políticas.
     * @param registry Registro donde se publica duración y resultado sin incluir contenido ni URLs.
     */
    public MeteredInternalHttpExecutor(InternalHttpExecutor delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    /**
     * Registra duración incluso ante excepciones y clasifica respuestas por familia 2xx, 4xx u otra
     * recibida.
     *
     * @param request Solicitud HTTP fallida o petición interna según la firma del método.
     * @return misma respuesta del delegado; propaga su excepción si falla.
     */
    @Override
    public InternalHttpResponse execute(InternalHttpRequest request) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "transport_error";
        try {
            InternalHttpResponse response = delegate.execute(request);
            outcome = response.statusCode() / 100 + "xx";
            return response;
        } finally {
            sample.stop(Timer.builder("core_internal_http")
                    .tag("service", request.service())
                    .tag("operation", request.operation())
                    .tag("outcome", outcome)
                    .register(registry));
        }
    }
}
