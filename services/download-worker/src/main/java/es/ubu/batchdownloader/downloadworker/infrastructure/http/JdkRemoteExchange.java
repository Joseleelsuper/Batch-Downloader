package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.ports.RemoteExchange;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;

/**
 * Abre respuestas HTTP JDK con cuerpo en streaming, timeout configurado y codificación identity,
 * dejando la interpretación de estado al descargador.
 *
 * @see es.ubu.batchdownloader.downloadworker.ports.RemoteExchange
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.DefaultRemoteDownloader
 * @since 0.1.0
 * @version 0.1.0
 * @category Transporte de descargas
 */
public final class JdkRemoteExchange implements RemoteExchange {
    private final HttpClient client;
    private final DownloadProperties properties;

    /**
     * Conecta el cliente y el timeout que se aplica a cada petición.
     *
     * @param client Cliente HTTP JDK que proporciona conexiones y respuestas con cuerpo en
     *     streaming.
     * @param properties Límites de tamaño, tiempo y redirecciones configurados para la
     *     transferencia.
     */
    public JdkRemoteExchange(HttpClient client, DownloadProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * Solicita contenido binario por GET con cabeceras estables y entrega el flujo al consumidor
     * para su cierre.
     *
     * @param uri URI de destino que debe superar la política de acceso a recursos públicos.
     * @return respuesta adaptada sin materializar todo el cuerpo.
     * @throws es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException si se
     *     interrumpe la petición, expira el timeout o falla la E/S remota.
     */
    @Override
    public Response get(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.requestTimeout())
                .header("Accept", "application/octet-stream,*/*")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "BatchDownloaderWorker/1.0")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream());
            return new JdkResponse(response);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DownloadRejectedException("download_interrupted", exception);
        } catch (HttpTimeoutException exception) {
            throw new DownloadRejectedException("remote_timeout", exception);
        } catch (IOException exception) {
            throw new DownloadRejectedException("remote_io_error", exception);
        }
    }

    /**
     * Adapta estado, cabeceras y flujo de HttpResponse al puerto de intercambio sin copiar el
     * contenido.
     *
     * @param delegate Respuesta JDK con su flujo todavía abierto.
     * @since 0.1.0
     * @version 0.1.0
     * @category Transporte de descargas
     */
    private record JdkResponse(HttpResponse<InputStream> delegate) implements Response {
        /**
         * {@inheritDoc}
         */
        @Override
        public int statusCode() {
            return delegate.statusCode();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public java.net.http.HttpHeaders headers() {
            return delegate.headers();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public InputStream body() {
            return delegate.body();
        }
    }
}
