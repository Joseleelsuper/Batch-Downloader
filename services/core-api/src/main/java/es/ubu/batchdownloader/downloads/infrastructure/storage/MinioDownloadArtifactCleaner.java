package es.ubu.batchdownloader.downloads.infrastructure.storage;

import es.ubu.batchdownloader.downloads.application.port.DownloadArtifactCleaner;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Implementa el componente {@code MinioDownloadArtifactCleaner}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
class MinioDownloadArtifactCleaner implements DownloadArtifactCleaner {
    /**
     * Estado {@code minio} mantenido por {@code MinioDownloadArtifactCleaner}.
     */
    private final MinioClient minio;
    /**
     * Estado {@code bucket} mantenido por {@code MinioDownloadArtifactCleaner}.
     */
    private final String bucket;

    /**
     * Inicializa una instancia de {@code MinioDownloadArtifactCleaner}.
     *
     * @param minio Valor de {@code minio} utilizado por la operación.
     * @param bucket Valor de {@code bucket} utilizado por la operación.
     */
    MinioDownloadArtifactCleaner(MinioClient minio, @Value("${app.minio.bucket}") String bucket) {
        this.minio = minio;
        this.bucket = bucket;
    }

    /**
     * Elimina el recurso solicitado mediante {@code deleteJobArtifacts}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @throws IllegalStateException Si el estado actual impide completar la operación.
     */
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
