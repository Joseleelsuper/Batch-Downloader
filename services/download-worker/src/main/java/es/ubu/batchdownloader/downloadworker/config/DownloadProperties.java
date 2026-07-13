package es.ubu.batchdownloader.downloadworker.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("download-worker.download")
public record DownloadProperties(
        @DefaultValue("100") @Min(1) int maxItems,
        @DefaultValue("1500MB") @NotNull DataSize maxFileSize,
        @DefaultValue("4GB") @NotNull DataSize maxTotalSize,
        @DefaultValue("5") @Min(0) int maxRedirects,
        @DefaultValue("10s") @NotNull Duration connectTimeout,
        @DefaultValue("15m") @NotNull Duration requestTimeout,
        @DefaultValue("4") @Min(1) int concurrency,
        @DefaultValue("30m") @NotNull Duration inboxLease,
        @DefaultValue("/tmp/batch-downloader") String tempDirectory) {
}
