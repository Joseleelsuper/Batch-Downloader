package es.ubu.batchdownloader.downloadworker.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("download-worker.storage")
public record StorageProperties(
        @DefaultValue("http://localhost:9000") @NotBlank String endpoint,
        @DefaultValue("minioadmin") @NotBlank String accessKey,
        @DefaultValue("minioadmin") @NotBlank String secretKey,
        @DefaultValue("installers") @NotBlank String bucket,
        @DefaultValue("24h") @NotNull Duration presignedUrlTtl) {
}
