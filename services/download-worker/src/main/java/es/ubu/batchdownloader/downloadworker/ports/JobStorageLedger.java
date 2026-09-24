package es.ubu.batchdownloader.downloadworker.ports;

import java.util.UUID;

/** Reserva duradera y fencing del intento en Core; ninguna escritura precede a su autorización. */
@FunctionalInterface
public interface JobStorageLedger {
    State update(UUID jobId, UUID attemptId, String action, long bytes);

    record State(boolean allowed, long reservedBytes, boolean cancelled, long budgetBytes) {}
}
