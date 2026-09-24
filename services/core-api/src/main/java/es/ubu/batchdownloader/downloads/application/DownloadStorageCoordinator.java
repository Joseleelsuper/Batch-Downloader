package es.ubu.batchdownloader.downloads.application;

import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.ServiceUnavailableException;
import es.ubu.batchdownloader.downloads.application.port.DownloadEventPublisher;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.application.port.DownloadStorage;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobNotifier;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner.RequestOwner;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Reservas FIFO duraderas. Las operaciones HTTP y de disco siempre ocurren fuera del bloqueo SQL. */
@Service
public class DownloadStorageCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(DownloadStorageCoordinator.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DownloadJobStore jobs;
    private final DownloadEventPublisher events;
    private final DownloadStorage storage;
    private final DownloadJobNotifier notifier;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean ticking = new AtomicBoolean();
    private final Set<UUID> operations = ConcurrentHashMap.newKeySet();
    private volatile boolean reconciled;

    public DownloadStorageCoordinator(JdbcTemplate jdbc, TransactionTemplate transactions,
            DownloadJobStore jobs, DownloadEventPublisher events, DownloadStorage storage, Clock clock, DownloadJobNotifier notifier) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.jobs = jobs;
        this.events = events;
        this.storage = storage;
        this.clock = clock;
        this.notifier = notifier;
    }

    public record StorageReply(boolean allowed, long reservedBytes, boolean cancelled, long budgetBytes) {}
    private record Entry(UUID id, long sequence, Long estimated, long required, long reserved,
            String phase, String attempt, String delivery, long delivered, int transfers) {}
    private record Source(UUID id, Long bytes) {}

    /** Participa en la transacción de creación; el trabajo ya debe haberse insertado. */
    public void enqueue(DownloadJob job) {
        Timestamp now = now();
        jdbc.update("INSERT INTO download_job_storage (job_id,last_activity_at,last_progress_at,last_worker_at,retry_at) VALUES (?,?,?,?,?)",
                job.id().toString(), now, now, now, now);
        jdbc.update("INSERT INTO download_job_receipts(job_id,owner_id,anonymous_owner_hash,anonymous_ip_hash,created_at) SELECT id,owner_id,anonymous_owner_hash,anonymous_ip_hash,created_at FROM download_jobs WHERE id=?", job.id().toString());
    }

    private <T> T locked(Supplier<T> action) {
        return transactions.execute(status -> {
            // ponytail: una fila serializa reservas globales; particionar solo con almacenes independientes.
            jobs.lockAdmission();
            return action.get();
        });
    }

    private Timestamp now() { return Timestamp.from(clock.instant()); }

    private Entry entry(UUID id) {
        var found = jdbc.query("SELECT * FROM download_job_storage WHERE job_id = ?", (rs, n) -> new Entry(
                UUID.fromString(rs.getString("job_id")), rs.getLong("queue_sequence"),
                (Long) rs.getObject("estimated_bytes"), rs.getLong("required_bytes"), rs.getLong("reserved_bytes"),
                rs.getString("phase"), rs.getString("attempt_id"), rs.getString("delivery_status"),
                rs.getLong("delivery_bytes"), rs.getInt("active_transfers")), id.toString());
        return found.isEmpty() ? null : found.getFirst();
    }

    private long used() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(reserved_bytes),0) FROM download_job_storage", Long.class);
    }

    public DownloadJobView decorate(DownloadJobView view) {
        Entry row = entry(view.id());
        if (row == null) return view;
        Long position = (row.phase().equals("QUEUED") || row.phase().equals("ESTIMATING"))
                ? jdbc.queryForObject("SELECT COUNT(*) FROM download_job_storage WHERE phase IN ('ESTIMATING','QUEUED') AND queue_sequence <= ?", Long.class, row.sequence())
                : null;
        return view.withStorage(row.estimated(), row.reserved(), position, row.delivery(), row.delivered());
    }

    public boolean acceptsEvent(UUID jobId, String attemptId, String type) {
        return locked(() -> {
            Entry row = entry(jobId);
            return row != null && attemptId != null && attemptId.equals(row.attempt())
                    && (List.of("RUNNING", "READY").contains(row.phase())
                        || row.phase().equals("CLEANED") && type.equals("download.job.failed"));
        });
    }

    /** Fencing del intento y contabilidad antes de escribir; nunca presta bytes sin reserva. */
    public StorageReply worker(UUID jobId, UUID attemptId, String action, long bytes) {
        if (bytes < 0) throw new BadRequestException("invalid_storage_bytes", "Los bytes deben ser positivos.");
        return locked(() -> {
            Entry row = entry(jobId);
            if (row == null || !attemptId.toString().equals(row.attempt()) || !List.of("RUNNING", "READY").contains(row.phase()))
                return new StorageReply(false, 0, true, DownloadStorageBudget.LIMIT);
            boolean allowed = true;
            boolean cancelled = false;
            switch (action) {
                case "START" -> {
                    String state = jdbc.queryForObject("SELECT status FROM download_jobs WHERE id=?", String.class, jobId.toString());
                    allowed = row.phase().equals("RUNNING") && !List.of("READY", "PARTIAL", "MANUAL_ONLY", "FAILED", "CANCELLED", "EXPIRED").contains(state);
                    cancelled = !allowed;
                }
                case "HEARTBEAT" -> { allowed = row.phase().equals("RUNNING"); cancelled = !allowed; }
                case "RESERVE" -> {
                    allowed = row.phase().equals("RUNNING") && bytes <= DownloadStorageBudget.LIMIT
                            && used() - row.reserved() <= DownloadStorageBudget.LIMIT - bytes;
                    if (allowed && bytes > row.reserved()) jdbc.update(
                            "UPDATE download_job_storage SET reserved_bytes=?, required_bytes=? WHERE job_id=?",
                            bytes, bytes, jobId.toString());
                }
                case "READY" -> {
                    allowed = (row.phase().equals("RUNNING") || row.phase().equals("READY")) && bytes <= row.reserved();
                    if (allowed) jdbc.update("UPDATE download_job_storage SET phase='READY', reserved_bytes=?, last_activity_at=?, last_progress_at=? WHERE job_id=?",
                            bytes, now(), now(), jobId.toString());
                }
                case "REQUEUE" -> {
                    if (!row.phase().equals("RUNNING")) return new StorageReply(false, row.reserved(), true, DownloadStorageBudget.LIMIT);
                    if (bytes > DownloadStorageBudget.LIMIT) {
                        fail(jobId, "download_budget_exceeded");
                        cancelled = true;
                    } else {
                        jdbc.update("UPDATE download_job_storage SET phase='QUEUED', reserved_bytes=0, required_bytes=?, attempt_id=NULL, retry_at=? WHERE job_id=?",
                                bytes, Timestamp.from(clock.instant().plusSeconds(2)), jobId.toString());
                        jdbc.update("UPDATE download_jobs SET status='QUEUED', progress=0, wait_reason='storage_capacity', retry_at=NULL, updated_at=?, version=version+1 WHERE id=?",
                                now(), jobId.toString());
                        jdbc.update("UPDATE download_job_items SET status='QUEUED', bytes_downloaded=0,sha256=NULL,error_code=NULL,version=version+1 WHERE job_id=?", jobId.toString());
                    }
                }
                case "CLEANED" -> jdbc.update("UPDATE download_job_storage SET phase='CLEANED', reserved_bytes=0, last_activity_at=? WHERE job_id=?", now(), jobId.toString());
                default -> throw new BadRequestException("invalid_storage_action", "Acción de almacenamiento no válida.");
            }
            jdbc.update("UPDATE download_job_storage SET last_worker_at=? WHERE job_id=?", now(), jobId.toString());
            Entry updated = entry(jobId);
            return new StorageReply(allowed, updated.reserved(), cancelled, DownloadStorageBudget.LIMIT);
        });
    }

    public void touch(UUID jobId, String phase, long bytes) {
        if (bytes < 0 || !List.of("waiting", "saving").contains(phase))
            throw new BadRequestException("invalid_download_activity", "Actividad de descarga no válida.");
        locked(() -> {
            Entry row = entry(jobId);
            if (row == null) throw missing();
            if (row.phase().equals("CLEANING") || row.phase().equals("CLEANED")) return null;
            if (phase.equals("waiting")) {
                if (!row.phase().equals("READY")) jdbc.update("UPDATE download_job_storage SET last_activity_at=? WHERE job_id=?", now(), jobId.toString());
            } else {
                Long size = jdbc.queryForObject("SELECT artifact_size_bytes FROM download_jobs WHERE id=?", Long.class, jobId.toString());
                if (!row.phase().equals("READY") || size == null || bytes > size)
                    throw new ConflictException("download_not_ready", "El ZIP no está disponible.");
                jdbc.update("UPDATE download_job_storage SET last_activity_at=?,last_progress_at=CASE WHEN delivery_bytes < ? THEN ? ELSE last_progress_at END,delivery_bytes=GREATEST(delivery_bytes,?),delivery_status='TRANSFERRING' WHERE job_id=?",
                        now(), bytes, now(), bytes, jobId.toString());
            }
            return null;
        });
    }

    public void complete(UUID jobId, long bytes) {
        locked(() -> {
            Entry row = entry(jobId);
            if (row == null) throw missing();
            Long size = jdbc.queryForObject("SELECT artifact_size_bytes FROM download_jobs WHERE id=?", Long.class, jobId.toString());
            if (size == null || bytes != size || bytes <= 0 || row.delivered() < size)
                throw new ConflictException("download_incomplete", "El ZIP no se ha recibido por completo.");
            jdbc.update("UPDATE download_job_storage SET phase='CLEANING', delivery_status='SAVED',last_activity_at=? WHERE job_id=?", now(), jobId.toString());
            jdbc.update("UPDATE download_job_receipts SET saved_bytes=? WHERE job_id=?", bytes, jobId.toString());
            return null;
        });
    }

    /** El evento durable solo se publica tras cerrar el ZIP y confirmar el borrado de temporales. */
    public void prepared(UUID jobId, Long storedBytes) {
        locked(() -> {
            Entry row = entry(jobId);
            if (row == null || !List.of("RUNNING", "READY").contains(row.phase())) throw missing();
            long bytes = storedBytes == null ? row.reserved() : storedBytes;
            if (bytes <= 0 || bytes > row.reserved())
                throw new ConflictException("invalid_storage_receipt", "El almacenamiento publicado no coincide con su reserva.");
            jdbc.update("UPDATE download_job_storage SET phase='READY',reserved_bytes=?,last_activity_at=?,last_progress_at=? WHERE job_id=?",
                    bytes, now(), now(), jobId.toString());
            return null;
        });
    }

    /** Un recibo mínimo permite reintentar el acuse tras la purga, sin revelar otros propietarios. */
    public boolean confirmed(RequestOwner owner, UUID jobId, long bytes) {
        var receipts = jdbc.query("SELECT owner_id,anonymous_owner_hash,saved_bytes FROM download_job_receipts WHERE job_id=? AND saved_bytes IS NOT NULL",
                (rs, n) -> owner.canAccess(rs.getString(1) == null ? null : UUID.fromString(rs.getString(1)), rs.getString(2))
                        && bytes == rs.getLong(3), jobId.toString());
        return !receipts.isEmpty() && receipts.getFirst();
    }

    public void transferStarted(UUID jobId, UUID transferId) {
        locked(() -> {
            if (!reconciled) throw new ServiceUnavailableException("storage_reconciling", "Se está conciliando el almacenamiento. Reintenta en unos segundos.", 2);
            Entry row = entry(jobId);
            if (row == null || !row.phase().equals("READY") || row.delivery().equals("SAVED"))
                throw new ConflictException("download_not_ready", "El ZIP no está disponible.");
            if (jdbc.update("INSERT IGNORE INTO download_job_transfers(transfer_id,job_id) VALUES (?,?)", transferId.toString(), jobId.toString()) > 0)
                jdbc.update("UPDATE download_job_storage SET active_transfers=active_transfers+1,delivery_status='TRANSFERRING',last_activity_at=?,last_progress_at=? WHERE job_id=?", now(), now(), jobId.toString());
            return null;
        });
    }

    /** El adaptador agrupa estas actualizaciones una vez por segundo, no una vez por bloque. */
    public void transferProgress(UUID jobId, long absoluteBytes) {
        jdbc.update("UPDATE download_job_storage SET delivery_bytes=GREATEST(delivery_bytes,?),last_progress_at=?,last_activity_at=? WHERE job_id=? AND phase='READY'",
                absoluteBytes, now(), now(), jobId.toString());
    }

    public void transferFinished(UUID jobId, UUID transferId) {
        locked(() -> {
            if (jdbc.update("DELETE FROM download_job_transfers WHERE transfer_id=? AND job_id=?", transferId.toString(), jobId.toString()) > 0)
                jdbc.update("UPDATE download_job_storage SET active_transfers=GREATEST(0,active_transfers-1),last_activity_at=? WHERE job_id=?", now(), jobId.toString());
            return null;
        });
    }

    public boolean transferAllowed(UUID jobId) {
        if (!reconciled) throw new ServiceUnavailableException("storage_reconciling", "Se está conciliando el almacenamiento. Reintenta en unos segundos.", 2);
        Entry row = entry(jobId);
        return row != null && row.phase().equals("READY") && !row.delivery().equals("SAVED");
    }

    public void processingProgress(UUID jobId) {
        jdbc.update("UPDATE download_job_storage SET last_progress_at=? WHERE job_id=? AND phase='RUNNING'", now(), jobId.toString());
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (!ticking.compareAndSet(false, true)) return;
        executor.submit(() -> {
            try {
                if (!reconciled) reconcile();
                expireInactive();
                jdbc.update("DELETE FROM download_job_receipts WHERE cleaned_at<?", Timestamp.from(clock.instant().minusSeconds(3600)));
                for (UUID id : ids("SELECT job_id FROM download_job_storage WHERE phase IN ('CLEANING','CLEANED') AND active_transfers=0")) {
                    run(id, () -> cleanup(id));
                }
                var heads = ids("SELECT job_id FROM download_job_storage WHERE phase IN ('ESTIMATING','QUEUED') ORDER BY queue_sequence LIMIT 1");
                if (!heads.isEmpty()) {
                    Entry head = entry(heads.getFirst());
                    if (head != null && head.phase().equals("ESTIMATING")) run(head.id(), () -> estimate(head.id()));
                }
                dispatch();
            } catch (RuntimeException exception) {
                LOG.warn("Download storage maintenance deferred: {}", exception.getMessage());
            } finally { ticking.set(false); }
        });
    }

    private void run(UUID id, Runnable action) {
        if (!operations.add(id)) return;
        executor.submit(() -> {
            try { action.run(); }
            catch (RuntimeException exception) { LOG.warn("Download storage operation pending job={}: {}", id, exception.getMessage()); }
            finally { operations.remove(id); }
        });
    }

    private List<UUID> ids(String sql) {
        return jdbc.query(sql, (rs, n) -> UUID.fromString(rs.getString(1)));
    }

    private void estimate(UUID id) {
        var sources = jdbc.query("SELECT i.source_ref, r.size_bytes FROM download_job_items i LEFT JOIN resolved_sources r ON r.id=UUID_TO_BIN(i.source_ref) WHERE i.job_id=? AND i.source_ref IS NOT NULL",
                (rs, n) -> new Source(UUID.fromString(rs.getString(1)), (Long) rs.getObject(2)), id.toString());
        List<Long> sizes = new ArrayList<>();
        for (Source source : sources) sizes.add(source.bytes() != null && source.bytes() > 0 ? source.bytes() : storage.revalidateSize(source.id()));
        long estimated;
        long required;
        try {
            long median = DownloadStorageBudget.median(sizes);
            if (!sizes.isEmpty() && median == 0) median = catalogMedian();
            estimated = DownloadStorageBudget.estimate(sizes, median);
            required = DownloadStorageBudget.peak(estimated);
        } catch (ArithmeticException exception) {
            locked(() -> { fail(id, "download_budget_exceeded"); return null; });
            return;
        } catch (IllegalArgumentException exception) {
            locked(() -> { fail(id, "download_size_unavailable"); return null; });
            return;
        }
        final long size = estimated;
        final long peak = required;
        locked(() -> {
            Entry row = entry(id);
            if (row == null || !row.phase().equals("ESTIMATING")) return null;
            if (peak > DownloadStorageBudget.LIMIT) { fail(id, "download_budget_exceeded"); return null; }
            jdbc.update("UPDATE download_job_storage SET estimated_bytes=?,required_bytes=?,phase='QUEUED' WHERE job_id=?", size, peak, id.toString());
            return null;
        });
    }

    private long catalogMedian() {
        Double value = jdbc.queryForObject("""
                SELECT AVG(size_bytes) FROM (
                  SELECT r.size_bytes, ROW_NUMBER() OVER (ORDER BY r.size_bytes) rn, COUNT(*) OVER () cnt
                  FROM resolved_sources r JOIN download_sources d ON d.id=r.download_source_id
                  JOIN software_apps a ON a.id=d.software_app_id
                  WHERE r.catalog_downloadable=1 AND d.catalog_available=1 AND a.app_status='active' AND r.size_bytes>0
                ) sizes WHERE rn IN (FLOOR((cnt+1)/2),FLOOR((cnt+2)/2))
                """, Double.class);
        return value == null ? 0 : (long) Math.ceil(value);
    }

    private void dispatch() {
        if (!reconciled) return;
        locked(() -> {
            long allocated = used();
            // Procesar lotes no limita los trabajos activos: la siguiente pasada continúa la misma FIFO.
            for (UUID id : ids("SELECT job_id FROM download_job_storage WHERE phase IN ('ESTIMATING','QUEUED') ORDER BY queue_sequence LIMIT 100")) {
                Entry row = entry(id);
                if (!row.phase().equals("QUEUED") || row.required() > DownloadStorageBudget.LIMIT - allocated) break;
                Timestamp retry = jdbc.queryForObject("SELECT retry_at FROM download_job_storage WHERE job_id=?", Timestamp.class, id.toString());
                if (retry.toInstant().isAfter(clock.instant())) break;
                DownloadJob job = jobs.findById(id).orElseThrow(DownloadStorageCoordinator::missing);
                if (job.cancellationRequested() || job.status().terminal()) {
                    jdbc.update("UPDATE download_job_storage SET phase='CLEANING',delivery_status='CLEANING' WHERE job_id=?", id.toString());
                    continue;
                }
                UUID attempt = events.jobRequested(job);
                jdbc.update("UPDATE download_job_storage SET phase='RUNNING',attempt_id=?,reserved_bytes=required_bytes,last_worker_at=?,last_progress_at=? WHERE job_id=?", attempt.toString(), now(), now(), id.toString());
                allocated += row.required();
            }
            return null;
        });
    }

    private void expireInactive() {
        locked(() -> {
            jdbc.update("""
                    UPDATE download_job_storage s JOIN download_jobs j ON j.id=s.job_id
                    SET s.phase='CLEANING',s.delivery_status=CASE WHEN s.delivery_status='SAVED' THEN 'SAVED' ELSE 'CLEANING' END
                    WHERE s.phase NOT IN ('CLEANING','CLEANED') AND
                      (j.cancellation_requested=1 OR j.status IN ('FAILED','CANCELLED','EXPIRED')
                       OR (s.active_transfers=0 AND s.last_activity_at < ?)
                       OR (s.phase='READY' AND s.last_progress_at < ?))
                    """, Timestamp.from(clock.instant().minusSeconds(60)), Timestamp.from(clock.instant().minusSeconds(300)));
            return null;
        });
    }

    private void cleanup(UUID id) {
        Entry row = entry(id);
        if (row == null || row.transfers() != 0) return;
        if (row.phase().equals("CLEANED")) {
            Timestamp changed = jdbc.queryForObject("SELECT last_activity_at FROM download_job_storage WHERE job_id=?", Timestamp.class, id.toString());
            if (changed.toInstant().plusSeconds(60).isAfter(clock.instant())) return;
        } else storage.delete(id);
        boolean removed = locked(() -> {
            Entry current = entry(id);
            if (current == null || current.transfers() != 0 || !List.of("CLEANING", "CLEANED").contains(current.phase())) return false;
            jdbc.update("DELETE FROM core_outbox_events WHERE aggregate_type='download-job' AND aggregate_id=?", id.toString());
            jdbc.update("UPDATE download_job_receipts SET cleaned_at=? WHERE job_id=?", now(), id.toString());
            jdbc.update("DELETE FROM download_jobs WHERE id=?", id.toString());
            return true;
        });
        if (removed) notifier.removed(id);
    }

    /** Arranque cerrado hasta contabilizar también restos de intentos y versiones anteriores. */
    private void reconcile() {
        var inventory = storage.inventory();
        Map<UUID, DownloadStorage.StoredJob> files = inventory.jobs().stream()
                .collect(java.util.stream.Collectors.toMap(DownloadStorage.StoredJob::jobId, j -> j));
        for (var stored : inventory.jobs()) if (entry(stored.jobId()) == null) storage.delete(stored.jobId());
        locked(() -> {
            jdbc.update("DELETE FROM download_job_transfers");
            jdbc.update("UPDATE download_job_storage SET active_transfers=0");
            for (UUID id : ids("SELECT job_id FROM download_job_storage")) {
                Entry row = entry(id);
                var disk = files.get(id);
                long bytes = disk == null ? 0 : Math.max(0, disk.bytes());
                String state = jdbc.queryForObject("SELECT status FROM download_jobs WHERE id=?", String.class, id.toString());
                if (!List.of("CLEANING", "CLEANED").contains(row.phase()) && List.of("READY", "PARTIAL", "MANUAL_ONLY").contains(state) && disk != null && !disk.active()) {
                    jdbc.update("UPDATE download_job_storage SET phase='READY',reserved_bytes=?,last_activity_at=?,last_progress_at=? WHERE job_id=?", bytes, now(), now(), id.toString());
                } else if (row.phase().equals("RECONCILING") || row.phase().equals("READY") && disk == null
                        || row.phase().equals("CLEANED") && bytes > 0) {
                    jdbc.update("UPDATE download_job_storage SET phase='CLEANING',delivery_status='CLEANING',reserved_bytes=? WHERE job_id=?",
                            Math.max(bytes, row.reserved()), id.toString());
                } else if (bytes > row.reserved()) {
                    jdbc.update("UPDATE download_job_storage SET reserved_bytes=? WHERE job_id=?", bytes, id.toString());
                }
            }
            return null;
        });
        reconciled = true;
    }

    private void fail(UUID id, String code) {
        jdbc.update("UPDATE download_jobs SET status='FAILED',failure_code=?,updated_at=?,version=version+1 WHERE id=?", code, now(), id.toString());
        jdbc.update("UPDATE download_job_storage SET phase='CLEANED',reserved_bytes=0,last_activity_at=? WHERE job_id=?", now(), id.toString());
    }

    private static NotFoundException missing() { return new NotFoundException("download_job_not_found", "No existe el trabajo."); }

    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
