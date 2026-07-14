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

@Component
class MinioZipUriSigner implements ZipUriSigner {
    private final MinioClient minio;
    private final String bucket;
    private final String region;

    MinioZipUriSigner(
            @Value("${app.minio.public-endpoint}") String publicEndpoint,
            @Value("${app.minio.access-key}") String accessKey,
            @Value("${app.minio.secret-key}") String secretKey,
            @Value("${app.minio.bucket}") String bucket,
            @Value("${app.minio.region}") String region) {
        // Presigning is local, so this client does not need to reach MinIO. Its
        // endpoint must be the address used by the browser because the Host is
        // part of the S3 signature and cannot be replaced after signing.
        this.minio = MinioClient.builder()
                .endpoint(publicEndpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
        this.region = region;
    }

    @Override
    public URI signGet(String objectKey, Duration validity) {
        try {
            String url = minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    // Supplying the known region keeps presigning offline. The public
                    // hostname may intentionally be unreachable from this container.
                    .region(region)
                    .extraQueryParams(Map.of(
                            "response-content-disposition",
                            "attachment; filename=\"batch-downloader.zip\""))
                    .expiry(Math.toIntExact(validity.toSeconds()), TimeUnit.SECONDS)
                    .build());
            return URI.create(url);
        } catch (Exception exception) {
            throw new IllegalStateException("minio_presign_failed", exception);
        }
    }
}
