package es.ubu.batchdownloader.downloadworker.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
