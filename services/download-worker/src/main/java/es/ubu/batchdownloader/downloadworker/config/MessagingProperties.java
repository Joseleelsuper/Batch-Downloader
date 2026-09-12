package es.ubu.batchdownloader.downloadworker.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Define destinos AMQP y políticas separadas de reintento y espera por capacidad para procesar
 * descargas y cancelaciones.
 *
 * @param commandExchange Nombre del exchange topic de solicitudes de procesamiento.
 * @param eventExchange Nombre del exchange topic de eventos que consume Core.
 * @param inputRoutingKey Clave de las solicitudes de descarga que admite el worker.
 * @param inputQueue Cola duradera de solicitudes de descarga.
 * @param cancellationRoutingKey Clave de las solicitudes de cancelación de trabajos.
 * @param cancellationQueue Cola independiente que permite cancelar mientras los consumidores de
 *     descargas están ocupados.
 * @param deadLetterExchange Exchange de mensajes rechazados tras agotar su política de
 *     procesamiento.
 * @param deadLetterQueue Cola que conserva comandos rechazados para diagnóstico.
 * @param capacityWaitQueue Cola temporal de espera antes de devolver al procesamiento los trabajos
 *     sin capacidad.
 * @param capacityWaitDelay Duración del TTL de la cola de espera por capacidad.
 * @param retryAttempts Máximo positivo de intentos del interceptor de mensajes, incluido el
 *     inicial.
 * @param retryInitialInterval Demora inicial antes de reintentar un fallo del consumidor.
 * @param retryMultiplier Multiplicador de la espera entre intentos; debe ser al menos uno.
 * @param retryMaxInterval Duración máxima de espera entre intentos del consumidor.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.config.RabbitTopologyConfiguration
 * @see es.ubu.batchdownloader.downloadworker.messaging.DownloadJobListener
 * @since 0.1.0
 * @version 0.1.0
 * @category Configuración del worker
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
