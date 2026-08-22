package es.ubu.batchdownloader.common.http;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Mide llamadas internas únicamente con servicio, operación y resultado acotados. */
public final class MeteredInternalHttpExecutor implements InternalHttpExecutor {
    private final InternalHttpExecutor delegate;
    private final MeterRegistry registry;

    /** Inicializa el wrapper de observabilidad. */
    public MeteredInternalHttpExecutor(InternalHttpExecutor delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    /** {@inheritDoc} */
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
