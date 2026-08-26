package es.ubu.batchdownloader.downloadworker.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Representa los datos inmutables de {@code MessagingProperties}.
 *
 * @param commandExchange Valor de {@code commandExchange} incluido en el record.
 * @param eventExchange Valor de {@code eventExchange} incluido en el record.
 * @param inputRoutingKey Valor de {@code inputRoutingKey} incluido en el record.
 * @param inputQueue Valor de {@code inputQueue} incluido en el record.
 * @param cancellationRoutingKey Valor de {@code cancellationRoutingKey} incluido en el record.
 * @param cancellationQueue Valor de {@code cancellationQueue} incluido en el record.
 * @param deadLetterExchange Valor de {@code deadLetterExchange} incluido en el record.
 * @param deadLetterQueue Valor de {@code deadLetterQueue} incluido en el record.
 * @param capacityWaitQueue Cola con TTL para esperas que no son fallos.
 * @param capacityWaitDelay Tiempo antes de devolver un trabajo aplazado a la cola principal.
 * @param retryAttempts Valor de {@code retryAttempts} incluido en el record.
 * @param retryInitialInterval Valor de {@code retryInitialInterval} incluido en el record.
 * @param retryMultiplier Valor de {@code retryMultiplier} incluido en el record.
 * @param retryMaxInterval Valor de {@code retryMaxInterval} incluido en el record.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Validated
@ConfigurationProperties("download-worker.messaging")
public record MessagingProperties(
        @DefaultValue("batch.commands.v1") @NotBlank String commandExchange,
        @DefaultValue("batch.events.v1") @NotBlank String eventExchange,
        @DefaultValue("download.job.requested") @NotBlank String inputRoutingKey,
        @DefaultValue("download-worker.download.job.requested.v1") @NotBlank String inputQueue,
        @DefaultValue("download.job.cancel-requested") @NotBlank String cancellationRoutingKey,
        @DefaultValue("download-worker.download.job.cancel-requested.v1") @NotBlank String cancellationQueue,
        @DefaultValue("batch.dead-letter.v1") @NotBlank String deadLetterExchange,
        @DefaultValue("download-worker.download.job.requested.v1.dlq") @NotBlank String deadLetterQueue,
        @DefaultValue("download-worker.download.job.capacity-wait.v1") @NotBlank String capacityWaitQueue,
        @DefaultValue("30s") Duration capacityWaitDelay,
        @DefaultValue("3") @Min(1) int retryAttempts,
        @DefaultValue("1s") Duration retryInitialInterval,
        @DefaultValue("2.0") @DecimalMin("1.0") double retryMultiplier,
        @DefaultValue("10s") Duration retryMaxInterval) {
}
