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

/** Verifica la compensación de una subida multipart interrumpida. */
class MinioArtifactStoreTest {
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
