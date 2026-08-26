package es.ubu.batchdownloader.downloads.infrastructure.storage;

import es.ubu.batchdownloader.downloads.application.port.ZipUriSigner;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.lang.Nullable;

/**
 * Implementa el componente {@code MinioZipUriSigner}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
class MinioZipUriSigner implements ZipUriSigner {
    /**
     * Estado {@code minio} mantenido por {@code MinioZipUriSigner}.
     */
    private final MinioClient minio;
    /**
     * Estado {@code bucket} mantenido por {@code MinioZipUriSigner}.
     */
    private final String bucket;
    /**
     * Estado {@code region} mantenido por {@code MinioZipUriSigner}.
     */
    private final String region;
    private final Counter successfulSigns;
    private final Counter failedSigns;

    /**
     * Inicializa una instancia de {@code MinioZipUriSigner}.
     *
     * @param publicEndpoint Valor de {@code publicEndpoint} utilizado por la operación.
     * @param accessKey Valor de {@code accessKey} utilizado por la operación.
     * @param secretKey Valor de {@code secretKey} utilizado por la operación.
     * @param bucket Valor de {@code bucket} utilizado por la operación.
     * @param region Valor de {@code region} utilizado por la operación.
     */
    @Autowired
    MinioZipUriSigner(
            @Value("${app.minio.public-endpoint}") String publicEndpoint,
            @Value("${app.minio.access-key}") String accessKey,
            @Value("${app.minio.secret-key}") String secretKey,
            @Value("${app.minio.bucket}") String bucket,
            @Value("${app.minio.region}") String region,
            @Nullable MeterRegistry registry) {
        // La firma previa es local y este cliente no necesita alcanzar MinIO. Su endpoint
        // debe ser la dirección utilizada por el navegador, porque Host forma parte de
        // la firma S3 y no puede sustituirse después de firmar.
        this.minio = MinioClient.builder()
                .endpoint(publicEndpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
        this.region = region;
        this.successfulSigns = registry == null
                ? null
                : registry.counter("core_download_signed_redirects", "outcome", "success");
        this.failedSigns = registry == null
                ? null
                : registry.counter("core_download_signed_redirects", "outcome", "failure");
    }

    /** Conserva el constructor anterior para pruebas de firma sin contexto Spring. */
    MinioZipUriSigner(
            String publicEndpoint,
            String accessKey,
            String secretKey,
            String bucket,
            String region) {
        this.minio = MinioClient.builder()
                .endpoint(publicEndpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
        this.region = region;
        this.successfulSigns = null;
        this.failedSigns = null;
    }

    /**
     * Implementa {@code signGet} para {@code MinioZipUriSigner}.
     *
     * @param objectKey Valor de {@code objectKey} utilizado por la operación.
     * @param validity Valor de {@code validity} utilizado por la operación.
     * @return Resultado producido por {@code signGet}.
     * @throws IllegalStateException Si el estado actual impide completar la operación.
     */
    @Override
    public URI signGet(String objectKey, Duration validity) {
        return signGet(objectKey, "batch-downloader.zip", validity);
    }

    /** Firma también las cabeceras de tipo y disposición que devolverá S3. */
    @Override
    public URI signGet(String objectKey, String filename, Duration validity) {
        try {
            String safeFilename = filename == null
                    ? "batch-downloader.zip"
                    : filename.replaceAll("[^A-Za-z0-9._-]", "_");
            String url = minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    // Proporcionar la región conocida mantiene la firma previa sin red.
                    // El hostname público puede ser inaccesible desde este contenedor.
                    .region(region)
                    .extraQueryParams(Map.of(
                            "response-content-disposition",
                            "attachment; filename=\"" + safeFilename + "\"",
                            "response-content-type",
                            "application/zip"))
                    .expiry(Math.toIntExact(validity.toSeconds()), TimeUnit.SECONDS)
                    .build());
            if (successfulSigns != null) successfulSigns.increment();
            return URI.create(url);
        } catch (Exception exception) {
            if (failedSigns != null) failedSigns.increment();
            throw new IllegalStateException("minio_presign_failed", exception);
        }
    }
}
