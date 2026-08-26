package es.ubu.batchdownloader.common.http;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Descripción inmutable de una petición interna sin políticas de transporte embebidas. */
public final class InternalHttpRequest {
    private final String service;
    private final String operation;
    private final String method;
    private final URI uri;
    private final String body;
    private final Duration timeout;
    private final Map<String, String> headers;

    /** Inicializa una petición sin cabeceras ni timeout transversal. */
    public InternalHttpRequest(
            String service,
            String operation,
            String method,
            URI uri,
            String body) {
        this(service, operation, method, uri, body, null, Map.of());
    }

    private InternalHttpRequest(
            String service,
            String operation,
            String method,
            URI uri,
            String body,
            Duration timeout,
            Map<String, String> headers) {
        this.service = Objects.requireNonNull(service);
        this.operation = Objects.requireNonNull(operation);
        this.method = Objects.requireNonNull(method);
        this.uri = Objects.requireNonNull(uri);
        this.body = body;
        this.timeout = timeout;
        this.headers = Map.copyOf(headers);
    }

    /** Devuelve una copia con una cabecera adicional o sustituida. */
    public InternalHttpRequest withHeader(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new InternalHttpRequest(
                service, operation, method, uri, body, timeout, copy);
    }

    /** Devuelve una copia con el límite temporal indicado. */
    public InternalHttpRequest withTimeout(Duration value) {
        return new InternalHttpRequest(
                service, operation, method, uri, body, value, headers);
    }

    public String service() {
        return service;
    }

    public String operation() {
        return operation;
    }

    public String method() {
        return method;
    }

    public URI uri() {
        return uri;
    }

    public String body() {
        return body;
    }

    public Duration timeout() {
        return timeout;
    }

    public Map<String, String> headers() {
        return headers;
    }
}
