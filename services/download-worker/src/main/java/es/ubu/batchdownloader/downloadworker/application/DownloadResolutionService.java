package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.FailedDownload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.SourceReferenceResolver;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/** Resuelve fuentes en una ventana acotada antes de reservar capacidad de descarga. */
final class DownloadResolutionService {
    private final SourceReferenceResolver resolver;
    private final ExecutorService executor;
    private final DownloadProperties properties;
    private final DownloadCancellationRegistry cancellations;
    private final DownloadEventEmitter events;
    private final Clock clock;

    DownloadResolutionService(
            SourceReferenceResolver resolver,
            ExecutorService executor,
            DownloadProperties properties,
            DownloadCancellationRegistry cancellations,
            DownloadEventEmitter events,
            Clock clock) {
        this.resolver = resolver;
        this.executor = executor;
        this.properties = properties;
        this.cancellations = cancellations;
        this.events = events;
        this.clock = clock;
    }

    String invalidReason(DownloadJobRequestedEvent event) {
        if (event.payload().items().size() > properties.maxItems()) {
            return "too_many_items";
        }
        Set<UUID> itemIds = new HashSet<>();
        for (DownloadItemRequest item : event.payload().items()) {
            if (!itemIds.add(item.itemId())) {
                return "duplicate_item_id";
            }
        }
        return null;
    }

    PreparedDownloads resolve(DownloadJobRequestedEvent event) {
        List<DownloadItemRequest> items = event.payload().items();
        CompletionService<ResolutionAttempt> completions = new ExecutorCompletionService<>(executor);
        List<Future<ResolutionAttempt>> futures = new ArrayList<>();
        int next = 0;
        int completed = 0;
        int window = Math.min(properties.perJobConcurrency(), items.size());
        while (next < window) {
            submit(event, items.get(next++), completions, futures);
        }
        List<ResolvedDownloadItem> resolved = new ArrayList<>();
        List<FailedDownload> failed = new ArrayList<>();
        while (completed < items.size()) {
            ResolutionAttempt attempt = awaitCompleted(completions);
            completed++;
            if (attempt.resolved() != null) {
                resolved.add(attempt.resolved());
            } else {
                failed.add(attempt.failure());
                events.progress(
                        event, clock.instant(), attempt.failure().itemId(), "FAILED",
                        0, null, null, attempt.failure().errorCode());
            }
            if (next < items.size()) {
                submit(event, items.get(next++), completions, futures);
            }
            if (cancellations.cancelled(event.payload().jobId())) {
                futures.forEach(future -> future.cancel(true));
                throw new CancellationException("download_job_cancelled");
            }
        }
        return new PreparedDownloads(List.copyOf(resolved), List.copyOf(failed));
    }

    int capacityWeight(List<ResolvedDownloadItem> items) {
        long total = 0;
        for (ResolvedDownloadItem item : items) {
            if (item.expectedSizeBytes() == null || item.expectedSizeBytes() < 0) {
                return properties.jobConcurrency();
            }
            if (Long.MAX_VALUE - total < item.expectedSizeBytes()) {
                return properties.jobConcurrency();
            }
            total += item.expectedSizeBytes();
            if (total > properties.largeJobThreshold().toBytes()) {
                return properties.jobConcurrency();
            }
        }
        return 1;
    }

    /**
     * Calcula la reserva completa previa. Un tamaño desconocido promete el máximo del trabajo;
     * los declarados nunca reservan menos que su suma ni más que el límite que el pipeline acepta.
     */
    long estimatedBytes(List<ResolvedDownloadItem> items) {
        long maximum = properties.maxTotalSize().toBytes();
        long total = 0;
        for (ResolvedDownloadItem item : items) {
            Long expected = item.expectedSizeBytes();
            if (expected == null || expected < 0) return maximum;
            try {
                total = Math.addExact(total, expected);
            } catch (ArithmeticException exception) {
                return maximum;
            }
            if (total >= maximum) return maximum;
        }
        return total;
    }

    private void submit(
            DownloadJobRequestedEvent event,
            DownloadItemRequest item,
            CompletionService<ResolutionAttempt> completions,
            List<Future<ResolutionAttempt>> futures) {
        events.progress(event, clock.instant(), item.itemId(), "RESOLVING", 0, null, null, null);
        try {
            futures.add(completions.submit(() -> resolveOne(item)));
            cancellations.track(event.payload().jobId(), futures);
        } catch (RejectedExecutionException exception) {
            throw new InfrastructureException("download_executor_saturated", exception);
        }
    }

    private ResolutionAttempt resolveOne(DownloadItemRequest item) {
        if (item.sourceRef() == null) {
            return ResolutionAttempt.failure(new FailedDownload(
                    item.itemId(), item.appId(), null,
                    "manual-" + item.itemId() + ".url", "manual_download_required"));
        }
        try {
            return ResolutionAttempt.success(resolver.resolve(item));
        } catch (DownloadRejectedException exception) {
            return ResolutionAttempt.failure(new FailedDownload(
                    item.itemId(), item.appId(), item.sourceRef(),
                    "installer-" + item.itemId() + ".bin", exception.code()));
        }
    }

    private <T> T awaitCompleted(CompletionService<T> completions) {
        try {
            return await(completions.take());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("download_job_interrupted", exception);
        }
    }

    private <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("download_job_interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new InfrastructureException("download_task_failed", cause);
        }
    }

    record PreparedDownloads(
            List<ResolvedDownloadItem> resolved,
            List<FailedDownload> failed) {}

    private record ResolutionAttempt(ResolvedDownloadItem resolved, FailedDownload failure) {
        private static ResolutionAttempt success(ResolvedDownloadItem resolved) {
            return new ResolutionAttempt(resolved, null);
        }

        private static ResolutionAttempt failure(FailedDownload failure) {
            return new ResolutionAttempt(null, failure);
        }
    }
}
