package es.ubu.batchdownloader.downloadworker.infrastructure.storage;

import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.config.StorageProperties;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.UploadObjectArgs;
import java.nio.file.Path;

@SuppressWarnings("java:S2221") // MinIO's public operations declare a heterogeneous checked Exception set.
public class MinioArtifactStore implements ArtifactStore {
    private final MinioClient client;
    private final StorageProperties properties;
    private volatile boolean bucketReady;

    public MinioArtifactStore(MinioClient client, StorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void put(String objectKey, Path source, String contentType) {
        ensureBucket();
        try {
            client.uploadObject(UploadObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .filename(source.toString())
                    .contentType(contentType)
                    .build());
        } catch (Exception exception) {
            throw new InfrastructureException("minio_upload_failed", exception);
        }
    }

    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            try {
                boolean exists = client.bucketExists(
                        BucketExistsArgs.builder().bucket(properties.bucket()).build());
                if (!exists) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
                }
                bucketReady = true;
            } catch (Exception exception) {
                throw new InfrastructureException("minio_bucket_initialization_failed", exception);
            }
        }
    }
}
