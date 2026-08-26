package es.ubu.batchdownloader.downloadworker.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifica el rechazo reintentable cuando no puede preservarse la reserva del SSD. */
class TemporaryDiskCapacityTest {
    @TempDir Path temporary;

    @Test
    void rejectsAReservationThatCannotLeaveTheMinimumFreeSpace() {
        TemporaryDiskCapacity capacity = new TemporaryDiskCapacity(Long.MAX_VALUE, 1024);

        assertThatThrownBy(() -> capacity.reserve(temporary, 1L))
                .isInstanceOf(InfrastructureException.class)
                .hasMessage("storage_busy");
    }
}
