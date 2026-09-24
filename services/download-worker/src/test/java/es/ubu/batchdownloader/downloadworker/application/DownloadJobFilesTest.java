package es.ubu.batchdownloader.downloadworker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class DownloadJobFilesTest {
    @TempDir Path directory;
    private final ArtifactStore artifacts = mock(ArtifactStore.class);
    private final DownloadWorkerMetrics metrics = mock(DownloadWorkerMetrics.class);

    @Test
    void inventoryCombinesAllAttemptsAndRemoteObjectsWithoutCountingUnrelatedDirectories() throws Exception {
        var files = files(directory);
        UUID job = UUID.randomUUID();
        UUID remoteOnly = UUID.randomUUID();
        Path attempt = files.createDirectory(job);
        Files.write(attempt.resolve("installer.exe"), new byte[100]);
        Files.write(Files.createDirectory(attempt.resolve("nested")).resolve("file"), new byte[40]);
        Files.write(files.createDirectory(job).resolve("partial"), new byte[3]);
        Files.write(Files.createDirectory(directory.resolve("unrelated")).resolve("data"), new byte[90]);
        Files.createDirectory(directory.resolve("x".repeat(36) + "-invalid"));
        var known = Set.of(job, remoteOnly);
        when(artifacts.jobUsage(known)).thenReturn(Map.of(job, 512L, remoteOnly, 128L));

        assertThat(files.usage(known)).containsExactlyInAnyOrderEntriesOf(Map.of(job, 655L, remoteOnly, 128L));
    }

    @Test
    void confirmedCleanupRemovesEveryAttemptOfOnlyTheSelectedJobBeforeDeletingArtifacts() throws Exception {
        var files = files(directory);
        UUID job = UUID.randomUUID();
        Path first = files.createDirectory(job);
        Path second = files.createDirectory(job);
        Path other = files.createDirectory(UUID.randomUUID());
        Files.write(Files.createDirectory(first.resolve("nested")).resolve("file"), new byte[100]);
        Files.write(second.resolve("partial"), new byte[5]);
        doAnswer(invocation -> {
            assertThat(first).doesNotExist();
            assertThat(second).doesNotExist();
            return null;
        }).when(artifacts).deleteJob(job);

        files.clean(job, true);
        files.clean(job, true); // Reintento después de perder la respuesta HTTP.
        verify(artifacts, times(2)).deleteJob(job);
        assertThat(other).isDirectory();
        verify(metrics).temporaryRemoved(100);
        verify(metrics).temporaryRemoved(5);
    }

    @Test
    void packagingCleanupKeepsTheCompletedZip() throws Exception {
        var files = files(directory);
        UUID job = UUID.randomUUID();
        Path attempt = files.createDirectory(job);
        Files.writeString(attempt.resolve("installer.exe"), "installer");
        files.clean(job, false);
        assertThat(attempt).doesNotExist();
        verifyNoInteractions(artifacts);
    }

    @Test
    void inaccessibleTemporaryStorageNeverLooksLikeAnEmptyInventoryOrSuccessfulCleanup() throws Exception {
        Path invalidBase = Files.writeString(directory.resolve("file-not-directory"), "data");
        var files = files(invalidBase);
        UUID job = UUID.randomUUID();
        assertThatThrownBy(() -> files.usage(Set.of(job)))
                .isInstanceOf(InfrastructureException.class).hasMessage("download_inventory_failed");
        assertThatThrownBy(() -> files.clean(job, true))
                .isInstanceOf(InfrastructureException.class).hasMessage("download_cleanup_failed");
        assertThatThrownBy(() -> files.createDirectory(job))
                .isInstanceOf(InfrastructureException.class).hasMessage("temp_directory_creation_failed");
        verify(artifacts, never()).deleteJob(job);
    }

    @Test
    void failedArtifactDeletionPropagatesSoTheCoordinatorRetainsTheReservation() {
        UUID job = UUID.randomUUID();
        var failure = new InfrastructureException("minio_cleanup_failed", new IllegalStateException("busy"));
        doThrow(failure).when(artifacts).deleteJob(job);
        assertThatThrownBy(() -> files(directory).clean(job, true)).isSameAs(failure);
    }

    private DownloadJobFiles files(Path base) {
        return new DownloadJobFiles(artifacts, metrics, new DownloadProperties(100, DataSize.ofGigabytes(4), 5,
                Duration.ofSeconds(10), Duration.ofMinutes(15), Duration.ofMinutes(30), base.toString()));
    }
}
