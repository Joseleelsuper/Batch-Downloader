package es.ubu.batchdownloader.downloadworker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ubu.batchdownloader.downloadworker.ports.JobStorageLedger;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class JobStorageReservationTest {
    @Test
    void deniesWriteBeforeBytesReachDiskAndPreservesRequestedGrowth() throws Exception {
        AtomicLong requested = new AtomicLong();
        JobStorageReservation reservation = reservation((job, attempt, action, bytes) -> {
            requested.set(bytes);
            return new JobStorageLedger.State(false, 100, false, 1000);
        });
        ByteArrayOutputStream disk = new ByteArrayOutputStream();
        var output = reservation.guard(disk);
        output.write(new byte[90]);

        assertThatThrownBy(() -> output.write(new byte[20])).isInstanceOf(CapacityDeferredException.class);
        assertThat(disk.size()).isEqualTo(90);
        assertThat(requested).hasValue(200);
        assertThat(reservation.requiredBytes()).isEqualTo(200);
        assertThatThrownBy(() -> output.write(1)).isInstanceOf(CapacityDeferredException.class);
    }

    @Test
    void grantsGrowthAtomicallyAndNeverWritesPastGlobalBudget() throws Exception {
        JobStorageReservation reservation = reservation((job, attempt, action, bytes) ->
                new JobStorageLedger.State(true, bytes, false, 1000));
        ByteArrayOutputStream disk = new ByteArrayOutputStream();
        var output = reservation.guard(disk);
        output.write(new byte[700]);
        output.write(new byte[300]);
        assertThatThrownBy(() -> output.write(1)).isInstanceOf(DownloadRejectedException.class)
                .hasMessage("storage_budget_exceeded");
        assertThat(disk.size()).isEqualTo(1000);
    }

    @Test
    void invalidatedAttemptStopsBeforeWriting() {
        JobStorageReservation reservation = reservation((job, attempt, action, bytes) ->
                new JobStorageLedger.State(false, 100, true, 1000));
        assertThatThrownBy(() -> reservation.consume(101))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void diskMarginStopsWriteEvenWithAnExistingReservation() {
        JobStorageReservation reservation = new JobStorageReservation((job, attempt, action, bytes) -> {
            throw new AssertionError("No new reservation should be needed");
        }, UUID.randomUUID(), UUID.randomUUID(), new JobStorageLedger.State(true, 100, false, 1000),
                bytes -> { throw new CapacityDeferredException("temporary_storage_busy", null); });
        ByteArrayOutputStream disk = new ByteArrayOutputStream();
        assertThatThrownBy(() -> reservation.guard(disk).write(1)).isInstanceOf(CapacityDeferredException.class);
        assertThat(disk.size()).isZero();
    }

    private JobStorageReservation reservation(JobStorageLedger ledger) {
        return new JobStorageReservation(ledger, UUID.randomUUID(), UUID.randomUUID(),
                new JobStorageLedger.State(true, 100, false, 1000));
    }
}
