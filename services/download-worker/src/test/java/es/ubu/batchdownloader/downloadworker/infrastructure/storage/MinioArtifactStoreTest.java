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
import org.junit.jupiter.api.Test;

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
