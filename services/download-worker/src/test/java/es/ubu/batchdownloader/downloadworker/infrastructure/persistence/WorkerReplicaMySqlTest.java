package es.ubu.batchdownloader.downloadworker.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ubu.batchdownloader.downloadworker.application.CapacityDeferredException;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobPayload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.messaging.InboxDownloadJobHandler;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Dos consumidores independientes comparten SQL y volumen; el ocupado se aplaza, no se confirma. */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class WorkerReplicaMySqlTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.13");
    @TempDir Path directory;
    private JdbcTemplate jdbc;

    @BeforeEach void schema() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/worker-migration")
                .table("download_worker_schema_history").baselineOnMigrate(true).baselineVersion("0").load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("DELETE FROM download_inbox");
    }

    @Test void replicaStartupPreservesLiveClaimsAndSealedResults() {
        var first = new JdbcInboxRepository(jdbc, Clock.systemUTC());
        UUID event = UUID.randomUUID(), job = UUID.randomUUID();
        assertThat(first.tryStart(event, Duration.ofMinutes(30))).isTrue();
        first.saveReady(event, job, "{\"status\":\"READY\"}");
        var restarted = new JdbcInboxRepository(jdbc, Clock.systemUTC());
        assertThat(restarted.tryStart(event, Duration.ofMinutes(30))).isFalse();
        assertThat(restarted.pendingReady(event)).isEqualTo("{\"status\":\"READY\"}");
        // Cero solo se utiliza después de obtener la exclusión del trabajo.
        try (var locks = new FileWorkerExecutionLocks(directory, 2); var lease = locks.tryJob(job)) {
            assertThat(lease).isNotNull();
            assertThat(restarted.tryStart(event, Duration.ZERO)).isTrue();
            restarted.complete(event);
        } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
        assertThat(first.tryStart(event, Duration.ZERO)).isFalse();
        assertThat(first.trackedJobs()).containsExactly(job);
        restarted.clearReady(job);
        assertThat(first.trackedJobs()).isEmpty();
    }

    @Test void differentJobsRunTogetherButDuplicateDeliveryRemainsRecoverable() throws Exception {
        var first = new JdbcInboxRepository(jdbc, Clock.systemUTC());
        var second = new JdbcInboxRepository(jdbc, Clock.systemUTC());
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var executions = new AtomicInteger();
        var event = event();
        try (var firstLocks = new FileWorkerExecutionLocks(directory, 2);
             var secondLocks = new FileWorkerExecutionLocks(directory, 2);
             var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstHandler = new InboxDownloadJobHandler(first, firstLocks, ignored -> {
                executions.incrementAndGet(); entered.countDown();
                try { if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test_timeout"); }
                catch (InterruptedException failure) { throw new RuntimeException(failure); }
            });
            var secondHandler = new InboxDownloadJobHandler(second, secondLocks, ignored -> executions.incrementAndGet());
            var running = threads.submit(() -> firstHandler.handle(event));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> secondHandler.handle(event)).isInstanceOf(CapacityDeferredException.class);
                secondHandler.handle(event());
                assertThat(executions).hasValue(2);
            } finally { finish.countDown(); }
            running.get(5, TimeUnit.SECONDS);
            secondHandler.handle(event);
            assertThat(executions).hasValue(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM download_inbox WHERE status='COMPLETED'", Integer.class)).isEqualTo(2);
        }
    }

    private static DownloadJobRequestedEvent event() {
        return new DownloadJobRequestedEvent(UUID.randomUUID(), "download.job.requested", 1, Instant.now(),
                UUID.randomUUID().toString(), null, new DownloadJobPayload(UUID.randomUUID(), List.of()));
    }
}
