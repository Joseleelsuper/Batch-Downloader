package es.ubu.batchdownloader.downloadworker.application;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Implementa el componente {@code DownloadBudget}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public final class DownloadBudget {
    /**
     * Estado {@code maxTotalBytes} mantenido por {@code DownloadBudget}.
     */
    private final long maxTotalBytes;
    /**
     * Estado {@code consumedBytes} mantenido por {@code DownloadBudget}.
     */
    private final AtomicLong consumedBytes = new AtomicLong();

    /**
     * Inicializa una instancia de {@code DownloadBudget}.
     *
     * @param maxTotalBytes Valor de {@code maxTotalBytes} utilizado por la operación.
     * @throws IllegalArgumentException Si los argumentos recibidos no cumplen las restricciones
     *     requeridas.
     */
    public DownloadBudget(long maxTotalBytes) {
        if (maxTotalBytes <= 0) {
            throw new IllegalArgumentException("maxTotalBytes must be positive");
        }
        this.maxTotalBytes = maxTotalBytes;
    }

    /**
     * Ejecuta la operación {@code consume}.
     *
     * @param bytes Valor de {@code bytes} utilizado por la operación.
     * @throws IllegalArgumentException Si los argumentos recibidos no cumplen las restricciones
     *     requeridas.
     * @throws DownloadRejectedException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
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
     * Ejecuta la operación {@code consumedBytes}.
     *
     * @return Resultado producido por {@code consumedBytes}.
     */
    public long consumedBytes() {
        return consumedBytes.get();
    }
}
