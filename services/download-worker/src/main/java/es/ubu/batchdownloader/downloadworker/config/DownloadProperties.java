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

/** Límites de recursos del worker; la admisión de trabajos y su presupuesto global pertenecen a Core. */
@Validated
@ConfigurationProperties("download-worker.download")
public record DownloadProperties(
        @DefaultValue("100") @Min(1) int maxItems,
        @DefaultValue("4GB") @NotNull DataSize maxFileSize,
        @DefaultValue("5") @Min(0) int maxRedirects,
        @DefaultValue("10s") @NotNull Duration connectTimeout,
        @DefaultValue("15m") @NotNull Duration requestTimeout,
        @DefaultValue("2") @Min(1) int perJobConcurrency,
        @DefaultValue("2") @Min(1) int packagingConcurrency,
        @DefaultValue("0") @Min(0) @Max(9) int zipLevel,
        @DefaultValue("8GB") @NotNull DataSize minFreeSpace,
        @DefaultValue("16MB") @NotNull DataSize multipartPartSize,
        @DefaultValue("30m") @NotNull Duration inboxLease,
        @DefaultValue("/tmp/batch-downloader") String tempDirectory) {

    public DownloadProperties(int maxItems, DataSize maxFileSize, int maxRedirects,
            Duration connectTimeout, Duration requestTimeout, Duration inboxLease, String tempDirectory) {
        this(maxItems, maxFileSize, maxRedirects, connectTimeout, requestTimeout, 2, 2, 0,
                DataSize.ofGigabytes(8), DataSize.ofMegabytes(16), inboxLease, tempDirectory);
    }

    @ConstructorBinding
    public DownloadProperties {
        if (multipartPartSize.toBytes() < 5L * 1024 * 1024) {
            throw new IllegalArgumentException("multipartPartSize must be at least 5 MiB");
        }
    }
}
