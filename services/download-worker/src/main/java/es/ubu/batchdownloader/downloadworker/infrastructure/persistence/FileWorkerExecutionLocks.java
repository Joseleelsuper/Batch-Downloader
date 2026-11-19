package es.ubu.batchdownloader.downloadworker.infrastructure.persistence;

import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.ports.WorkerExecutionLocks;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Bloqueos del núcleo sobre un único fichero del volumen Docker local compartido.
 * Una caída libera sus regiones sin esperar un lease SQL. El fichero no se borra:
 * recrearlo permitiría bloquear inodos distintos. No sirve para volúmenes remotos.
 */
public final class FileWorkerExecutionLocks implements WorkerExecutionLocks, AutoCloseable {
    private static final long JOB_REGIONS = 1L << 48;
    private final FileChannel channel;
    private final int packagingSlots;

    public FileWorkerExecutionLocks(Path directory, int packagingSlots) {
        if (packagingSlots < 1) throw new IllegalArgumentException("invalid_packaging_slots");
        this.packagingSlots = packagingSlots;
        try {
            Files.createDirectories(directory);
            // Un único canal por proceso: cerrar otro canal del mismo fichero puede liberar sus locks.
            channel = FileChannel.open(directory.resolve(".worker-execution.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            throw new InfrastructureException("worker_lock_open_failed", exception);
        }
    }

    @Override
    public Lease tryJob(UUID jobId) {
        long region = (jobId.getMostSignificantBits() ^ jobId.getLeastSignificantBits()) & (JOB_REGIONS - 1);
        return acquire(region);
    }

    @Override
    public Lease tryPackaging() {
        for (int slot = 0; slot < packagingSlots; slot++) {
            Lease lease = acquire(JOB_REGIONS + slot);
            if (lease != null) return lease;
        }
        return null;
    }

    @Override
    public boolean active(UUID jobId) {
        try (Lease lease = tryJob(jobId)) { return lease == null; }
    }

    private Lease acquire(long region) {
        try {
            FileLock lock = channel.tryLock(region, 1, false);
            return lock == null ? null : () -> {
                try { lock.release(); }
                catch (IOException exception) {
                    throw new InfrastructureException("worker_lock_release_failed", exception);
                }
            };
        } catch (OverlappingFileLockException busy) {
            return null;
        } catch (IOException exception) {
            throw new InfrastructureException("worker_lock_unavailable", exception);
        }
    }

    @Override
    public void close() throws IOException { channel.close(); }
}
