package es.ubu.batchdownloader.downloadworker.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import es.ubu.batchdownloader.downloadworker.config.StorageProperties;
import io.minio.MinioClient;
import io.minio.MinioAsyncClient;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifica contra MinIO real el inventario de partes y su aborto, no solo mocks del SDK. */
@Testcontainers(disabledWithoutDocker = true)
class MinioArtifactStoreIntegrationTest {
    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            "ghcr.io/l33tlamer/minio-backup@sha256:a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e")
            .withEnv("MINIO_ROOT_USER", "test-worker")
            .withEnv("MINIO_ROOT_PASSWORD", "test-worker-secret")
            .withExposedPorts(9000)
            .withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @Test
    void inventoriesAndRemovesCompletedObjectsAndAbandonedMultipart() throws Exception {
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        try (MinioClient client = MinioClient.builder().endpoint(endpoint)
                .credentials("test-worker", "test-worker-secret").build();
                MinioAsyncClient multipart = MinioAsyncClient.builder().endpoint(endpoint)
                        .credentials("test-worker", "test-worker-secret").build()) {
            StorageProperties properties = new StorageProperties(endpoint, "test-worker", "test-worker-secret",
                    "test-zips", Duration.ofHours(1));
            MinioArtifactStore store = new MinioArtifactStore(client, properties, new MinioMultipartClient(multipart));
            UUID jobId = UUID.randomUUID();
            String prefix = "jobs/" + jobId + "/";
            store.putBytes(prefix + "bundle.zip", new byte[]{1, 2, 3}, "application/zip", 5 * 1024 * 1024);
            store.putBytes(prefix + "manifest.json", new byte[]{4, 5}, "application/json", 5 * 1024 * 1024);
            String uploadId = multipart.createMultipartUploadAsync(properties.bucket(), null,
                    prefix + "bundle.zip", null, null).get().result().uploadId();
            multipart.uploadPartAsync(properties.bucket(), null, prefix + "bundle.zip", new byte[512],
                    512, uploadId, 1, null, null).get();

            var parts = multipart.listPartsAsync(properties.bucket(), null, prefix + "bundle.zip",
                    1000, 0, uploadId, null, null).get().result();
            assertThat(parts.partList()).hasSize(1);
            assertThat(parts.partList().getFirst().partSize()).isEqualTo(512);
            assertThat(new MinioMultipartClient(multipart)
                    .incomplete(properties.bucket(), prefix + "bundle.zip")).hasSize(1);

            // Recupera también un trabajo que cayó sin ningún objeto completo ni directorio temporal.
            UUID onlyMultipart = UUID.randomUUID();
            String orphanKey = "jobs/" + onlyMultipart + "/bundle.zip";
            String orphanUpload = multipart.createMultipartUploadAsync(properties.bucket(), null,
                    orphanKey, null, null).get().result().uploadId();
            multipart.uploadPartAsync(properties.bucket(), null, orphanKey, new byte[123],
                    123, orphanUpload, 1, null, null).get();
            var knownJobs = java.util.Set.of(jobId, onlyMultipart);
            assertThat(store.jobUsage(knownJobs)).containsEntry(jobId, 517L).containsEntry(onlyMultipart, 123L);
            store.deleteJob(jobId);
            store.deleteJob(onlyMultipart);
            assertThat(store.jobUsage(knownJobs)).isEmpty();
            store.deleteJob(jobId); // Reintento idempotente tras perder la respuesta del borrado.
        }
    }
}
