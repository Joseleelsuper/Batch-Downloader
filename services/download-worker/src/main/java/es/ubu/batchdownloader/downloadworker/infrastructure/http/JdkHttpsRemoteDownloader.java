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

public class JdkHttpsRemoteDownloader implements RemoteDownloader {
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Logger LOGGER = LoggerFactory.getLogger(JdkHttpsRemoteDownloader.class);

    private final HttpClient client;
    private final PublicHttpsUriPolicy uriPolicy;
    private final DownloadProperties properties;

    public JdkHttpsRemoteDownloader(
            HttpClient client,
            PublicHttpsUriPolicy uriPolicy,
            DownloadProperties properties) {
        this.client = client;
        this.uriPolicy = uriPolicy;
        this.properties = properties;
    }

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

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException exception) {
            LOGGER.debug("Could not close discarded HTTP response", exception);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOGGER.debug("Could not delete partial download {}", path, exception);
        }
    }
}
