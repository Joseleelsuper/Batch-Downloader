package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementa el componente {@code JdkHttpsRemoteDownloader}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class JdkHttpsRemoteDownloader implements RemoteDownloader {
    /**
     * Constante que define {@code REDIRECT_STATUSES}.
     */
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    /**
     * Constante que define {@code LOGGER}.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(JdkHttpsRemoteDownloader.class);

    /**
     * Estado {@code client} mantenido por {@code JdkHttpsRemoteDownloader}.
     */
    private final HttpClient client;
    /**
     * Estado {@code uriPolicy} mantenido por {@code JdkHttpsRemoteDownloader}.
     */
    private final PublicHttpsUriPolicy uriPolicy;
    /**
     * Estado {@code properties} mantenido por {@code JdkHttpsRemoteDownloader}.
     */
    private final DownloadProperties properties;

    /**
     * Inicializa una instancia de {@code JdkHttpsRemoteDownloader}.
     *
     * @param client Valor de {@code client} utilizado por la operación.
     * @param uriPolicy Valor de {@code uriPolicy} utilizado por la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     */
    public JdkHttpsRemoteDownloader(
            HttpClient client,
            PublicHttpsUriPolicy uriPolicy,
            DownloadProperties properties) {
        this.client = client;
        this.uriPolicy = uriPolicy;
        this.properties = properties;
    }

    /**
     * Implementa {@code download} para {@code JdkHttpsRemoteDownloader}.
     *
     * @param item Elemento sobre el que se realiza la operación.
     * @param filename Valor de {@code filename} utilizado por la operación.
     * @param target Valor de {@code target} utilizado por la operación.
     * @param totalBudget Valor de {@code totalBudget} utilizado por la operación.
     * @param requestedMaxFileBytes Valor de {@code requestedMaxFileBytes} utilizado por la
     *     operación.
     * @return Resultado producido por {@code download}.
     * @throws DownloadRejectedException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    @Override
    public DownloadedArtifact download(
            ResolvedDownloadItem item,
            String filename,
            Path target,
            DownloadBudget totalBudget,
            long requestedMaxFileBytes) {
        long maxFileBytes = Math.min(requestedMaxFileBytes, properties.maxFileSize().toBytes());
        URI current = item.url();
        for (int redirects = 0; redirects <= properties.maxRedirects(); redirects++) {
            uriPolicy.validate(current);
            HttpResponse<InputStream> response = send(current);
            if (REDIRECT_STATUSES.contains(response.statusCode())) {
                closeQuietly(response.body());
                if (redirects == properties.maxRedirects()) {
                    throw new DownloadRejectedException("too_many_redirects");
                }
                String location = response.headers().firstValue("location")
                        .orElseThrow(() -> new DownloadRejectedException("redirect_without_location"));
                try {
                    current = current.resolve(location);
                } catch (IllegalArgumentException exception) {
                    throw new DownloadRejectedException("invalid_redirect", exception);
                }
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeQuietly(response.body());
                throw new DownloadRejectedException("remote_http_" + response.statusCode());
            }
            verifyDeclaredSize(response, maxFileBytes);
            verifyResponseMetadata(response);
            return streamToDisk(item, filename, target, response.body(), totalBudget, maxFileBytes);
        }
        throw new DownloadRejectedException("too_many_redirects");
    }

    /**
     * Envía el contenido solicitado mediante {@code send}.
     *
     * @param uri Valor de {@code uri} utilizado por la operación.
     * @return Resultado producido por {@code send}.
     * @throws DownloadRejectedException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private HttpResponse<InputStream> send(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.requestTimeout())
                .header("Accept", "application/octet-stream,*/*")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "BatchDownloaderWorker/1.0")
                .GET()
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DownloadRejectedException("download_interrupted", exception);
        } catch (IOException exception) {
            throw new DownloadRejectedException("remote_io_error", exception);
        }
    }

    /**
     * Verifica los datos recibidos mediante {@code verifyDeclaredSize}.
     *
     * @param response Respuesta que debe procesarse.
     * @param maxFileBytes Valor de {@code maxFileBytes} utilizado por la operación.
     */
    private void verifyDeclaredSize(
            HttpResponse<InputStream> response,
            long maxFileBytes) {
        response.headers().firstValueAsLong("content-length").ifPresent(length -> {
            if (length > maxFileBytes) {
                closeQuietly(response.body());
                throw new DownloadRejectedException("file_size_limit_exceeded");
            }
        });
    }

    /**
     * Verifica los datos recibidos mediante {@code verifyResponseMetadata}.
     *
     * @param response Respuesta que debe procesarse.
     * @throws DownloadRejectedException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private void verifyResponseMetadata(HttpResponse<InputStream> response) {
        String contentEncoding = response.headers().firstValue("content-encoding").orElse("identity");
        if (!contentEncoding.equalsIgnoreCase("identity")) {
            closeQuietly(response.body());
            throw new DownloadRejectedException("encoded_response_not_supported");
        }
        String contentType = response.headers().firstValue("content-type")
                .orElse("")
                .toLowerCase(java.util.Locale.ROOT);
        if (contentType.startsWith("text/html") || contentType.startsWith("application/json")) {
            closeQuietly(response.body());
            throw new DownloadRejectedException("unexpected_download_content_type");
        }
    }

    /**
     * Ejecuta la operación {@code streamToDisk}.
     *
     * @param item Elemento sobre el que se realiza la operación.
     * @param filename Valor de {@code filename} utilizado por la operación.
     * @param target Valor de {@code target} utilizado por la operación.
     * @param input Valor de {@code input} utilizado por la operación.
     * @param totalBudget Valor de {@code totalBudget} utilizado por la operación.
     * @param maxFileBytes Valor de {@code maxFileBytes} utilizado por la operación.
     * @return Resultado producido por {@code streamToDisk}.
     * @throws DownloadRejectedException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private DownloadedArtifact streamToDisk(
            ResolvedDownloadItem item,
            String filename,
            Path target,
            InputStream input,
            DownloadBudget totalBudget,
            long maxFileBytes) {
        long fileBytes = 0;
        MessageDigest digest = sha256Digest();
        try {
            Files.createDirectories(target.getParent());
            try (input; OutputStream output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new DownloadRejectedException("download_interrupted");
                    }
                    if (read == 0) {
                        continue;
                    }
                    fileBytes += read;
                    if (fileBytes > maxFileBytes) {
                        throw new DownloadRejectedException("file_size_limit_exceeded");
                    }
                    totalBudget.consume(read);
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            if (item.expectedSha256() != null && !sha256.equalsIgnoreCase(item.expectedSha256())) {
                throw new DownloadRejectedException("source_sha256_mismatch");
            }
            return new DownloadedArtifact(
                    item.itemId(),
                    item.appId(),
                    item.sourceRef(),
                    filename,
                    target,
                    fileBytes,
                    sha256,
                    null);
        } catch (IOException | RuntimeException exception) {
            deleteQuietly(target);
            if (exception instanceof DownloadRejectedException rejected) {
                throw rejected;
            }
            throw new DownloadRejectedException("local_io_error", exception);
        }
    }

    /**
     * Ejecuta la operación {@code sha256Digest}.
     *
     * @return Resultado producido por {@code sha256Digest}.
     * @throws IllegalStateException Si el estado actual impide completar la operación.
     */
    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    /**
     * Ejecuta la operación {@code closeQuietly}.
     *
     * @param input Valor de {@code input} utilizado por la operación.
     */
    private void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException exception) {
            LOGGER.debug("Could not close discarded HTTP response", exception);
        }
    }

    /**
     * Elimina el recurso solicitado mediante {@code deleteQuietly}.
     *
     * @param path Ruta del recurso que debe procesarse.
     */
    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOGGER.debug("Could not delete partial download {}", path, exception);
        }
    }
}
