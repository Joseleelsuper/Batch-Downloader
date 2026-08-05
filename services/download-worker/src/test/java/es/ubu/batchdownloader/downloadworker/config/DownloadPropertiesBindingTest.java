package es.ubu.batchdownloader.downloadworker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class DownloadPropertiesBindingTest {

    @Test
    void bindsCanonicalConstructorWhenCompatibilityConstructorAlsoExists() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("download-worker.download.max-items", "100"),
                Map.entry("download-worker.download.max-file-size", "4GB"),
                Map.entry("download-worker.download.max-total-size", "20GB"),
                Map.entry("download-worker.download.max-redirects", "5"),
                Map.entry("download-worker.download.connect-timeout", "10s"),
                Map.entry("download-worker.download.request-timeout", "15m"),
                Map.entry("download-worker.download.concurrency", "8"),
                Map.entry("download-worker.download.job-concurrency", "2"),
                Map.entry("download-worker.download.per-job-concurrency", "4"),
                Map.entry("download-worker.download.packaging-concurrency", "1"),
                Map.entry("download-worker.download.zip-level", "1"),
                Map.entry("download-worker.download.min-free-space", "10GB"),
                Map.entry("download-worker.download.large-job-threshold", "2GB"),
                Map.entry("download-worker.download.multipart-part-size", "16MB"),
                Map.entry("download-worker.download.inbox-lease", "30m"),
                Map.entry("download-worker.download.temp-directory", "/tmp/batch-downloader")));

        DownloadProperties properties = new Binder(source)
                .bind("download-worker.download", Bindable.of(DownloadProperties.class))
                .orElseThrow(() -> new AssertionError("DownloadProperties was not bound"));

        assertThat(properties.jobConcurrency()).isEqualTo(2);
        assertThat(properties.perJobConcurrency()).isEqualTo(4);
        assertThat(properties.packagingConcurrency()).isEqualTo(1);
        assertThat(properties.multipartPartSize().toMegabytes()).isEqualTo(16);
    }
}
