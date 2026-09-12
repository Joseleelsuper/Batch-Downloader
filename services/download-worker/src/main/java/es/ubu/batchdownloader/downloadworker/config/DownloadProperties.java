package es.ubu.batchdownloader.downloadworker.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Centraliza límites y reservas del pipeline y valida las relaciones entre concurrencia global,
 * ventana por trabajo y tamaño multipart.
 *
 * @param maxItems Máximo positivo de elementos admitidos por trabajo.
 * @param maxFileSize Límite de bytes de un instalador, expresado como DataSize.
 * @param maxTotalSize Límite de bytes del conjunto de instaladores y reserva defensiva si se
 *     desconoce su tamaño.
 * @param maxRedirects Número máximo no negativo de redirecciones HTTP que se permite seguir.
 * @param connectTimeout Duración máxima para abrir una conexión HTTP.
 * @param requestTimeout Duración máxima configurada para una petición de descarga.
 * @param concurrency Número positivo de hilos globales para resolver y transferir instaladores.
 * @param jobConcurrency Número positivo de permisos globales y consumidores de trabajos.
 * @param perJobConcurrency Máximo de tareas por ventana de un trabajo; no puede superar
 *     concurrency.
 * @param packagingConcurrency Máximo positivo de trabajos que pueden comprimir un ZIP a la vez.
 * @param zipLevel Nivel de compresión entre cero y nueve; cero prioriza tiempo de empaquetado.
 * @param minFreeSpace Margen de espacio que debe permanecer libre en el volumen temporal.
 * @param largeJobThreshold Tamaño que, al superarse, hace que un trabajo consuma toda la capacidad
 *     de trabajos.
 * @param multipartPartSize Tamaño de cada parte de subida; debe ser al menos cinco MiB.
 * @param inboxLease Duración de una reserva de evento antes de poder recuperar su procesamiento.
 * @param tempDirectory Directorio base de los temporales exclusivos de cada trabajo.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.config.WorkerConfiguration
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @since 0.1.0
 * @version 0.1.0
 * @category Configuración del worker
 */
@Validated
@ConfigurationProperties("download-worker.download")
public record DownloadProperties(
        @DefaultValue("100") @Min(1) int maxItems,
        @DefaultValue("4GB") @NotNull DataSize maxFileSize,
        @DefaultValue("20GB") @NotNull DataSize maxTotalSize,
        @DefaultValue("5") @Min(0) int maxRedirects,
        @DefaultValue("10s") @NotNull Duration connectTimeout,
        @DefaultValue("15m") @NotNull Duration requestTimeout,
        @DefaultValue("16") @Min(1) int concurrency,
        @DefaultValue("8") @Min(1) int jobConcurrency,
        @DefaultValue("2") @Min(1) int perJobConcurrency,
        @DefaultValue("4") @Min(1) int packagingConcurrency,
        @DefaultValue("0") @Min(0) @Max(9) int zipLevel,
        @DefaultValue("30GB") @NotNull DataSize minFreeSpace,
        @DefaultValue("2GB") @NotNull DataSize largeJobThreshold,
        @DefaultValue("16MB") @NotNull DataSize multipartPartSize,
        @DefaultValue("30m") @NotNull Duration inboxLease,
        @DefaultValue("/tmp/batch-downloader") String tempDirectory) {
    /**
     * Conserva la construcción abreviada con dos trabajos, ventana de hasta cuatro, un ZIP
     * simultáneo, nivel uno y margen temporal de diez GiB.
     *
     * @param maxItems Máximo positivo de elementos admitidos por trabajo.
     * @param maxFileSize Límite de bytes de un instalador, expresado como DataSize.
     * @param maxTotalSize Límite de bytes del conjunto de instaladores y reserva defensiva si se
     *     desconoce su tamaño.
     * @param maxRedirects Número máximo no negativo de redirecciones HTTP que se permite seguir.
     * @param connectTimeout Duración máxima para abrir una conexión HTTP.
     * @param requestTimeout Duración máxima configurada para una petición de descarga.
     * @param concurrency Número positivo de hilos globales para resolver y transferir instaladores.
     * @param inboxLease Duración de una reserva de evento antes de poder recuperar su
     *     procesamiento.
     * @param tempDirectory Directorio base de los temporales exclusivos de cada trabajo.
     */
    public DownloadProperties(
            int maxItems,
            DataSize maxFileSize,
            DataSize maxTotalSize,
            int maxRedirects,
            Duration connectTimeout,
            Duration requestTimeout,
            int concurrency,
            Duration inboxLease,
            String tempDirectory) {
        this(
                maxItems,
                maxFileSize,
                maxTotalSize,
                maxRedirects,
                connectTimeout,
                requestTimeout,
                concurrency,
                2,
                Math.min(4, concurrency),
                1,
                1,
                DataSize.ofGigabytes(10),
                DataSize.ofGigabytes(2),
                DataSize.ofMegabytes(16),
                inboxLease,
                tempDirectory);
    }

    /**
     * Enlaza la configuración completa y comprueba que la ventana cabe en el pool y que las partes
     * multipart cumplen el mínimo.
     *
     * @param maxItems Máximo positivo de elementos admitidos por trabajo.
     * @param maxFileSize Límite de bytes de un instalador, expresado como DataSize.
     * @param maxTotalSize Límite de bytes del conjunto de instaladores y reserva defensiva si se
     *     desconoce su tamaño.
     * @param maxRedirects Número máximo no negativo de redirecciones HTTP que se permite seguir.
     * @param connectTimeout Duración máxima para abrir una conexión HTTP.
     * @param requestTimeout Duración máxima configurada para una petición de descarga.
     * @param concurrency Número positivo de hilos globales para resolver y transferir instaladores.
     * @param jobConcurrency Número positivo de permisos globales y consumidores de trabajos.
     * @param perJobConcurrency Máximo de tareas por ventana de un trabajo; no puede superar
     *     concurrency.
     * @param packagingConcurrency Máximo positivo de trabajos que pueden comprimir un ZIP a la vez.
     * @param zipLevel Nivel de compresión entre cero y nueve; cero prioriza tiempo de empaquetado.
     * @param minFreeSpace Margen de espacio que debe permanecer libre en el volumen temporal.
     * @param largeJobThreshold Tamaño que, al superarse, hace que un trabajo consuma toda la
     *     capacidad de trabajos.
     * @param multipartPartSize Tamaño de cada parte de subida; debe ser al menos cinco MiB.
     * @param inboxLease Duración de una reserva de evento antes de poder recuperar su
     *     procesamiento.
     * @param tempDirectory Directorio base de los temporales exclusivos de cada trabajo.
     * @throws IllegalArgumentException si la ventana supera la concurrencia global o las partes son
     *     menores que cinco MiB.
     */
    @ConstructorBinding
    public DownloadProperties {
        if (perJobConcurrency > concurrency) {
            throw new IllegalArgumentException("perJobConcurrency must not exceed concurrency");
        }
        if (multipartPartSize.toBytes() < 5L * 1024 * 1024) {
            throw new IllegalArgumentException("multipartPartSize must be at least 5 MiB");
        }
    }
}
