package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Reserva bajo un mismo cerrojo los bytes de descargas en vuelo y exige un margen libre en el
 * volumen temporal antes y después de materializar archivos.
 *
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadPipeline
 * @see es.ubu.batchdownloader.downloadworker.application.CapacityDeferredException
 * @since 0.1.0
 * @version 0.1.0
 * @category Capacidad y coordinación de descargas
 */
@Component
public final class TemporaryDiskCapacity {
    /** Espacio que nunca puede consumirse. */
    private final long minimumFreeBytes;
    /** Reserva defensiva para una fuente sin tamaño declarado. */
    private final long unknownDownloadBytes;
    /**
     * Bytes reservados para la métrica de capacidad.
     */
    private long reservedBytes;
    /** Ruta observada por las métricas de disco. */
    private final Path monitoredDirectory;

    /**
     * Obtiene margen libre, reserva defensiva y volumen temporal de la configuración del worker.
     *
     * @param properties Límites de almacenamiento o descarga de los que se obtiene la capacidad de
     *     este componente.
     */
    public TemporaryDiskCapacity(DownloadProperties properties) {
        this(
                properties.minFreeSpace().toBytes(),
                properties.maxTotalSize().toBytes(),
                Path.of(properties.tempDirectory()));
    }

    /**
     * Configura las reservas y registra métricas de espacio prometido, utilizable y mínimo libre.
     *
     * @param properties Límites de almacenamiento o descarga de los que se obtiene la capacidad de
     *     este componente.
     * @param registry Registro de ocupación, espera y resultados del worker.
     */
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

    /**
     * Permite verificar reservas con límites explícitos y el volumen del directorio actual.
     *
     * @param minimumFreeBytes Margen mínimo que debe continuar libre en el volumen temporal, en
     *     bytes.
     * @param unknownDownloadBytes Reserva defensiva en bytes cuando no se conoce el tamaño de
     *     descarga.
     */
    TemporaryDiskCapacity(long minimumFreeBytes, long unknownDownloadBytes) {
        this(minimumFreeBytes, unknownDownloadBytes, Path.of("."));
    }

    /**
     * Inicializa los límites y el directorio de observación sin reservar espacio todavía.
     *
     * @param minimumFreeBytes Margen mínimo que debe continuar libre en el volumen temporal, en
     *     bytes.
     * @param unknownDownloadBytes Reserva defensiva en bytes cuando no se conoce el tamaño de
     *     descarga.
     * @param monitoredDirectory Directorio cuyo volumen se consulta al publicar la métrica de
     *     espacio utilizable.
     */
    private TemporaryDiskCapacity(
            long minimumFreeBytes,
            long unknownDownloadBytes,
            Path monitoredDirectory) {
        this.minimumFreeBytes = minimumFreeBytes;
        this.unknownDownloadBytes = unknownDownloadBytes;
        this.monitoredDirectory = monitoredDirectory;
    }

    /**
     * Crea el directorio si falta y comprueba que caben las reservas activas y otra descarga de
     * tamaño desconocido sin consumir una reserva.
     *
     * @param directory Directorio del volumen donde se escribirán los temporales del trabajo.
     * @throws es.ubu.batchdownloader.downloadworker.application.CapacityDeferredException si falla
     *     el acceso al volumen o no puede conservarse el margen libre.
     */
    public synchronized void requireAvailable(Path directory) {
        try {
            Files.createDirectories(directory);
            requireAvailable(directory, unknownDownloadBytes);
        } catch (ArithmeticException | IOException exception) {
            throw busy(exception);
        }
    }

    /**
     * Comprueba el margen y añade atómicamente la reserva anunciada o defensiva de una descarga.
     *
     * @param directory Directorio del volumen donde se escribirán los temporales del trabajo.
     * @param declaredBytes Tamaño anunciado de la descarga en bytes; null usa la reserva defensiva
     *     y negativos se acotan a cero.
     * @return reserva que debe completarse al materializar el archivo o cerrarse al abortar.
     * @throws es.ubu.batchdownloader.downloadworker.application.CapacityDeferredException si no
     *     cabe la reserva o no puede comprobarse el volumen.
     */
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

