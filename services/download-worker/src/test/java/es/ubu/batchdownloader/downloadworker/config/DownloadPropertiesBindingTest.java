package es.ubu.batchdownloader.downloadworker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Verifica que Spring enlaza los constructores canónicos de configuración aunque se conserven
 * sobrecargas abreviadas.
 *
 * @see es.ubu.batchdownloader.downloadworker.config.DownloadProperties
 * @see es.ubu.batchdownloader.downloadworker.config.StorageProperties
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de procesamiento y capacidad
 */
class DownloadPropertiesBindingTest {

    /**
     * Enlaza configuración completa de MinIO y comprueba destino, identidad de acceso, bucket,
     * vigencia y cuota del record.
     */
    @Test
    void bindsStorageCanonicalConstructorWhenCompatibilityConstructorAlsoExists() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("download-worker.storage.endpoint", "http://minio:9000"),
                Map.entry("download-worker.storage.access-key", "worker"),
                Map.entry("download-worker.storage.secret-key", "worker-secret"),
                Map.entry("download-worker.storage.bucket", "zips"),
                Map.entry("download-worker.storage.presigned-url-ttl", "5m"),
                Map.entry("download-worker.storage.quota", "120GB")));

        StorageProperties properties = new Binder(source)
                .bind("download-worker.storage", Bindable.of(StorageProperties.class))
                .orElseThrow(() -> new AssertionError("StorageProperties was not bound"));

        assertThat(properties.endpoint()).isEqualTo("http://minio:9000");
        assertThat(properties.accessKey()).isEqualTo("worker");
        assertThat(properties.bucket()).isEqualTo("zips");
        assertThat(properties.presignedUrlTtl()).hasMinutes(5);
        assertThat(properties.quota().toGigabytes()).isEqualTo(120);
    }

    /**
     * Enlaza todas las propiedades de descarga y comprueba concurrencias, nivel ZIP, margen de
     * disco y tamaño multipart explícitos.
     */
    @Test
    void bindsCanonicalConstructorWhenCompatibilityConstructorAlsoExists() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("download-worker.download.max-items", "100"),
                Map.entry("download-worker.download.max-file-size", "4GB"),
                Map.entry("download-worker.download.max-redirects", "5"),
                Map.entry("download-worker.download.connect-timeout", "10s"),
                Map.entry("download-worker.download.request-timeout", "15m"),
                Map.entry("download-worker.download.per-job-concurrency", "2"),
                Map.entry("download-worker.download.packaging-concurrency", "2"),
                Map.entry("download-worker.download.zip-level", "0"),
                Map.entry("download-worker.download.min-free-space", "8GB"),
                Map.entry("download-worker.download.multipart-part-size", "16MB"),
                Map.entry("download-worker.download.inbox-lease", "30m"),
                Map.entry("download-worker.download.temp-directory", "/tmp/batch-downloader")));

        DownloadProperties properties = new Binder(source)
                .bind("download-worker.download", Bindable.of(DownloadProperties.class))
                .orElseThrow(() -> new AssertionError("DownloadProperties was not bound"));

        assertThat(properties.perJobConcurrency()).isEqualTo(2);
        assertThat(properties.packagingConcurrency()).isEqualTo(2);
        assertThat(properties.zipLevel()).isZero();
        assertThat(properties.minFreeSpace().toGigabytes()).isEqualTo(8);
        assertThat(properties.multipartPartSize().toMegabytes()).isEqualTo(16);
    }
}
