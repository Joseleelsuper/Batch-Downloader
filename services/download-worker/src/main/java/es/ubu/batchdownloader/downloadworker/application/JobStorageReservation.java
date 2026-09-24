package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.ports.JobStorageLedger;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/** Contabiliza el pico antes de escribir; las estimaciones nunca autorizan bytes sin reservar. */
public final class JobStorageReservation {
    private final JobStorageLedger ledger;
    private final UUID jobId;
    private final UUID attemptId;
    private final long limit;
    private final java.util.function.LongConsumer diskGuard;
    private long reserved;
    private long written;
    private long required;
    private RuntimeException failure;
    private long checkedAt = System.nanoTime();

    public JobStorageReservation(JobStorageLedger ledger, UUID jobId, UUID attemptId,
            JobStorageLedger.State state) {
        this(ledger, jobId, attemptId, state, ignored -> {});
    }

    public JobStorageReservation(JobStorageLedger ledger, UUID jobId, UUID attemptId,
            JobStorageLedger.State state, java.util.function.LongConsumer diskGuard) {
        this.ledger = ledger;
        this.jobId = jobId;
        this.attemptId = attemptId;
        this.reserved = state.reservedBytes();
        this.limit = state.budgetBytes();
        this.diskGuard = diskGuard;
    }

    public synchronized void consume(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative bytes");
        if (failure != null) throw failure;
        try { diskGuard.accept(bytes); }
        catch (RuntimeException exception) { failure = exception; throw exception; }
        long next;
        try { next = Math.addExact(written, bytes); }
        catch (ArithmeticException overflow) { throw new DownloadRejectedException("storage_budget_exceeded"); }
        require(next);
        written = next;
    }

    public synchronized void require(long bytes) {
        if (failure != null) throw failure;
        if (bytes > limit) {
            failure = new DownloadRejectedException("storage_budget_exceeded");
            throw failure;
        }
        if (bytes > reserved) {
            required = Math.min(limit, Math.max(bytes, reserved > limit / 2 ? limit : reserved * 2));
            JobStorageLedger.State state = ledger.update(jobId, attemptId, "RESERVE", required);
            check(state);
            if (!state.allowed() || state.reservedBytes() < required) {
                failure = new CapacityDeferredException("storage_budget_wait", null);
                throw failure;
            }
            reserved = state.reservedBytes();
        }
        heartbeat();
    }

    public synchronized void heartbeat() {
        if (System.nanoTime() - checkedAt < 10_000_000_000L) return;
        check(ledger.update(jobId, attemptId, "HEARTBEAT", written));
        checkedAt = System.nanoTime();
    }

    private void check(JobStorageLedger.State state) {
        if (state.cancelled()) {
            failure = new CancellationException("download_job_cancelled");
            throw failure;
        }
    }

    public synchronized long requiredBytes() { return Math.max(required, reserved); }

    public OutputStream guard(OutputStream output) {
        return new FilterOutputStream(output) {
            @Override public void write(int value) throws IOException {
                consume(1);
                out.write(value);
            }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                consume(length);
                out.write(bytes, offset, length);
            }
        };
    }
}
