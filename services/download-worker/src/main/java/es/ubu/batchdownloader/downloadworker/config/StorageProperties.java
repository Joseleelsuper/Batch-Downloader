package es.ubu.batchdownloader.downloadworker.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Representa los datos inmutables de {@code StorageProperties}.
 *
 * @param endpoint Valor de {@code endpoint} incluido en el record.
 * @param accessKey Valor de {@code accessKey} incluido en el record.
 * @param secretKey Valor de {@code secretKey} incluido en el record.
 * @param bucket Valor de {@code bucket} incluido en el record.
 * @param presignedUrlTtl Valor de {@code presignedUrlTtl} incluido en el record.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Validated
@ConfigurationProperties("download-worker.storage")
public record StorageProperties(
        @DefaultValue("http://localhost:9000") @NotBlank String endpoint,
        @DefaultValue("minioadmin") @NotBlank String accessKey,
        @DefaultValue("minioadmin") @NotBlank String secretKey,
        @DefaultValue("installers") @NotBlank String bucket,
        @DefaultValue("24h") @NotNull Duration presignedUrlTtl) {
}
