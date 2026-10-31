package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.ports.RemoteExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
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
     * @return respuesta adaptada sin materializar el cuerpo completo.
     * @throws es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException si se
     *     interrumpe la petición, expira el timeout o falla la E/S remota.
     */
    @Override
    public Response get(URI uri) {
        long startedAt = System.nanoTime();
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(properties.requestTimeout())
                .header("Accept", "application/octet-stream,*/*")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "BatchDownloaderWorker/1.0")
                .GET();
        if ("github.com".equalsIgnoreCase(uri.getHost()) && uri.getPath().contains("/releases/download/")) {
            requestBuilder.header("Cache-Control", "no-cache");
        }
        HttpRequest request = requestBuilder.build();
        try {
            HttpResponse<InputStream> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream());
            long remaining = properties.requestTimeout().toNanos() - (System.nanoTime() - startedAt);
            return new JdkResponse(response, new DeadlineInputStream(response.body(), remaining));
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
    private record JdkResponse(HttpResponse<InputStream> delegate, InputStream body) implements Response {
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

    }

    private static ScheduledThreadPoolExecutor deadlines() {
        var executor = new ScheduledThreadPoolExecutor(
                1, Thread.ofPlatform().daemon().name("remote-body-deadline").factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** Cierra el cuerpo al vencer el plazo para desbloquear también una lectura sin datos. */
    private static final class DeadlineInputStream extends FilterInputStream {
        private final AtomicBoolean expired = new AtomicBoolean();
        private final ScheduledFuture<?> deadline;

        private DeadlineInputStream(InputStream input, long remainingNanos) {
            super(input);
            deadline = DEADLINES.schedule(() -> {
                expired.set(true);
                try { input.close(); } catch (IOException ignored) { /* La lectura informa el fallo. */ }
            }, Math.max(0, remainingNanos), TimeUnit.NANOSECONDS);
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(single[0]);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            requireTime();
            try {
                int count = in.read(bytes, offset, length);
                requireTime();
                return count;
            } catch (IOException exception) {
                if (expired.get()) throw new DownloadRejectedException("remote_timeout", exception);
                throw exception;
            }
        }

        private void requireTime() {
            if (expired.get()) throw new DownloadRejectedException("remote_timeout");
        }

        @Override
        public void close() throws IOException {
            deadline.cancel(false);
            super.close();
        }
    }
}
