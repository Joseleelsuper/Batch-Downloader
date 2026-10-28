package es.ubu.batchdownloader.downloadworker.application;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Suma atómicamente los bytes transferidos por todas las descargas del trabajo para aplicar un
 * límite total compartido.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadPipeline
 * @since 0.1.0
 * @version 0.1.0
 * @category Capacidad y coordinación de descargas
 */
public final class DownloadBudget {
    /**
     * Estado {@code maxTotalBytes} mantenido por {@code DownloadBudget}.
     */
    private final long maxTotalBytes;
    /**
     * Bytes admitidos que todavía ocupan espacio.
     */
    private final AtomicLong consumedBytes = new AtomicLong();
    private final java.util.function.LongConsumer beforeWrite;
    private final java.util.function.LongConsumer afterDelete;

    /**
     * Inicializa el presupuesto compartido con consumo cero y exige un límite positivo.
     *
     * @param maxTotalBytes Máximo de bytes transferidos por el conjunto del trabajo; debe ser
     *     positivo.
     * @throws IllegalArgumentException si el máximo total es cero o negativo.
     */
    public DownloadBudget(long maxTotalBytes) {
        this(maxTotalBytes, ignored -> {});
    }

    public DownloadBudget(long maxTotalBytes, java.util.function.LongConsumer beforeWrite) {
        this(maxTotalBytes, beforeWrite, ignored -> {});
    }

    public DownloadBudget(long maxTotalBytes, java.util.function.LongConsumer beforeWrite,
            java.util.function.LongConsumer afterDelete) {
        if (maxTotalBytes <= 0) {
            throw new IllegalArgumentException("maxTotalBytes must be positive");
        }
        this.maxTotalBytes = maxTotalBytes;
        this.beforeWrite = beforeWrite;
        this.afterDelete = afterDelete;
    }

    /**
     * Admite los bytes antes de escribirlos; un rechazo conserva el consumo anterior.
     *
     * @param bytes Cantidad de bytes que se reserva, contabiliza o consume según la operación.
     * @throws IllegalArgumentException si la cantidad es negativa.
     * @throws es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException si el
     *     total acumulado excede el máximo permitido.
     */
    public void consume(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must not be negative");
        }
        consumedBytes.updateAndGet(current -> {
            if (bytes > maxTotalBytes - current) {
                throw new DownloadRejectedException("total_size_limit_exceeded");
            }
            return current + bytes;
        });
        try {
            beforeWrite.accept(bytes);
        } catch (RuntimeException exception) {
            consumedBytes.addAndGet(-bytes);
            throw exception;
        }
    }

    /** Devuelve únicamente bytes cuyo archivo parcial ya se ha eliminado. */
    public void release(long bytes) {
        if (bytes < 0 || bytes > consumedBytes.get()) throw new IllegalArgumentException("Invalid released bytes");
        afterDelete.accept(bytes);
        consumedBytes.addAndGet(-bytes);
    }

    /**
     * Consulta el consumo total acumulado por todas las transferencias del trabajo.
     *
     * @return bytes admitidos pendientes de eliminar.
     */
    public long consumedBytes() {
        return consumedBytes.get();
    }
}
