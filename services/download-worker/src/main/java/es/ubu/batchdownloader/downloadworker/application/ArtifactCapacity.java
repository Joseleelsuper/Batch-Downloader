package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.config.StorageProperties;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Controla conjuntamente bytes persistidos y reservas de ZIP en vuelo para no admitir trabajo que
 * exceda la cuota del almacén de objetos.
 *
 * @see es.ubu.batchdownloader.downloadworker.ports.ArtifactStore
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @since 0.1.0
 * @version 0.1.0
 * @category Capacidad y coordinación de descargas
 */
@Component
public final class ArtifactCapacity {
    private final ArtifactStore store;
    private final long quotaBytes;
    private final long admissionReserveBytes;
    private long reservedBytes;
    private long observedStoredBytes;

    /**
     * Calcula la reserva defensiva por trabajo y registra métricas de uso y cuota sin consultar la
     * red durante la construcción.
     *
     * @param store Almacén que informa del espacio ya persistido para combinarlo con reservas en
     *     vuelo.
     * @param properties Límites de almacenamiento o descarga de los que se obtiene la capacidad de
     *     este componente.
     * @param downloads Límite total del trabajo usado para reservar su ZIP con margen de cabeceras.
     * @param registry Registro de ocupación, espera y resultados del worker.
     */
    public ArtifactCapacity(
            ArtifactStore store,
            StorageProperties properties,
            DownloadProperties downloads,
            MeterRegistry registry) {
        this.store = store;
        this.quotaBytes = properties.quota().toBytes();
        long maximum = downloads.maxTotalSize().toBytes();
        this.admissionReserveBytes = Math.min(
                quotaBytes, maximum + Math.max(1024L * 1024, maximum / 100));
        registry.gauge("download_worker_artifact_reserved_bytes", this, value -> value.reserved());
        registry.gauge("download_worker_artifact_stored_bytes", this, value -> value.observed());
        registry.gauge("download_worker_artifact_quota_bytes", this, value -> value.quotaBytes);
    }

    /**
     * Comprueba que cabe un trabajo máximo con margen de empaquetado, sin añadir una reserva
     * permanente.
     *
     * @throws es.ubu.batchdownloader.downloadworker.application.CapacityDeferredException si no hay
     *     cuota suficiente o no se puede conocer la ocupación.
     */
    public synchronized void requireAvailable() {
        reserveInternal(admissionReserveBytes);
    }

    /**
     * Consulta ocupación y añade atómicamente una reserva no negativa al conjunto de bytes en
     * vuelo.
     *
     * @param estimatedBytes Tamaño estimado del objeto que se reserva, en bytes; negativos se
     *     normalizan a cero.
     * @return reserva que debe cerrarse tras persistir o compensar el objeto.
     * @throws es.ubu.batchdownloader.downloadworker.application.CapacityDeferredException si el uso
     *     más las reservas supera la cuota o no puede comprobarse.
     */
    public synchronized Lease reserve(long estimatedBytes) {
        long bytes = Math.max(0, estimatedBytes);
        reserveInternal(bytes);
        reservedBytes = Math.addExact(reservedBytes, bytes);
        return new Lease(bytes);
    }

    /**
     * Refresca el espacio persistido y comprueba la cuota con las reservas actuales y la nueva
     * promesa; debe ejecutarse bajo el cerrojo del componente.
     *
     * @param additionalBytes Bytes adicionales que se comprueban junto a la ocupación y reservas
     *     existentes.
     * @throws es.ubu.batchdownloader.downloadworker.application.CapacityDeferredException si falta
     *     espacio, falla su consulta o desborda el cálculo de ocupación.
     */
    private void reserveInternal(long additionalBytes) {
        try {
            observedStoredBytes = store.usageBytes();
            long promised = Math.addExact(reservedBytes, additionalBytes);
            if (Math.addExact(observedStoredBytes, promised) > quotaBytes) {
                throw new CapacityDeferredException(
                        "artifact_quota_busy", new IllegalStateException("MinIO quota exhausted"));
            }
        } catch (CapacityDeferredException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new CapacityDeferredException("artifact_capacity_unknown", exception);
        }
    }

    /**
     * Lee bajo el cerrojo el tamaño prometido por los trabajos todavía en vuelo.
     *
     * @return bytes reservados expresados como valor de métrica.
     */
    private synchronized double reserved() {
        return reservedBytes;
    }

    /**
     * Lee bajo el cerrojo la última ocupación que se pudo confirmar en el almacén.
     *
     * @return bytes persistidos observados, sin incluir reservas.
     */
    private synchronized double observed() {
        return observedStoredBytes;
    }

    /**
     * Actualiza periódicamente la métrica de ocupación; si falla la consulta conserva el último
     * valor y deja que la próxima admisión vuelva a comprobar capacidad.
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    void refreshUsage() {
        try {
            long current = store.usageBytes();
            synchronized (this) {
                observedStoredBytes = current;
            }
        } catch (RuntimeException ignored) {
            // El endpoint de capacidad seguirá fallando cerrado; la métrica conserva el
            // último valor confirmado en vez de publicar un cero engañoso.
        }
    }

    /**
     * Representa una promesa de espacio que se retira del contador una sola vez al cerrar la
     * reserva.
     *
     * @since 0.1.0
     * @version 0.1.0
     * @category Capacidad y coordinación de descargas
     */
    public final class Lease implements AutoCloseable {
        private final long bytes;
        private boolean closed;

        /**
         * Conserva los bytes que ya se añadieron al contador de reservas en vuelo.
         *
         * @param bytes Cantidad de bytes que se reserva, contabiliza o consume según la operación.
         */
        private Lease(long bytes) {
            this.bytes = bytes;
        }

        /**
         * Descuenta la reserva una sola vez bajo el cerrojo del componente, sin borrar objetos
         * persistidos.
         */
        @Override
        public void close() {
            synchronized (ArtifactCapacity.this) {
                if (!closed) {
                    closed = true;
                    reservedBytes = Math.max(0, reservedBytes - bytes);
                }
            }
        }
    }
}
