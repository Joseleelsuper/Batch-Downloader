package es.ubu.batchdownloader.downloadworker.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.config.StorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Comprueba compensación de objetos parciales cuando falla el productor del ZIP transmitido a
 * almacenamiento.
 *
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.storage.MinioArtifactStore
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de integración y mensajería
 */
class MinioArtifactStoreTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptedInventoryOrCleanupPreservesInterruptionAndDoesNotConfirmDeletion(boolean cleanup)
            throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioMultipartClient multipart = mock(MinioMultipartClient.class);
        when(client.bucketExists(any())).thenReturn(true);
        when(client.listObjects(any())).thenReturn(java.util.List.of());
        var jobId = java.util.UUID.randomUUID();
        String key = "jobs/" + jobId + "/bundle.zip";
        when(multipart.incomplete("zips", key)).thenReturn(
                java.util.List.of(new MinioMultipartClient.PendingUpload(key, "pending")));
        when(multipart.listPartsAsync(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new CompletableFuture<>());
        when(multipart.abortMultipartUploadAsync(any(), any(), any(), any(), any(), any()))
                .thenReturn(new CompletableFuture<>());
        var store = new MinioArtifactStore(client,
                new StorageProperties("http://minio", "key", "secret", "zips", Duration.ofHours(1)), multipart);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> {
                if (cleanup) store.deleteJob(jobId);
                else store.jobUsage(java.util.Set.of(jobId));
            }).isInstanceOf(InfrastructureException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(client, org.mockito.Mockito.never()).removeObject(any());
        } finally {
            Thread.interrupted(); // No propagar la interrupción simulada al ejecutor de JUnit.
        }
    }

    @Test
    void repeatedInterruptionWaitsForUploaderToStopBeforeDeletingPartialObject() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any())).thenReturn(true);
        var started = new CompletableFuture<Void>();
        var allowStop = new CompletableFuture<Void>();
        when(client.putObject(any())).thenAnswer(invocation -> {
            started.complete(null);
            allowStop.get(5, TimeUnit.SECONDS); // Emula el get interrumpible del wrapper MinioClient.
            return null;
        });
        var store = new MinioArtifactStore(client,
                new StorageProperties("http://minio", "key", "secret", "zips", Duration.ofHours(1)));
        var failure = new CompletableFuture<Throwable>();
        var preserved = new AtomicBoolean();
        Thread producer = Thread.ofVirtual().start(() -> {
            try { store.putStreaming("jobs/id/bundle.zip", "application/zip", 5L * 1024 * 1024, output -> {}); }
            catch (RuntimeException exception) {
                preserved.set(Thread.currentThread().isInterrupted());
                failure.complete(exception);
            }
        });
        try {
            started.get(5, TimeUnit.SECONDS);
            producer.interrupt();
            producer.interrupt();
            assertThatThrownBy(() -> failure.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            verify(client, org.mockito.Mockito.never()).removeObject(any());
            allowStop.complete(null);
            assertThat(failure.get(5, TimeUnit.SECONDS)).isInstanceOf(InfrastructureException.class)
                    .hasMessage("minio_upload_interrupted");
            assertThat(preserved).isTrue();
            verify(client).removeObject(any());
        } finally {
            allowStop.complete(null);
            producer.interrupt();
            producer.join(5000);
        }
    }

    @Test
    void inventoryCountsMultipartAndCleanupRequiresTheirConfirmedAbsence() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioMultipartClient multipart = mock(MinioMultipartClient.class);
        when(client.bucketExists(any())).thenReturn(true);
        when(client.listObjects(any())).thenReturn(java.util.List.of());
        java.util.UUID jobId = java.util.UUID.randomUUID();
        String key = "jobs/" + jobId + "/bundle.zip";
        var upload = new MinioMultipartClient.PendingUpload(key, "incomplete");
        when(multipart.incomplete("zips", key)).thenReturn(java.util.List.of(upload));
        io.minio.messages.Part part = mock(io.minio.messages.Part.class);
        when(part.partSize()).thenReturn(512L);
        io.minio.messages.ListPartsResult parts = mock(io.minio.messages.ListPartsResult.class);
        when(parts.partList()).thenReturn(java.util.List.of(part));
        io.minio.ListPartsResponse partsResponse = mock(io.minio.ListPartsResponse.class);
        when(partsResponse.result()).thenReturn(parts);
        when(multipart.listPartsAsync(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(partsResponse));
        when(multipart.abortMultipartUploadAsync(any(), any(), any(), any(), any(), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        MinioArtifactStore store = new MinioArtifactStore(client,
                new StorageProperties("http://minio", "key", "secret", "zips", Duration.ofHours(1)), multipart);

        assertThat(store.jobUsage(java.util.Set.of(jobId))).containsEntry(jobId, 512L);
        assertThatThrownBy(() -> store.deleteJob(jobId)).isInstanceOf(InfrastructureException.class)
                .hasMessage("minio_cleanup_failed");

        when(multipart.incomplete("zips", key)).thenReturn(java.util.List.of(upload), java.util.List.of());
        store.deleteJob(jobId);
        verify(multipart, org.mockito.Mockito.times(2))
                .abortMultipartUploadAsync(any(), any(), any(), any(), any(), any());
    }
    /**
     * Hace fallar el escritor con IOException y comprueba InfrastructureException y la solicitud de
     * eliminación del objeto en MinIO.
     */
    @Test
    void removesThePartialObjectWhenTheStreamWriterFails() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        MinioArtifactStore store = new MinioArtifactStore(
                client,
                new StorageProperties(
                        "http://minio", "key", "secret", "zips", Duration.ofHours(1)));

        assertThatThrownBy(() -> store.putStreaming(
                        "jobs/id/bundle.zip",
                        "application/zip",
                        5L * 1024 * 1024,
                        output -> {
                            throw new IOException("writer failed");
                        }))
                .isInstanceOf(InfrastructureException.class);

        verify(client).removeObject(any(RemoveObjectArgs.class));
    }
}
