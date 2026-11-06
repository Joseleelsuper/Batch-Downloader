package es.ubu.batchdownloader.downloadworker.ports;

import java.util.UUID;

/** Exclusión entre procesos del mismo servidor y cupo global de empaquetado. */
public interface WorkerExecutionLocks {
    /** Devuelve null si otra ejecución o limpieza conserva el trabajo. */
    Lease tryJob(UUID jobId);

    /** Devuelve null cuando todas las plazas de empaquetado están ocupadas. */
    Lease tryPackaging();

    /** Consulta conservadora: una región ocupada nunca se presenta como libre. */
    boolean active(UUID jobId);

    interface Lease extends AutoCloseable {
        @Override void close();
    }
}
