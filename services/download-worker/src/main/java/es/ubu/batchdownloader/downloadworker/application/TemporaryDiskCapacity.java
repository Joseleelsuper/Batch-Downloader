package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Reserva de forma atómica el espacio declarado de los temporales en vuelo. */
@Component
public final class TemporaryDiskCapacity {
    /** Espacio que nunca puede consumirse. */
    private final long minimumFreeBytes;
    /** Reserva defensiva para una fuente sin tamaño declarado. */
    private final long unknownDownloadBytes;
    /** Bytes prometidos por descargas que todavía están escribiéndose. */
    private long reservedBytes;
    /** Ruta observada por las métricas de disco. */
    private final Path monitoredDirectory;

    /** Inicializa la reserva a partir de los límites del worker. */
    public TemporaryDiskCapacity(DownloadProperties properties) {
        this(
                properties.minFreeSpace().toBytes(),
                properties.maxFileSize().toBytes(),
                Path.of(properties.tempDirectory()));
    }

    /** Inicializa además los medidores Prometheus del único SSD. */
    @Autowired
    public TemporaryDiskCapacity(DownloadProperties properties, MeterRegistry registry) {
        this(properties);
        registry.gauge(
                "download_worker_disk_reserved_bytes",
                this,
                TemporaryDiskCapacity::reservedBytes);
        registry.gauge(
                "download_worker_disk_usable_bytes",
                this,
                TemporaryDiskCapacity::usableBytes);
        registry.gauge(
                "download_worker_disk_minimum_free_bytes",
                this,
                capacity -> capacity.minimumFreeBytes);
    }

    /** Constructor acotado para pruebas unitarias sin depender del tamaño del host. */
    TemporaryDiskCapacity(long minimumFreeBytes, long unknownDownloadBytes) {
        this(minimumFreeBytes, unknownDownloadBytes, Path.of("."));
    }

    /** Inicializador común de los contadores. */
    private TemporaryDiskCapacity(
            long minimumFreeBytes,
            long unknownDownloadBytes,
            Path monitoredDirectory) {
        this.minimumFreeBytes = minimumFreeBytes;
        this.unknownDownloadBytes = unknownDownloadBytes;
        this.monitoredDirectory = monitoredDirectory;
    }

    /**
     * Comprueba la reserva mínima y todas las promesas activas sin consumir una plaza.
     * La usa la admisión HTTP de Core antes de crear un trabajo.
     *
     * @param directory Directorio temporal del worker.
     */
    public synchronized void requireAvailable(Path directory) {
        try {
            Files.createDirectories(directory);
            requireAvailable(directory, 0L);
        } catch (ArithmeticException | IOException exception) {
            throw busy(exception);
        }
    }

    /** Reserva el tamaño declarado o el máximo defensivo si se desconoce. */
    public synchronized Lease reserve(Path directory, Long declaredBytes) {
        long bytes = declaredBytes == null
                ? unknownDownloadBytes
                : Math.max(0, declaredBytes);
        try {
            requireAvailable(directory, bytes);
            reservedBytes = Math.addExact(reservedBytes, bytes);
            return new Lease(bytes, directory);
        } catch (ArithmeticException | IOException exception) {
            throw busy(exception);
        }
    }

    /** Construye el fallo reintentable que mantiene el mensaje en RabbitMQ. */
    private static InfrastructureException busy(Exception cause) {
        return new InfrastructureException("storage_busy", cause);
    }

    /** Verifica una reserva adicional manteniendo el cerrojo del contador. */
    private void requireAvailable(Path directory, long additionalBytes) throws IOException {
        long required = Math.addExact(
                minimumFreeBytes,
                Math.addExact(reservedBytes, additionalBytes));
        if (Files.getFileStore(directory).getUsableSpace() < required) {
            throw new IOException("Insufficient temporary disk space");
        }
    }

    /** @return Bytes prometidos por descargas todavía en vuelo. */
    private synchronized double reservedBytes() {
        return reservedBytes;
    }

    /** @return Espacio utilizable del volumen temporal, o cero si no puede consultarse. */
    private double usableBytes() {
        try {
            Files.createDirectories(monitoredDirectory);
            return Files.getFileStore(monitoredDirectory).getUsableSpace();
        } catch (IOException exception) {
            return 0;
        }
    }

    /** Reserva liberable de una descarga. */
    public final class Lease implements AutoCloseable {
        /** Bytes prometidos por esta descarga. */
        private final long bytes;
        /** Disco en el que se materializa el temporal. */
        private final Path directory;
        /** Impide descontar dos veces. */
        private boolean closed;

        /** Inicializa una reserva activa. */
        private Lease(long bytes, Path directory) {
            this.bytes = bytes;
            this.directory = directory;
        }

        /**
         * Convierte la reserva en espacio ya reflejado por el sistema de archivos y verifica
         * que las demás promesas siguen dejando el margen mínimo.
         */
        public void completed() {
            synchronized (TemporaryDiskCapacity.this) {
                release();
                try {
                    long required = Math.addExact(minimumFreeBytes, reservedBytes);
                    if (Files.getFileStore(directory).getUsableSpace() < required) {
                        throw busy(new IOException("Temporary disk reserve exhausted"));
                    }
                } catch (ArithmeticException | IOException exception) {
                    throw busy(exception);
                }
            }
        }

        /** Libera una reserva que no llegó a convertirse en temporal. */
        @Override
        public void close() {
            synchronized (TemporaryDiskCapacity.this) {
                release();
            }
        }

        /** Descuenta la reserva una sola vez. */
        private void release() {
            if (!closed) {
                closed = true;
                reservedBytes = Math.max(0, reservedBytes - bytes);
            }
        }
    }
}
