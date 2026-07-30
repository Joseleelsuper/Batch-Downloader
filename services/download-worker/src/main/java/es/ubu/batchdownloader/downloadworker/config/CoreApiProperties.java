package es.ubu.batchdownloader.downloadworker.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Representa los datos inmutables de {@code CoreApiProperties}.
 *
 * @param baseUrl Valor de {@code baseUrl} incluido en el record.
 * @param serviceToken Valor de {@code serviceToken} incluido en el record.
 * @param timeout Valor de {@code timeout} incluido en el record.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Validated
@ConfigurationProperties("download-worker.core-api")
public record CoreApiProperties(
        @DefaultValue("http://core-api:8080") @NotBlank String baseUrl,
        @DefaultValue("development-internal-token") @NotBlank String serviceToken,
        @DefaultValue("10s") @NotNull Duration timeout) {
}
