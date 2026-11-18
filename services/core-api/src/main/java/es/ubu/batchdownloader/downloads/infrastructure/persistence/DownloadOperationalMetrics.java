package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import es.ubu.batchdownloader.downloads.application.DownloadStorageBudget;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Instantánea agregada de MySQL; los scrapes no consultan SQL ni generan etiquetas de usuario. */
@Component
final class DownloadOperationalMetrics {
    private record Storage(long reserved, long queued, long oldest) {}
    private record Snapshot(Map<DownloadJobStatus, Long> jobs, Storage storage, long sampledAt) {}
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(
            new Snapshot(Map.of(), new Storage(0, 0, 0), 0));
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Counter failures;

    DownloadOperationalMetrics(JdbcTemplate jdbc, Clock clock, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.clock = clock;
        for (DownloadJobStatus state : DownloadJobStatus.values()) {
            Gauge.builder("download_jobs", this, value -> value.snapshot.get().jobs().getOrDefault(state, 0L))
                    .description("Trabajos conservados en MySQL; no es un contador histórico")
                    .tag("state", state.name()).register(registry);
        }
        Gauge.builder("download_storage_reserved_bytes", this, value -> value.snapshot.get().storage().reserved())
                .register(registry);
        Gauge.builder("download_storage_limit_bytes", () -> DownloadStorageBudget.LIMIT).register(registry);
        Gauge.builder("download_storage_queue_depth", this, value -> value.snapshot.get().storage().queued()).register(registry);
        Gauge.builder("download_storage_oldest_queued_seconds", this, value -> value.snapshot.get().storage().oldest()).register(registry);
        Gauge.builder("download_metrics_last_success_timestamp_seconds", this, value -> value.snapshot.get().sampledAt()).register(registry);
        failures = registry.counter("download_metrics_refresh_failures");
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 15_000)
    void refresh() {
        try {
            var states = new EnumMap<DownloadJobStatus, Long>(DownloadJobStatus.class);
            jdbc.query("SELECT status,COUNT(*) AS job_count FROM download_jobs GROUP BY status", (RowCallbackHandler) row -> {
                try { states.put(DownloadJobStatus.valueOf(row.getString("status")), row.getLong("job_count")); }
                catch (IllegalArgumentException ignored) { /* Nunca crear etiquetas desde valores de la base. */ }
            });
            Storage storage = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(s.reserved_bytes),0) AS reserved_bytes,
                      COALESCE(SUM(CASE WHEN s.phase IN ('ESTIMATING','QUEUED') THEN 1 ELSE 0 END),0) AS queued,
                      COALESCE(MAX(CASE WHEN s.phase IN ('ESTIMATING','QUEUED')
                        THEN GREATEST(0,TIMESTAMPDIFF(SECOND,j.created_at,?)) ELSE 0 END),0) AS oldest
                    FROM download_job_storage s JOIN download_jobs j ON j.id=s.job_id
                    """, (row, index) -> new Storage(row.getLong("reserved_bytes"), row.getLong("queued"), row.getLong("oldest")),
                    LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            if (storage == null) throw new DataRetrievalFailureException("Missing aggregate snapshot");
            snapshot.set(new Snapshot(Map.copyOf(states), storage, clock.instant().getEpochSecond()));
        } catch (DataAccessException exception) {
            failures.increment(); // Conservar la última muestra y su fecha permite detectar datos obsoletos.
        }
    }
}
