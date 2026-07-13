package es.ubu.batchdownloader.downloadworker.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("download-worker.messaging")
public record MessagingProperties(
        @DefaultValue("batch.commands.v1") @NotBlank String commandExchange,
        @DefaultValue("batch.events.v1") @NotBlank String eventExchange,
        @DefaultValue("download.job.requested") @NotBlank String inputRoutingKey,
        @DefaultValue("download-worker.download.job.requested.v1") @NotBlank String inputQueue,
        @DefaultValue("batch.dead-letter.v1") @NotBlank String deadLetterExchange,
        @DefaultValue("download-worker.download.job.requested.v1.dlq") @NotBlank String deadLetterQueue,
        @DefaultValue("3") @Min(1) int retryAttempts,
        @DefaultValue("1s") Duration retryInitialInterval,
        @DefaultValue("2.0") @DecimalMin("1.0") double retryMultiplier,
        @DefaultValue("10s") Duration retryMaxInterval) {
}
