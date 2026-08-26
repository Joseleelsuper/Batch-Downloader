package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.FailedDownload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/** Mantiene una ventana acotada de descargas y sus presupuestos de disco y tamaño. */
final class DownloadPipeline {
    private final DownloadJobRequestedEvent event;
    private final List<ResolvedDownloadItem> items;
    private final Path jobDirectory;
    private final int window;
    private final RemoteDownloader remoteDownloader;
    private final FilenamePolicy filenamePolicy;
    private final DownloadProperties properties;
    private final DownloadCancellationRegistry cancellations;
    private final DownloadWorkerMetrics metrics;
    private final DownloadEventEmitter events;
    private final Clock clock;
    private final DownloadJobFiles files;
    private final CompletionService<Attempt> completions;
    private final List<Future<Attempt>> futures = new ArrayList<>();
    private final DownloadBudget budget;
    private final Set<String> usedNames;
    private int submitted;
    private int completed;

    DownloadPipeline(
            DownloadJobRequestedEvent event,
            List<ResolvedDownloadItem> items,
            Path jobDirectory,
            int window,
            ExecutorService executor,
            RemoteDownloader remoteDownloader,
            FilenamePolicy filenamePolicy,
            DownloadProperties properties,
            DownloadCancellationRegistry cancellations,
            DownloadWorkerMetrics metrics,
            DownloadEventEmitter events,
            Clock clock,
            DownloadJobFiles files) {
        this.event = event;
        this.items = items;
        this.jobDirectory = jobDirectory;
        this.window = Math.min(window, items.size());
        this.remoteDownloader = remoteDownloader;
        this.filenamePolicy = filenamePolicy;
        this.properties = properties;
        this.cancellations = cancellations;
        this.metrics = metrics;
        this.events = events;
        this.clock = clock;
        this.files = files;
        this.completions = new ExecutorCompletionService<>(executor);
        this.budget = new DownloadBudget(properties.maxTotalSize().toBytes());
        this.usedNames = filenamePolicy.newNameSet();
        while (submitted < this.window) {
            submitNext();
        }
    }

    boolean hasNext() {
        return completed < items.size();
    }

    Attempt next() {
        Attempt attempt = awaitCompleted();
        completed++;
        if (submitted < items.size()) {
            submitNext();
        }
        return attempt;
    }

    private void submitNext() {
        ResolvedDownloadItem item = items.get(submitted++);
        events.progress(
                event, clock.instant(), item.itemId(), "DOWNLOADING",
                0, item.expectedSizeBytes(), null, null);
        try {
            futures.add(completions.submit(() -> downloadOne(item)));
            cancellations.track(event.payload().jobId(), futures);
        } catch (RejectedExecutionException exception) {
            throw new InfrastructureException("download_executor_saturated", exception);
        }
    }

    private Attempt downloadOne(ResolvedDownloadItem resolved) {
        String filename;
        synchronized (usedNames) {
            filename = filenamePolicy.filenameFor(resolved, usedNames);
        }
        Path target = jobDirectory.resolve("files").resolve(filename);
        try {
            metrics.downloadStarted();
            try {
                DownloadedArtifact artifact = remoteDownloader.download(
                        resolved,
                        filename,
                        target,
                        budget,
                        properties.maxFileSize().toBytes());
                metrics.temporaryAdded(artifact.sizeBytes());
                return Attempt.success(artifact);
            } finally {
                metrics.downloadFinished();
            }
        } catch (DownloadRejectedException exception) {
            return Attempt.failure(new FailedDownload(
                    resolved.itemId(), resolved.appId(), resolved.sourceRef(), filename, exception.code()));
        } catch (RuntimeException exception) {
            files.deleteTemporary(target);
            if (cancellations.cancelled(event.payload().jobId())) {
                throw new CancellationException("download_job_cancelled");
            }
            throw exception;
        }
    }

    private Attempt awaitCompleted() {
        try {
            return await(completions.take());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("download_job_interrupted", exception);
        }
    }

    private Attempt await(Future<Attempt> future) {
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

    record Attempt(DownloadedArtifact artifact, FailedDownload failure) {
        private static Attempt success(DownloadedArtifact artifact) {
            return new Attempt(artifact, null);
        }

        private static Attempt failure(FailedDownload failure) {
            return new Attempt(null, failure);
        }
    }
}
