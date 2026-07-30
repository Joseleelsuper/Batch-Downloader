package es.ubu.batchdownloader.downloadworker.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
        @DefaultValue("30m") @NotNull Duration inboxLease,
        @DefaultValue("/tmp/batch-downloader") String tempDirectory) {
}
