package es.ubu.batchdownloader.common.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Adaptador JDK que sólo se ocupa de construir y ejecutar una petición HTTP. */
public final class JdkInternalHttpExecutor implements InternalHttpExecutor {
    private final HttpClient client;

    /** Inicializa el adaptador sobre un cliente reutilizable. */
    public JdkInternalHttpExecutor(HttpClient client) {
        this.client = client;
    }

    /** {@inheritDoc} */
    @Override
    public InternalHttpResponse execute(InternalHttpRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri());
        if (request.timeout() != null) {
            builder.timeout(request.timeout());
        }
        request.headers().forEach(builder::header);
        builder.method(
                request.method(),
                request.body() == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(request.body()));
        try {
            HttpResponse<String> response = client.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            return new InternalHttpResponse(
                    response.statusCode(), response.headers(), response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InternalHttpTransportException(true, exception);
        } catch (IOException exception) {
            throw new InternalHttpTransportException(false, exception);
        }
    }
}
