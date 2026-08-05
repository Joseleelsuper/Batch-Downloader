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
 * Representa los datos inmutables de {@code DownloadProperties}.
 *
 * @param maxItems Valor de {@code maxItems} incluido en el record.
 * @param maxFileSize Valor de {@code maxFileSize} incluido en el record.
 * @param maxTotalSize Valor de {@code maxTotalSize} incluido en el record.
 * @param maxRedirects Valor de {@code maxRedirects} incluido en el record.
 * @param connectTimeout Valor de {@code connectTimeout} incluido en el record.
 * @param requestTimeout Valor de {@code requestTimeout} incluido en el record.
 * @param concurrency Valor de {@code concurrency} incluido en el record.
 * @param jobConcurrency Trabajos normales que pueden ejecutarse simultáneamente.
 * @param perJobConcurrency Descargas simultáneas máximas por trabajo.
 * @param packagingConcurrency ZIP que pueden escribirse simultáneamente.
 * @param zipLevel Nivel de compresión del ZIP.
 * @param minFreeSpace Reserva que siempre debe permanecer libre.
 * @param largeJobThreshold Tamaño declarado a partir del que el trabajo es exclusivo.
 * @param multipartPartSize Tamaño de parte para la subida multipart.
 * @param inboxLease Valor de {@code inboxLease} incluido en el record.
 * @param tempDirectory Valor de {@code tempDirectory} incluido en el record.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
        @DefaultValue("8") @Min(1) int concurrency,
        @DefaultValue("2") @Min(1) int jobConcurrency,
        @DefaultValue("4") @Min(1) int perJobConcurrency,
        @DefaultValue("1") @Min(1) int packagingConcurrency,
        @DefaultValue("1") @Min(0) @Max(9) int zipLevel,
        @DefaultValue("10GB") @NotNull DataSize minFreeSpace,
        @DefaultValue("2GB") @NotNull DataSize largeJobThreshold,
        @DefaultValue("16MB") @NotNull DataSize multipartPartSize,
        @DefaultValue("30m") @NotNull Duration inboxLease,
        @DefaultValue("/tmp/batch-downloader") String tempDirectory) {
    /**
     * Conserva el constructor previo para dobles de prueba y consumidores embebidos.
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

    /** Valida relaciones que no puede expresar Bean Validation por campo. */
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
