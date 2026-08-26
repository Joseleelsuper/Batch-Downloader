package es.ubu.batchdownloader.common.http;

import java.time.Duration;

/** Impone el límite temporal común sin repetirlo en cada cliente funcional. */
public final class TimeoutInternalHttpExecutor implements InternalHttpExecutor {
    private final InternalHttpExecutor delegate;
    private final Duration timeout;

    /** Inicializa el wrapper de timeout. */
    public TimeoutInternalHttpExecutor(InternalHttpExecutor delegate, Duration timeout) {
        this.delegate = delegate;
        this.timeout = timeout;
    }

    /** {@inheritDoc} */
    @Override
    public InternalHttpResponse execute(InternalHttpRequest request) {
        return delegate.execute(request.withTimeout(timeout));
    }
}