    /**
     * Clasifica un fallo de reserva temporal como condición aplazable del trabajo.
     *
     * @param cause Fallo original conservado para diagnóstico y política de reintentos.
     * @return fallo con motivo temporary_storage_busy y causa original.
     */
    private static CapacityDeferredException busy(Exception cause) {
        return new CapacityDeferredException("temporary_storage_busy", cause);
    }

    /**
     * Suma margen mínimo, reservas en vuelo y bytes propuestos y los compara con el espacio
     * utilizable del volumen.
     *
     * @param directory Directorio del volumen donde se escribirán los temporales del trabajo.
     * @param additionalBytes Bytes adicionales que se comprueban junto a la ocupación y reservas
     *     existentes.
     * @throws java.io.IOException si no se puede consultar el volumen o queda menos espacio del
     *     requerido.
     * @throws ArithmeticException si desborda la suma de bytes requerida.
     */
    private void requireAvailable(Path directory, long additionalBytes) throws IOException {
        long required = Math.addExact(
                minimumFreeBytes,
                Math.addExact(reservedBytes, additionalBytes));
        if (Files.getFileStore(directory).getUsableSpace() < required) {
            throw new IOException("Insufficient temporary disk space");
        }
    }

    /**
     * Lee el contador protegido de espacio prometido a descargas en vuelo.
     *
     * @return bytes reservados para la métrica de capacidad.
     */
    private synchronized double reservedBytes() {
        return reservedBytes;
    }

    /**
     * Consulta el espacio utilizable del volumen supervisado, creando su directorio si es
     * necesario.
     *
     * @return bytes utilizables; cero si falla la consulta de E/S.
     */
    private double usableBytes() {
        try {
            Files.createDirectories(monitoredDirectory);
            return Files.getFileStore(monitoredDirectory).getUsableSpace();
        } catch (IOException exception) {
            return 0;
        }
    }

    /**
     * Conserva la promesa de bytes de una descarga hasta que el archivo está materializado o se
     * aborta la transferencia.
     *
     * @since 0.1.0
     * @version 0.1.0
     * @category Capacidad y coordinación de descargas
     */
    public final class Lease implements AutoCloseable {
        /** Bytes prometidos por esta descarga. */
        private final long bytes;
        /** Disco en el que se materializa el temporal. */
        private final Path directory;
        /** Impide descontar dos veces. */
        private boolean closed;

        /**
         * Asocia bytes ya reservados al volumen que se volverá a comprobar al completar la
         * descarga.
         *
         * @param bytes Cantidad de bytes que se reserva, contabiliza o consume según la operación.
         * @param directory Directorio del volumen donde se escribirán los temporales del trabajo.
         */
        private Lease(long bytes, Path directory) {
            this.bytes = bytes;
            this.directory = directory;
        }

        /**
         * Retira la promesa porque el archivo ya ocupa espacio real y comprueba que sigue habiendo
         * margen para las demás reservas.
         *
         * @throws es.ubu.batchdownloader.downloadworker.application.CapacityDeferredException si el
         *     espacio restante ya no cubre el margen y las demás reservas, o no puede comprobarse.
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

        /**
         * Libera la promesa pendiente al salir del ámbito de la descarga; no elimina el archivo
         * local.
         */
        @Override
        public void close() {
            synchronized (TemporaryDiskCapacity.this) {
                release();
            }
        }

        /**
         * Descuenta los bytes reservados una sola vez; el llamador mantiene el cerrojo del
         * componente.
         */
        private void release() {
            if (!closed) {
                closed = true;
                reservedBytes = Math.max(0, reservedBytes - bytes);
            }
        }
    }
}
