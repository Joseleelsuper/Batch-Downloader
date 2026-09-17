package es.ubu.batchdownloader.downloads.infrastructure.storage;

import es.ubu.batchdownloader.downloads.application.port.DownloadArtifactCleaner;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Elimina recursivamente los objetos bajo el prefijo exclusivo de un trabajo expirado.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.application.port.DownloadArtifactCleaner
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobExpiration
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
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
     * Conecta el cliente interno y el bucket donde se conservan los artefactos del worker.
     *
     * @param minio Cliente del almacén configurado con sus credenciales de acceso.
     * @param bucket Contenedor de objetos donde el worker publica los ZIP y sus temporales.
     */
    MinioDownloadArtifactCleaner(MinioClient minio, @Value("${app.minio.bucket}") String bucket) {
        this.minio = minio;
        this.bucket = bucket;
    }

    /**
     * Lista y elimina los objetos bajo jobs/UUID/; un fallo interrumpe el recorrido y permite
     * reintentar los restantes.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @throws IllegalStateException si MinIO no puede listar o eliminar algún objeto.
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
