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
 * Firma localmente enlaces GET usando el origen público del navegador y una región conocida, sin
 * consultar MinIO por red.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.application.port.ZipUriSigner
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobAccessService
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
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
     * Configura el cliente de firma y, cuando se proporciona un registro, contadores de firmas
     * correctas y fallidas.
     *
     * @param publicEndpoint Origen del almacén utilizado por el navegador; forma parte de la firma
     *     S3.
     * @param accessKey Identificador de la credencial del almacén de objetos.
     * @param secretKey Secreto de la credencial del almacén; no debe aparecer en respuestas ni
     *     registros.
     * @param bucket Contenedor de objetos donde el worker publica los ZIP y sus temporales.
     * @param region Región S3 conocida que permite firmar sin consultar el almacén por red.
     * @param registry Registro opcional de métricas; null desactiva la instrumentación.
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

    /**
     * Configura el cliente de firma y, cuando se proporciona un registro, contadores de firmas
     * correctas y fallidas.
     *
     * @param publicEndpoint Origen del almacén utilizado por el navegador; forma parte de la firma
     *     S3.
     * @param accessKey Identificador de la credencial del almacén de objetos.
     * @param secretKey Secreto de la credencial del almacén; no debe aparecer en respuestas ni
     *     registros.
     * @param bucket Contenedor de objetos donde el worker publica los ZIP y sus temporales.
     * @param region Región S3 conocida que permite firmar sin consultar el almacén por red.
     */
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
     * Firma la lectura con el nombre predeterminado batch-downloader.zip.
     *
     * @param objectKey Clave interna del ZIP en el almacén de objetos, nunca una URL firmada.
     * @param validity Vigencia de la firma, convertida a segundos y validada por el cliente S3.
     * @return URI temporal del ZIP con descarga como adjunto.
     */
    @Override
    public URI signGet(String objectKey, Duration validity) {
        return signGet(objectKey, "batch-downloader.zip", validity);
    }

    /**
     * Sanitiza el nombre y firma GET con tipo application/zip y disposición adjunta; registra el
     * resultado de la firma.
     *
     * @param objectKey Clave interna del ZIP en el almacén de objetos, nunca una URL firmada.
     * @param filename Nombre sugerido; sus caracteres ajenos a letras ASCII, dígitos, punto, guion
     *     y guion bajo se sustituyen.
     * @param validity Vigencia de la firma, convertida a segundos y validada por el cliente S3.
     * @return URI firmada con el origen público sin alterar después su hostname.
     * @throws IllegalStateException si el nombre, la vigencia o la configuración impiden construir
     *     la firma.
     */
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
