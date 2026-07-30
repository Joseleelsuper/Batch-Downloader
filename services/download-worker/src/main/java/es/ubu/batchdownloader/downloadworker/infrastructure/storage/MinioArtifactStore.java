package es.ubu.batchdownloader.downloadworker.infrastructure.storage;

import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.config.StorageProperties;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.UploadObjectArgs;
import java.nio.file.Path;

/**
 * Implementa el componente {@code MinioArtifactStore}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@SuppressWarnings("java:S2221") // MinIO's public operations declare a heterogeneous checked Exception set.
public class MinioArtifactStore implements ArtifactStore {
    /**
     * Estado {@code client} mantenido por {@code MinioArtifactStore}.
     */
    private final MinioClient client;
    /**
     * Estado {@code properties} mantenido por {@code MinioArtifactStore}.
     */
    private final StorageProperties properties;
    /**
     * Estado {@code bucketReady} mantenido por {@code MinioArtifactStore}.
     */
    private volatile boolean bucketReady;

    /**
     * Inicializa una instancia de {@code MinioArtifactStore}.
     *
     * @param client Valor de {@code client} utilizado por la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     */
    public MinioArtifactStore(MinioClient client, StorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * Implementa {@code put} para {@code MinioArtifactStore}.
     *
     * @param objectKey Valor de {@code objectKey} utilizado por la operación.
     * @param source Fuente de descarga sobre la que se actúa.
     * @param contentType Valor de {@code contentType} utilizado por la operación.
     * @throws InfrastructureException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
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

    /**
     * Elimina el recurso solicitado mediante {@code delete}.
     *
     * @param objectKey Valor de {@code objectKey} utilizado por la operación.
     * @throws InfrastructureException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    @Override
    public void delete(String objectKey) {
        ensureBucket();
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw new InfrastructureException("minio_delete_failed", exception);
        }
    }

    /**
     * Ejecuta la operación {@code ensureBucket}.
     *
     * @throws InfrastructureException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
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
