package es.ubu.batchdownloader.downloads.infrastructure.storage;

import es.ubu.batchdownloader.downloads.application.port.DownloadArtifactCleaner;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deletes every object owned by an expired job, including defensive leftovers from staging. */
@Component
class MinioDownloadArtifactCleaner implements DownloadArtifactCleaner {
    private final MinioClient minio;
    private final String bucket;

    MinioDownloadArtifactCleaner(MinioClient minio, @Value("${app.minio.bucket}") String bucket) {
        this.minio = minio;
        this.bucket = bucket;
    }

    @Override
    @SuppressWarnings("java:S2221") // MinIO exposes heterogeneous checked exceptions.
    public void deleteJobArtifacts(UUID jobId) {
        String prefix = "jobs/" + jobId + "/";
        try {
            for (var result : minio.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .recursive(true)
                    .build())) {
                minio.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket)
                        .object(result.get().objectName())
                        .build());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("minio_cleanup_failed", exception);
        }
    }
}
