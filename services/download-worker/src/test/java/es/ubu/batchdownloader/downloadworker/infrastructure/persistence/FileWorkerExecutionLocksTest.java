package es.ubu.batchdownloader.downloadworker.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Usa bloqueos reales, incluida una JVM independiente terminada abruptamente. */
@Timeout(30)
class FileWorkerExecutionLocksTest {
    @TempDir Path directory;

    @Test void jobExclusionAndPackagingSlotsAreShared() throws Exception {
        try (var first = new FileWorkerExecutionLocks(directory, 2);
             var second = new FileWorkerExecutionLocks(directory, 2)) {
            UUID job = UUID.randomUUID();
            try (var execution = first.tryJob(job);
                 var otherJob = second.tryJob(UUID.randomUUID());
                 var zip1 = first.tryPackaging(); var zip2 = second.tryPackaging()) {
                assertThat(execution).isNotNull(); assertThat(otherJob).isNotNull();
                assertThat(second.active(job)).isTrue();
                assertThat(second.tryJob(job)).isNull();
                assertThat(zip1).isNotNull(); assertThat(zip2).isNotNull();
                assertThat(first.tryPackaging()).isNull();
            }
            assertThat(second.active(job)).isFalse();
            try (var recovered = second.tryJob(job); var packaging = second.tryPackaging()) {
                assertThat(recovered).isNotNull(); assertThat(packaging).isNotNull();
            }
        }
        assertThat(Files.size(directory.resolve(".worker-execution.lock"))).isZero();
    }

    @Test void killedJvmReleasesJobAndGlobalPackagingSlots() throws Exception {
        UUID job = UUID.randomUUID();
        Path signal = directory.resolve("locked");
        Process owner = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                LockOwner.class.getName(), directory.toString(), job.toString(), signal.toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("owner.log").toFile()).start();
        try (var survivor = new FileWorkerExecutionLocks(directory, 2)) {
            await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(signal));
            assertThat(survivor.tryJob(job)).isNull();
            assertThat(survivor.tryPackaging()).isNull();
            owner.destroyForcibly();
            assertThat(owner.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try (var recovered = survivor.tryJob(job);
                 var zip1 = survivor.tryPackaging(); var zip2 = survivor.tryPackaging()) {
                assertThat(recovered).isNotNull();
                assertThat(zip1).isNotNull(); assertThat(zip2).isNotNull();
            }
        } finally { owner.destroyForcibly(); }
    }

    public static final class LockOwner {
        public static void main(String[] args) throws Exception {
            try (var locks = new FileWorkerExecutionLocks(Path.of(args[0]), 2);
                 var job = locks.tryJob(UUID.fromString(args[1]));
                 var zip1 = locks.tryPackaging(); var zip2 = locks.tryPackaging()) {
                if (job == null || zip1 == null || zip2 == null) throw new IllegalStateException("lock_unavailable");
                Files.writeString(Path.of(args[2]), "locked");
                new java.util.concurrent.CountDownLatch(1).await();
            }
        }
    }
}
