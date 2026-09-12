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

/**
 * Comprueba que la admisión considera conjuntamente ocupación persistida y reservas de ZIP en
 * vuelo.
 *
 * @see es.ubu.batchdownloader.downloadworker.application.ArtifactCapacity
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de procesamiento y capacidad
 */
class ArtifactCapacityTest {
    /**
     * Con diez MiB persistidos y quince reservados sobre una cuota de cuarenta, comprueba que se
     * aplaza otro trabajo cuya reserva ya no cabe.
     */
    @Test
    void rejectsAdmissionWhenStoredAndInflightBytesLeaveNoSafeJobReservation() {
        long megabyte = DataSize.ofMegabytes(1).toBytes();
        ArtifactStore store = new ArtifactStore() {
            /**
             * No almacena contenido porque este doble solo participa en la consulta de cuota.
             *
             * @param key Clave que este doble no utiliza porque solo simula ocupación.
             * @param source Archivo local cuyos bytes se capturan para inspeccionar el resultado.
             * @param contentType Metadato del puerto que el doble en memoria no necesita conservar.
             */
            @Override
            public void put(String key, Path source, String contentType) {}

            /**
             * Simula una ocupación persistida constante para aislar el cálculo de reservas.
             *
             * @return diez MiB ya ocupados.
             */
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
