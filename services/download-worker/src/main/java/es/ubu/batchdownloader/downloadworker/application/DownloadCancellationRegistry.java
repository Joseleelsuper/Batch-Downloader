package es.ubu.batchdownloader.downloadworker.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Coordinates a best-effort, cooperative stop across command and processing listener threads. */
@Component
public class DownloadCancellationRegistry {
    private static final Duration RETENTION = Duration.ofMinutes(30);

    private final ConcurrentHashMap<UUID, Instant> cancellations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<Future<?>>> activeTasks = new ConcurrentHashMap<>();

    public void cancel(UUID jobId) {
        cancellations.put(jobId, Instant.now());
        activeTasks.getOrDefault(jobId, List.of()).forEach(task -> task.cancel(true));
    }

    public boolean cancelled(UUID jobId) {
        return cancellations.containsKey(jobId);
    }

    public void track(UUID jobId, List<? extends Future<?>> tasks) {
        List<Future<?>> copy = List.copyOf(tasks);
        activeTasks.put(jobId, copy);
        if (cancelled(jobId)) {
            copy.forEach(task -> task.cancel(true));
        }
    }

    public void finish(UUID jobId) {
        activeTasks.remove(jobId);
        cancellations.remove(jobId);
    }

    @Scheduled(fixedDelay = 300_000)
    void expireUnclaimedCancellations() {
        Instant cutoff = Instant.now().minus(RETENTION);
        cancellations.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }
}
