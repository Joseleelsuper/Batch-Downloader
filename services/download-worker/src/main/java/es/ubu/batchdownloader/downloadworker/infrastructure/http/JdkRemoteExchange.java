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

/** Transporte JDK que ejecuta exactamente un GET y nunca sigue redirecciones. */
public final class JdkRemoteExchange implements RemoteExchange {
    private final HttpClient client;
    private final DownloadProperties properties;

    /** Inicializa el transporte con los límites ya configurados para el worker. */
    public JdkRemoteExchange(HttpClient client, DownloadProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** {@inheritDoc} */
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
        } catch (IOException exception) {
            throw new DownloadRejectedException("remote_io_error", exception);
        }
    }

    /** Adaptador cerrado sobre la respuesta nativa del JDK. */
    private record JdkResponse(HttpResponse<InputStream> delegate) implements Response {
        @Override
        public int statusCode() {
            return delegate.statusCode();
        }

        @Override
        public java.net.http.HttpHeaders headers() {
            return delegate.headers();
        }

        @Override
        public InputStream body() {
            return delegate.body();
        }
    }
}
