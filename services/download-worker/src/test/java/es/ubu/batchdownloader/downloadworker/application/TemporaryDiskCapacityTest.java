package es.ubu.batchdownloader.downloadworker.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Comprueba que una reserva imposible de sostener se clasifica como capacidad temporalmente
 * ocupada.
 *
 * @see es.ubu.batchdownloader.downloadworker.application.TemporaryDiskCapacity
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de procesamiento y capacidad
 */
class TemporaryDiskCapacityTest {
    @TempDir Path temporary;

    /**
     * Exige un margen libre de Long.MAX_VALUE y comprueba que reservar otro byte propaga
     * storage_busy.
     */
    @Test
    void rejectsAReservationThatCannotLeaveTheMinimumFreeSpace() {
        TemporaryDiskCapacity capacity = new TemporaryDiskCapacity(Long.MAX_VALUE, 1024);

        assertThatThrownBy(() -> capacity.reserve(temporary, 1L))
                .isInstanceOf(InfrastructureException.class)
                .hasMessage("storage_busy");
    }
}
