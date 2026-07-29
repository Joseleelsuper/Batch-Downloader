package es.ubu.batchdownloader.downloadworker.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("download-worker.core-api")
public record CoreApiProperties(
        @DefaultValue("http://core-api:8080") @NotBlank String baseUrl,
        @DefaultValue("development-internal-token") @NotBlank String serviceToken,
        @DefaultValue("10s") @NotNull Duration timeout) {
}
