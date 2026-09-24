package es.ubu.batchdownloader.downloads.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner.RequestOwner;
import es.ubu.batchdownloader.downloads.application.port.*;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobItem;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Prueba reservas y limpieza con bloqueos y transacciones de MySQL, sin procesos de aplicación. */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class DownloadStorageCoordinatorMySqlTest {
    private static final long GIB = 1024L * 1024 * 1024;
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private DownloadJobStore jobs;
    private DownloadEventPublisher events;
    private DownloadStorage disk;
    private DownloadJobNotifier notifier;
    private DownloadStorageCoordinator coordinator;
    private MutableClock clock;
    private final Map<UUID, DownloadJob> domain = new ConcurrentHashMap<>();
    private final List<UUID> dispatched = new CopyOnWriteArrayList<>();
    private final Map<UUID, Deque<UUID>> transfers = new HashMap<>();

    @BeforeAll static void schema() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MYSQL.getJdbcUrl()); config.setUsername(MYSQL.getUsername()); config.setPassword(MYSQL.getPassword());
        config.setMaximumPoolSize(5); config.setConnectionTimeout(2000);
        pool = new HikariDataSource(config); jdbc = new JdbcTemplate(pool);
        jdbc.execute("CREATE TABLE download_jobs (id CHAR(36) PRIMARY KEY, owner_id CHAR(36), anonymous_owner_hash CHAR(64), anonymous_ip_hash CHAR(64), status VARCHAR(24), progress INT DEFAULT 0, cancellation_requested BOOLEAN DEFAULT 0, artifact_size_bytes BIGINT, wait_reason VARCHAR(80), retry_at DATETIME(6), failure_code VARCHAR(80), created_at DATETIME(6), updated_at DATETIME(6), version BIGINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE download_job_items (id CHAR(36) PRIMARY KEY,job_id CHAR(36),source_ref CHAR(36), status VARCHAR(24),bytes_downloaded BIGINT DEFAULT 0,sha256 VARCHAR(64),error_code VARCHAR(80),version BIGINT DEFAULT 0,FOREIGN KEY(job_id) REFERENCES download_jobs(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE core_outbox_events (aggregate_type VARCHAR(80),aggregate_id CHAR(36))");
        jdbc.execute("CREATE TABLE download_job_capacity_guard (id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO download_job_capacity_guard VALUES (1)");
        jdbc.execute("CREATE TABLE resolved_sources (id BINARY(16) PRIMARY KEY, size_bytes BIGINT,download_source_id BINARY(16),catalog_downloadable BOOLEAN)");
        jdbc.execute("CREATE TABLE download_sources (id BINARY(16) PRIMARY KEY,software_app_id BINARY(16),catalog_available BOOLEAN)");
        jdbc.execute("CREATE TABLE software_apps (id BINARY(16) PRIMARY KEY,app_status VARCHAR(24))");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20__download_storage_fifo.sql")).execute(pool);
    }

    @AfterAll static void closePool() { pool.close(); }
    @BeforeEach void setUp() {
        jdbc.update("DELETE FROM download_jobs"); jdbc.update("DELETE FROM download_job_receipts");
        jdbc.update("DELETE FROM core_outbox_events"); jdbc.update("DELETE FROM resolved_sources");
        jdbc.update("DELETE FROM download_sources"); jdbc.update("DELETE FROM software_apps");
        jobs = mock(DownloadJobStore.class); events = mock(DownloadEventPublisher.class);
        disk = mock(DownloadStorage.class); notifier = mock(DownloadJobNotifier.class);
        clock = new MutableClock();
        doAnswer(call -> jdbc.queryForObject("SELECT id FROM download_job_capacity_guard WHERE id=1 FOR UPDATE", Integer.class)).when(jobs).lockAdmission();
        when(jobs.findById(any())).thenAnswer(call -> Optional.ofNullable(domain.get(call.getArgument(0))));
        when(events.jobRequested(any())).thenAnswer(call -> { DownloadJob job = call.getArgument(0); dispatched.add(job.id()); return UUID.randomUUID(); });
        when(disk.inventory()).thenReturn(new DownloadStorage.Inventory(List.of(), 30 * GIB));
        coordinator = new DownloadStorageCoordinator(jdbc, new TransactionTemplate(new DataSourceTransactionManager(pool)), jobs, events, disk, clock, notifier);
        call("reconcile");
    }
    @AfterEach void closeCoordinator() { coordinator.close(); }

    @Test void strictFifoBlocksSmallThirdThenAdmitsSecondAndThirdTogether() {
        UUID first = queued(6 * GIB), second = queued(5 * GIB), third = queued(GIB);
        call("dispatch");
        assertThat(dispatched).containsExactly(first);
        assertThat(coordinator.decorate(DownloadJobView.from(domain.get(third))).queuePosition()).isEqualTo(2);
        coordinator.worker(first, attempt(first), "READY", 1024);
        call("dispatch");
        assertThat(dispatched).containsExactly(first, second, third);
        assertThat(reserved()).isEqualTo(6 * GIB + 1024);
    }

    @Test void concurrentGrowthCannotOversubscribeGlobalBudget() throws Exception {
        UUID first = queued(4 * GIB), second = queued(4 * GIB); call("dispatch");
        var latch = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = threads.submit(() -> { latch.await(); return coordinator.worker(first, attempt(first), "RESERVE", 6 * GIB); });
            var b = threads.submit(() -> { latch.await(); return coordinator.worker(second, attempt(second), "RESERVE", 6 * GIB); });
            latch.countDown();
            assertThat(List.of(a.get().allowed(), b.get().allowed())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(reserved()).isEqualTo(DownloadStorageBudget.LIMIT);
    }

    @Test void retryKeepsSequenceAndFencesOldMessages() {
        UUID first = queued(4 * GIB); call("dispatch"); UUID old = attempt(first);
        long sequence = number(first, "queue_sequence");
        coordinator.worker(first, old, "REQUEUE", 7 * GIB);
        UUID second = queued(4 * GIB);
        clock.advance(3); call("dispatch");
        assertThat(dispatched).containsExactly(first, first);
        assertThat(number(first, "queue_sequence")).isEqualTo(sequence);
        assertThat(phase(second)).isEqualTo("QUEUED");
        assertThat(coordinator.acceptsEvent(first, old.toString(), "download.job.ready")).isFalse();
        assertThat(coordinator.worker(first, old, "CLEANED", 0).cancelled()).isTrue();
        assertThat(reserved()).isEqualTo(7 * GIB);
    }

    @Test void durableReadyEventMakesZipDeliverableWithoutDependingOnHttpCallback() {
        UUID id = queued(3 * GIB); call("dispatch");
        coordinator.prepared(id,1024L);
        assertThat(phase(id)).isEqualTo("READY"); assertThat(reserved()).isEqualTo(1024);
        start(id);
        assertThat(coordinator.transferAllowed(id)).isTrue();
        assertThat(coordinator.worker(id,attempt(id),"START",0).allowed()).isFalse();
        assertThat(coordinator.worker(id,attempt(id),"READY",1024).allowed()).isTrue();
    }

    @Test void restartRefusesTransfersUntilInventoryCompletes() {
        UUID id = ready(1024);
        ReflectionTestUtils.setField(coordinator,"reconciled",false);
        assertThatThrownBy(() -> start(id)).hasMessageContaining("conciliando");
        assertThatThrownBy(() -> coordinator.transferAllowed(id)).hasMessageContaining("conciliando");
        assertThat(number(id,"active_transfers")).isZero();
    }

    @Test void metadataRevalidationDoesNotHoldConnectionAndUsesOwnMedian() {
        UUID id = estimating(Arrays.asList(100L, null, 300L));
        when(disk.revalidateSize(any())).thenAnswer(call -> { assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero(); return null; });
        call("estimate", id);
        assertThat(number(id, "estimated_bytes")).isEqualTo(600);
        assertThat(number(id, "required_bytes")).isEqualTo(DownloadStorageBudget.peak(600));
    }

    @Test void unknownJobUsesDownloadableCatalogMedianAndOversizeFails() {
        UUID app = UUID.randomUUID(), source = UUID.randomUUID();
        jdbc.update("INSERT INTO software_apps VALUES(UUID_TO_BIN(?),'active')", app.toString());
        jdbc.update("INSERT INTO download_sources VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),1)", source.toString(), app.toString());
        for (long size : List.of(100L,300L)) jdbc.update("INSERT INTO resolved_sources VALUES(UUID_TO_BIN(?),?,UUID_TO_BIN(?),1)", UUID.randomUUID().toString(), size, source.toString());
        UUID unknown = estimating(Arrays.asList(null,null)); call("estimate", unknown);
        assertThat(number(unknown, "estimated_bytes")).isEqualTo(400);
        UUID oversized = estimating(List.of(6 * GIB)); call("estimate", oversized);
        assertThat(jdbc.queryForObject("SELECT failure_code FROM download_jobs WHERE id=?", String.class, oversized.toString())).isEqualTo("download_budget_exceeded");
        assertThat(number(oversized,"reserved_bytes")).isZero();
    }

    @Test void failedDeletionKeepsReservationAndReceiptPreservesHourlyCount() {
        UUID id = ready(3 * GIB);
        clock.advance(61); call("expireInactive");
        doThrow(new IllegalStateException("disk offline")).when(disk).delete(id);
        assertThatThrownBy(() -> call("cleanup", id)).hasMessageContaining("disk offline");
        assertThat(reserved()).isEqualTo(3 * GIB);
        doAnswer(call -> { assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero(); return null; }).when(disk).delete(id);
        call("cleanup", id);
        assertThat(reserved()).isZero(); assertThat(countJobs()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM download_job_receipts WHERE anonymous_owner_hash='browser'", Long.class)).isEqualTo(1);
        verify(notifier).removed(id);
        assertThat(coordinator.acceptsEvent(id, UUID.randomUUID().toString(), "download.job.ready")).isFalse();
    }

    @Test void waitingClientKeepsQueueButObservationDoesNotKeepReadyZip() {
        UUID waiting = queued(GIB), ready = ready(GIB);
        clock.advance(50); coordinator.touch(waiting, "waiting", 0); coordinator.touch(ready,"waiting",0);
        clock.advance(12); call("expireInactive");
        assertThat(phase(waiting)).isEqualTo("QUEUED"); assertThat(phase(ready)).isEqualTo("CLEANING");
        clock.advance(1000); coordinator.touch(waiting,"waiting",0); call("expireInactive");
        assertThat(phase(waiting)).isEqualTo("QUEUED");
    }

    @Test void stalledTransferStopsAfterFiveMinutesButReservationWaitsForEverySocket() {
        UUID id = ready(GIB); start(id); start(id);
        clock.advance(61); call("expireInactive"); assertThat(coordinator.transferAllowed(id)).isTrue();
        clock.advance(240); call("expireInactive"); assertThat(coordinator.transferAllowed(id)).isFalse();
        call("cleanup", id); verify(disk, never()).delete(id);
        finish(id); call("cleanup", id); verify(disk, never()).delete(id);
        finish(id); call("cleanup", id); verify(disk).delete(id); assertThat(reserved()).isZero();
    }

    @Test void completeIsOwnedIdempotentAndRequiresExactSavedBytes() {
        UUID id = ready(1024); start(id); coordinator.transferProgress(id,1024); finish(id);
        assertThatThrownBy(() -> coordinator.complete(id,1023)).hasMessageContaining("completo");
        coordinator.complete(id,1024); coordinator.complete(id,1024); call("cleanup",id);
        assertThat(coordinator.confirmed(new RequestOwner(null,"browser",null),id,1024)).isTrue();
        assertThat(coordinator.confirmed(new RequestOwner(null,"other",null),id,1024)).isFalse();
        assertThat(coordinator.confirmed(new RequestOwner(null,"browser",null),id,1023)).isFalse();
        assertThat(countJobs()).isZero();
    }

    @Test void repeatedSocketFinishCannotDiscountAnotherLiveConnection() {
        UUID id = ready(1024), first = UUID.randomUUID(), second = UUID.randomUUID();
        coordinator.transferStarted(id,first); coordinator.transferStarted(id,first); coordinator.transferStarted(id,second);
        assertThat(number(id,"active_transfers")).isEqualTo(2);
        coordinator.transferFinished(id,first); coordinator.transferFinished(id,first);
        assertThat(number(id,"active_transfers")).isEqualTo(1);
        clock.advance(301); call("expireInactive"); call("cleanup",id); verify(disk,never()).delete(id);
        coordinator.transferFinished(id,second); call("cleanup",id); verify(disk).delete(id);
    }

    @Test void restartReconcilesOrphansAndNeverRevivesSavedCleanup() {
        UUID ready = ready(1024); start(ready); coordinator.transferProgress(ready,1024); coordinator.complete(ready,1024);
        UUID orphan = UUID.randomUUID();
        when(disk.inventory()).thenReturn(new DownloadStorage.Inventory(List.of(new DownloadStorage.StoredJob(ready,1024,false),new DownloadStorage.StoredJob(orphan,2048,false)),20 * GIB));
        call("reconcile"); verify(disk).delete(orphan);
        assertThat(phase(ready)).isEqualTo("CLEANING"); assertThat(number(ready,"active_transfers")).isZero();
        call("cleanup",ready); assertThat(reserved()).isZero();
    }

    @Test void reconciliationNeverFreesMissingZipOrUnexpectedCleanedFilesBeforeConfirmedDelete() {
        UUID missing = ready(GIB), dirty = queued(GIB);
        jdbc.update("UPDATE download_job_storage SET phase='CLEANED',reserved_bytes=0 WHERE job_id=?",dirty.toString());
        when(disk.inventory()).thenReturn(new DownloadStorage.Inventory(List.of(new DownloadStorage.StoredJob(dirty,4096,false)),20 * GIB));
        call("reconcile");
        assertThat(reserved()).isEqualTo(GIB + 4096);
        assertThat(phase(missing)).isEqualTo("CLEANING"); assertThat(phase(dirty)).isEqualTo("CLEANING");
        call("cleanup",missing); call("cleanup",dirty);
        verify(disk).delete(missing); verify(disk).delete(dirty); assertThat(reserved()).isZero();
    }

    @Test void schedulerRecoversInventoryAndMetadataFailuresWithoutBypassingHead() throws Exception {
        UUID head = estimating(Collections.singletonList(null)), next = queued(1024);
        ReflectionTestUtils.setField(coordinator, "reconciled", false);
        when(disk.inventory()).thenThrow(new IllegalStateException("worker unavailable"))
                .thenReturn(new DownloadStorage.Inventory(List.of(), 30 * GIB));
        when(disk.revalidateSize(any())).thenThrow(new IllegalStateException("metadata unavailable")).thenReturn(100L);
        tickAndWait();
        assertThat(dispatched).isEmpty();
        assertThatThrownBy(() -> coordinator.transferAllowed(head)).hasMessageContaining("conciliando");
        tickAndWait();
        assertThat(dispatched).isEmpty();
        assertThat(phase(head)).isEqualTo("ESTIMATING");
        tickAndWait(); tickAndWait();
        assertThat(dispatched).containsExactly(head, next);
        assertThat(reserved()).isEqualTo(DownloadStorageBudget.peak(100) + 1024);
    }

    @Test void schedulerRetriesCleanupAndRetiresOnlyOldReceipts() throws Exception {
        UUID id = ready(1024);
        doThrow(new IllegalStateException("storage busy")).doNothing().when(disk).delete(id);
        clock.advance(61); tickAndWait();
        assertThat(phase(id)).isEqualTo("CLEANING"); assertThat(reserved()).isEqualTo(1024);
        tickAndWait();
        assertThat(countJobs()).isZero(); assertThat(reserved()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM download_job_receipts", Long.class)).isEqualTo(1);
        clock.advance(3601); tickAndWait();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM download_job_receipts", Long.class)).isZero();
        verify(disk, times(2)).delete(id);
    }

    @Test void savingHeartbeatsRequireRealProgressAndCannotKeepStalledFileForever() {
        UUID id = ready(1024); start(id);
        assertThatThrownBy(() -> coordinator.complete(id, 1024)).hasMessageContaining("completo");
        assertThatThrownBy(() -> coordinator.touch(id, "saving", 1025)).hasMessageContaining("disponible");
        clock.advance(240); coordinator.touch(id, "saving", 512);
        clock.advance(240); coordinator.touch(id, "saving", 512); call("expireInactive");
        assertThat(coordinator.transferAllowed(id)).isTrue();
        clock.advance(61); coordinator.touch(id, "saving", 512); call("expireInactive");
        assertThat(coordinator.transferAllowed(id)).isFalse();
        coordinator.touch(id, "saving", 1024);
        assertThat(number(id, "delivery_bytes")).isEqualTo(512);
        assertThatThrownBy(() -> start(id)).hasMessageContaining("disponible");
        assertThat(reserved()).isEqualTo(1024);
    }

    @Test void invalidEstimatesFailVisiblyThenPurgeWithoutASecondDeletion() {
        UUID unknown = estimating(Collections.singletonList(null)); call("estimate", unknown);
        UUID overflow = estimating(List.of(Long.MAX_VALUE)); call("estimate", overflow);
        assertThat(jdbc.queryForObject("SELECT failure_code FROM download_jobs WHERE id=?", String.class, unknown.toString()))
                .isEqualTo("download_size_unavailable");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM download_jobs WHERE id=?", String.class, overflow.toString()))
                .isEqualTo("download_budget_exceeded");
        call("cleanup", unknown); assertThat(countJobs()).isEqualTo(2);
        clock.advance(61); call("cleanup", unknown); call("cleanup", overflow);
        assertThat(countJobs()).isZero(); assertThat(reserved()).isZero();
        verify(disk, never()).delete(any());
        assertThatThrownBy(() -> coordinator.touch(unknown, "waiting", 0)).hasMessageContaining("No existe");
    }

    @Test void workerCannotReopenReadyOrOversizeJobsAndFailedAttemptIsStillObservable() {
        UUID id = queued(1024); call("dispatch"); UUID attempt = attempt(id);
        assertThat(coordinator.worker(id, attempt, "START", 0).allowed()).isTrue();
        assertThat(coordinator.worker(id, attempt, "HEARTBEAT", 0).allowed()).isTrue();
        assertThat(coordinator.worker(id, attempt, "RESERVE", 512).allowed()).isTrue();
        assertThat(reserved()).isEqualTo(1024);
        assertThat(coordinator.worker(id, attempt, "RESERVE", DownloadStorageBudget.LIMIT + 1).allowed()).isFalse();
        assertThat(coordinator.worker(id, attempt, "READY", 2048).allowed()).isFalse();
        assertThatThrownBy(() -> coordinator.prepared(id, 0L)).hasMessageContaining("reserva");
        assertThatThrownBy(() -> coordinator.prepared(id, 2048L)).hasMessageContaining("reserva");
        assertThat(coordinator.worker(id, attempt, "REQUEUE", DownloadStorageBudget.LIMIT + 1).cancelled()).isTrue();
        assertThat(reserved()).isZero();
        assertThat(coordinator.acceptsEvent(id, attempt.toString(), "download.job.failed")).isTrue();
        assertThat(coordinator.acceptsEvent(id, attempt.toString(), "download.job.ready")).isFalse();
        assertThat(coordinator.acceptsEvent(id, null, "download.job.failed")).isFalse();
        UUID prepared = queued(1024); call("dispatch");
        coordinator.prepared(prepared, null);
        assertThat(coordinator.worker(prepared, attempt(prepared), "HEARTBEAT", 0).cancelled()).isTrue();
        assertThat(coordinator.worker(prepared, attempt(prepared), "REQUEUE", 2048).cancelled()).isTrue();
        assertThat(reserved()).isEqualTo(1024);
    }

    @Test void restartAccountsLegacyReadyAndLiveWorkerBytesBeforeAdmission() {
        UUID legacyReady = ready(4096), legacyRunning = queued(4096), running = queued(1024);
        jdbc.update("UPDATE download_job_storage SET phase='RECONCILING',reserved_bytes=0 WHERE job_id IN (?,?)",
                legacyReady.toString(), legacyRunning.toString());
        call("dispatch");
        when(disk.inventory()).thenReturn(new DownloadStorage.Inventory(List.of(
                new DownloadStorage.StoredJob(legacyReady, 2048, false),
                new DownloadStorage.StoredJob(legacyRunning, 4096, true),
                new DownloadStorage.StoredJob(running, 8192, true)), 20 * GIB));
        call("reconcile");
        assertThat(phase(legacyReady)).isEqualTo("READY");
        assertThat(phase(legacyRunning)).isEqualTo("CLEANING");
        assertThat(phase(running)).isEqualTo("RUNNING");
        assertThat(reserved()).isEqualTo(2048 + 4096 + 8192);
        assertThat(coordinator.transferAllowed(legacyReady)).isTrue();
        call("cleanup", legacyRunning);
        assertThat(reserved()).isEqualTo(2048 + 8192);
    }

    private void tickAndWait() throws InterruptedException {
        coordinator.tick();
        var ticking = (java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(coordinator, "ticking");
        var operations = (Set<?>) ReflectionTestUtils.getField(coordinator, "operations");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((ticking.get() || !operations.isEmpty()) && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(ticking.get()).isFalse();
        assertThat(operations).isEmpty();
    }

    private UUID estimating(List<Long> sizes) {
        DownloadJob job = DownloadJob.queue(null,"browser","ip", List.of(DownloadJobItem.manual(UUID.randomUUID(),"App","https://example.test",clock.instant())),1,0,clock.instant(),clock.instant().plusSeconds(3600));
        domain.put(job.id(),job);
        jdbc.update("INSERT INTO download_jobs(id,anonymous_owner_hash,anonymous_ip_hash,status,created_at,updated_at) VALUES(?,'browser','ip','QUEUED',?,?)",job.id().toString(),Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
        coordinator.enqueue(job);
        for (Long size : sizes) {
            UUID ref = UUID.randomUUID();
            jdbc.update("INSERT INTO resolved_sources(id,size_bytes) VALUES(UUID_TO_BIN(?),?)",ref.toString(),size);
            jdbc.update("INSERT INTO download_job_items(id,job_id,source_ref,status) VALUES(?,?,?,'QUEUED')",UUID.randomUUID().toString(),job.id().toString(),ref.toString());
        }
        return job.id();
    }
    private UUID queued(long bytes) {
        UUID id = estimating(List.of()); jdbc.update("UPDATE download_job_storage SET phase='QUEUED',required_bytes=? WHERE job_id=?",bytes,id.toString()); return id;
    }
    private UUID ready(long bytes) {
        UUID id = queued(bytes); jdbc.update("UPDATE download_job_storage SET phase='READY',reserved_bytes=?,attempt_id=? WHERE job_id=?",bytes,UUID.randomUUID().toString(),id.toString());
        jdbc.update("UPDATE download_jobs SET status='READY',artifact_size_bytes=? WHERE id=?",bytes,id.toString()); return id;
    }
    private UUID attempt(UUID id) { return UUID.fromString(jdbc.queryForObject("SELECT attempt_id FROM download_job_storage WHERE job_id=?",String.class,id.toString())); }
    private void start(UUID id) {
        UUID transfer = UUID.randomUUID(); coordinator.transferStarted(id,transfer);
        transfers.computeIfAbsent(id, ignored -> new ArrayDeque<>()).add(transfer);
    }
    private void finish(UUID id) { coordinator.transferFinished(id,transfers.get(id).removeFirst()); }
    private String phase(UUID id) { return jdbc.queryForObject("SELECT phase FROM download_job_storage WHERE job_id=?",String.class,id.toString()); }
    private long number(UUID id,String column) { return jdbc.queryForObject("SELECT " + column + " FROM download_job_storage WHERE job_id=?",Long.class,id.toString()); }
    private long reserved() { return jdbc.queryForObject("SELECT COALESCE(SUM(reserved_bytes),0) FROM download_job_storage",Long.class); }
    private long countJobs() { return jdbc.queryForObject("SELECT COUNT(*) FROM download_jobs",Long.class); }
    private void call(String method,Object... args) { ReflectionTestUtils.invokeMethod(coordinator,method,args); }
    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-09-24T12:00:00Z");
        void advance(long seconds) { instant = instant.plusSeconds(seconds); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return instant; }
    }
}
