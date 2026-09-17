package es.ubu.batchdownloader.common.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Ejecuta peticiones internas con HttpClient del JDK y conserva la interrupción del hilo al
 * traducir fallos de transporte.
 *
 * @see es.ubu.batchdownloader.common.http.InternalHttpExecutor
 * @see es.ubu.batchdownloader.common.http.InternalHttpTransportException
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
public final class JdkInternalHttpExecutor implements InternalHttpExecutor {
    private final HttpClient client;

    /**
     * Conecta el transporte JDK utilizado para enviar peticiones sincrónicas.
     *
     * @param client Cliente HTTP JDK que ejecuta la solicitud sincrónica.
     */
    public JdkInternalHttpExecutor(HttpClient client) {
        this.client = client;
    }

    /**
     * Aplica plazo, cabeceras, verbo y cuerpo explícitos; devuelve cualquier estado HTTP y traduce
     * interrupción o IOException.
     *
     * @param request Solicitud HTTP fallida o petición interna según la firma del método.
     * @return respuesta textual sin clasificar su estado de negocio.
     */
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
