package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import es.ubu.batchdownloader.downloadworker.ports.RemoteExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementación funcional de la descarga sobre un exchange seguro de un solo salto.
 *
 * <p>La comparación con el hash esperado, el cleanup y las métricas se componen fuera de esta
 * clase. El SHA-256 producido sigue formando parte del artefacto descargado.</p>
 */
public final class DefaultRemoteDownloader implements RemoteDownloader {
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultRemoteDownloader.class);
    private final RemoteExchange exchange;
    private final DownloadProperties properties;

    /** Inicializa la operación base. */
    public DefaultRemoteDownloader(RemoteExchange exchange, DownloadProperties properties) {
        this.exchange = exchange;
        this.properties = properties;
    }

    /** {@inheritDoc} */
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
            RemoteExchange.Response response = exchange.get(current);
            if (REDIRECT_STATUSES.contains(response.statusCode())) {
                try {
                    if (redirects == properties.maxRedirects()) {
                        throw new DownloadRejectedException("too_many_redirects");
                    }
                    current = resolveRedirect(current, response);
                } finally {
                    closeQuietly(response);
                }
                continue;
            }
            try (response) {
                requireSuccessful(response);
                verifyDeclaredSize(response, maxFileBytes);
                verifyResponseMetadata(response);
                return streamToDisk(
                        item,
                        filename,
                        target,
                        response.body(),
                        totalBudget,
                        maxFileBytes);
            } catch (IOException exception) {
                throw new DownloadRejectedException("local_io_error", exception);
            }
        }
        throw new DownloadRejectedException("too_many_redirects");
    }

    private URI resolveRedirect(URI current, RemoteExchange.Response response) {
        String location = response.headers().firstValue("location")
                .orElseThrow(() -> new DownloadRejectedException("redirect_without_location"));
        try {
            return current.resolve(location);
        } catch (IllegalArgumentException exception) {
            throw new DownloadRejectedException("invalid_redirect", exception);
        }
    }

    private void requireSuccessful(RemoteExchange.Response response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new DownloadRejectedException("remote_http_" + response.statusCode());
        }
    }

    private void verifyDeclaredSize(RemoteExchange.Response response, long maxFileBytes) {
        response.headers().firstValueAsLong("content-length").ifPresent(length -> {
            if (length > maxFileBytes) {
                throw new DownloadRejectedException("file_size_limit_exceeded");
            }
        });
    }

    private void verifyResponseMetadata(RemoteExchange.Response response) {
        String contentEncoding = response.headers()
                .firstValue("content-encoding")
                .orElse("identity");
        if (!contentEncoding.equalsIgnoreCase("identity")) {
            throw new DownloadRejectedException("encoded_response_not_supported");
        }
        String contentType = response.headers()
                .firstValue("content-type")
                .orElse("")
                .toLowerCase(Locale.ROOT);
        if (contentType.startsWith("text/html") || contentType.startsWith("application/json")) {
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
            try (OutputStream output = Files.newOutputStream(target)) {
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
        } catch (IOException exception) {
            throw new DownloadRejectedException("local_io_error", exception);
        } catch (RuntimeException exception) {
            if (exception instanceof DownloadRejectedException rejected) {
                throw rejected;
            }
            throw new DownloadRejectedException("local_io_error", exception);
        }
        String sha256 = HexFormat.of().formatHex(digest.digest());
        return new DownloadedArtifact(
                item.itemId(),
                item.appId(),
                item.sourceRef(),
                filename,
                target,
                fileBytes,
                sha256,
                null);
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void closeQuietly(RemoteExchange.Response response) {
        try {
            response.close();
        } catch (IOException exception) {
            LOGGER.debug("Could not close discarded HTTP response", exception);
        }
    }
}
