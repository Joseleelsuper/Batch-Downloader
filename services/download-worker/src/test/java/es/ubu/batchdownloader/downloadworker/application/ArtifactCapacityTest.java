package es.ubu.batchdownloader.downloadworker.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.config.StorageProperties;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** Verifica que almacenado y reservado participan en la misma cuota lógica. */
class ArtifactCapacityTest {
    @Test
    void rejectsAdmissionWhenStoredAndInflightBytesLeaveNoSafeJobReservation() {
        long megabyte = DataSize.ofMegabytes(1).toBytes();
        ArtifactStore store = new ArtifactStore() {
            @Override
            public void put(String key, Path source, String contentType) {}

            @Override
            public long usageBytes() {
                return 10 * megabyte;
            }
        };
        DownloadProperties downloads = new DownloadProperties(
                10,
                DataSize.ofMegabytes(10),
                DataSize.ofMegabytes(20),
                3,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                2,
                Duration.ofMinutes(5),
                "/tmp");
        StorageProperties storage = new StorageProperties(
                "http://minio", "key", "secret", "zips", Duration.ofHours(6),
                DataSize.ofMegabytes(40));
        ArtifactCapacity capacity = new ArtifactCapacity(
                store, storage, downloads, new SimpleMeterRegistry());

        try (ArtifactCapacity.Lease ignored = capacity.reserve(15 * megabyte)) {
            assertThatThrownBy(capacity::requireAvailable)
                    .isInstanceOf(CapacityDeferredException.class)
                    .satisfies(exception -> org.assertj.core.api.Assertions.assertThat(
                            ((CapacityDeferredException) exception).reason())
                            .isEqualTo("artifact_quota_busy"));
        }
    }
}
