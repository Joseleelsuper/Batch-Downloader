package es.ubu.batchdownloader.downloadworker.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.unit.DataSize;

/**
 * Representa los datos inmutables de {@code StorageProperties}.
 *
 * @param endpoint Valor de {@code endpoint} incluido en el record.
 * @param accessKey Valor de {@code accessKey} incluido en el record.
 * @param secretKey Valor de {@code secretKey} incluido en el record.
 * @param bucket Valor de {@code bucket} incluido en el record.
 * @param presignedUrlTtl Valor de {@code presignedUrlTtl} incluido en el record.
 * @param quota Cuota lógica del bucket de ZIP y manifiestos.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Validated
@ConfigurationProperties("download-worker.storage")
public record StorageProperties(
        @DefaultValue("http://localhost:9000") @NotBlank String endpoint,
        @DefaultValue("minioadmin") @NotBlank String accessKey,
        @DefaultValue("minioadmin") @NotBlank String secretKey,
        @DefaultValue("installers") @NotBlank String bucket,
        @DefaultValue("6h") @NotNull Duration presignedUrlTtl,
        @DefaultValue("120GB") @NotNull DataSize quota) {

    /** Conserva el constructor previo para pruebas y consumidores embebidos. */
    public StorageProperties(
            String endpoint,
            String accessKey,
            String secretKey,
            String bucket,
            Duration presignedUrlTtl) {
        this(endpoint, accessKey, secretKey, bucket, presignedUrlTtl, DataSize.ofGigabytes(120));
    }

    /** Selecciona explícitamente el constructor canónico para el binding de Spring. */
    @ConstructorBinding
    public StorageProperties {}
}
