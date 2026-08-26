package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/** Reintenta dos veces solo timeouts y estados HTTP explícitamente transitorios. */
public final class RetryingRemoteDownloader implements RemoteDownloader {
    private static final int MAX_RETRIES = 2;
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);
    private static final Set<String> RETRYABLE_EXACT = Set.of(
            "remote_timeout", "remote_http_408", "remote_http_429");
    private final RemoteDownloader delegate;
    private final MeterRegistry registry;

    /** Inicializa el wrapper de reintentos íntegros. */
    public RetryingRemoteDownloader(RemoteDownloader delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    /** {@inheritDoc} */
    @Override
    public DownloadedArtifact download(
            ResolvedDownloadItem item,
            String filename,
            Path target,
            DownloadBudget totalBudget,
            long maxFileBytes) {
        for (int attempt = 0; ; attempt++) {
            try {
                DownloadBudget attemptBudget = new DownloadBudget(maxFileBytes);
                DownloadedArtifact artifact = delegate.download(
                        item, filename, target, attemptBudget, maxFileBytes);
                totalBudget.consume(artifact.sizeBytes());
                return artifact;
            } catch (DownloadRejectedException exception) {
                if (attempt >= MAX_RETRIES || !retryable(exception.code())) throw exception;
                deletePartial(target, exception);
                registry.counter("download_worker_remote_retries", "reason", exception.code()).increment();
                pause(delay(exception, attempt));
            }
        }
    }

    private boolean retryable(String code) {
        if (RETRYABLE_EXACT.contains(code)) return true;
        if (!code.startsWith("remote_http_5")) return false;
        try {
            int status = Integer.parseInt(code.substring("remote_http_".length()));
            return status >= 500 && status <= 599;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private Duration delay(DownloadRejectedException exception, int attempt) {
        Duration requested = exception.retryAfter();
        if (requested == null) requested = Duration.ofSeconds(1L << attempt);
        if (requested.isNegative()) return Duration.ZERO;
        return requested.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : requested;
    }

    private void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DownloadRejectedException("download_interrupted", exception);
        }
    }

    private void deletePartial(Path target, RuntimeException original) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            original.addSuppressed(exception);
            throw original;
        }
    }
}
