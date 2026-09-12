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
     * Bytes contabilizados, incluidos los que causaron superar el límite.
     */
    private final AtomicLong consumedBytes = new AtomicLong();

    /**
     * Inicializa el presupuesto compartido con consumo cero y exige un límite positivo.
     *
     * @param maxTotalBytes Máximo de bytes transferidos por el conjunto del trabajo; debe ser
     *     positivo.
     * @throws IllegalArgumentException si el máximo total es cero o negativo.
     */
    public DownloadBudget(long maxTotalBytes) {
        if (maxTotalBytes <= 0) {
            throw new IllegalArgumentException("maxTotalBytes must be positive");
        }
        this.maxTotalBytes = maxTotalBytes;
    }

    /**
     * Añade los bytes transferidos al consumo acumulado y rechaza la descarga si supera el límite;
     * el consumo ya contabilizado no se revierte.
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
        long total = consumedBytes.addAndGet(bytes);
        if (total > maxTotalBytes) {
            throw new DownloadRejectedException("total_size_limit_exceeded");
        }
    }

    /**
     * Consulta el consumo total acumulado por todas las transferencias del trabajo.
     *
     * @return bytes contabilizados, incluidos los que causaron superar el límite.
     */
    public long consumedBytes() {
        return consumedBytes.get();
    }
}
