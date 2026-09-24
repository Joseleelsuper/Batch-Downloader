package es.ubu.batchdownloader.downloads.application;

import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import es.ubu.batchdownloader.downloads.domain.DownloadItemStatus;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica eventos del worker al estado persistido y prepara la difusión asociada a cada transición.
 *
 * @see es.ubu.batchdownloader.downloads.domain.DownloadJob
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobNotifications
 * @see es.ubu.batchdownloader.downloads.application.port.DownloadJobStore
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
@Service
public class DownloadJobEventHandler {
    /**
     * Persistencia de trabajos, elementos, contexto Linux y reservas de admisión.
     */
    private final DownloadJobStore jobs;
    /**
     * Reloj que determina cuotas, cambios de estado y vencimientos.
     */
    private final Clock clock;
    /**
     * Cuotas de admisión y duraciones de conservación y firma del ZIP.
     */
    private final DownloadLimits limits;
    /**
     * Coordinador de difusión después del commit.
     */
    private final DownloadJobNotifications notifications;
    private final DownloadStorageCoordinator storage;

    /**
     * Conecta persistencia, reloj, límite de retención y difusión para aplicar eventos del worker.
     *
     * @param jobs Persistencia de trabajos, elementos, contexto Linux y reservas de admisión.
     * @param clock Reloj que determina cuotas, cambios de estado y vencimientos.
     * @param limits Cuotas de admisión y duraciones de conservación y firma del ZIP.
     * @param notifications Coordinador de difusión después del commit.
     */
    public DownloadJobEventHandler(DownloadJobStore jobs, Clock clock, DownloadLimits limits, DownloadJobNotifications notifications, DownloadStorageCoordinator storage) {
        this.jobs = jobs;
        this.clock = clock;
        this.limits = limits;
        this.notifications = notifications;
        this.storage = storage;
    }

    /**
     * Actualiza el elemento mediante el puerto transaccional y difunde la vista guardada después
     * del commit.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @param itemId UUID de un elemento perteneciente al trabajo indicado.
     * @param status Estado del trabajo o elemento correspondiente al evento o proyección.
     * @param bytesDownloaded Bytes transferidos del instalador; el dominio conserva el máximo
     *     recibido.
     *
     * @param sha256 SHA-256 hexadecimal del contenido cuando se conoce; null si todavía no está
     *     disponible.
     *
     * @param errorCode Código seguro del fallo del elemento o null si no hay un fallo que
     *     comunicar.
     *
     * @throws es.ubu.batchdownloader.common.NotFoundException si no puede localizarse el trabajo
     *     del evento.
     */
    @Transactional
    public void applyProgress(
            UUID jobId,
            UUID itemId,
            DownloadItemStatus status,
            long bytesDownloaded,
            String sha256,
            String errorCode) {
        DownloadJob previous = requireJob(jobId);
        boolean advanced = !previous.status().terminal() && previous.items().stream()
                .filter(item -> item.id().equals(itemId) && !item.status().terminal())
                .anyMatch(item -> bytesDownloaded > item.bytesDownloaded()
                        || status.ordinal() > item.status().ordinal());
        DownloadJob job = jobs.applyProgress(
                        jobId,
                        itemId,
                        status,
                        bytesDownloaded,
                        sha256,
                        errorCode,
                        clock.instant())
                .orElseThrow(() -> new NotFoundException(
                        "download_job_not_found", "No existe el trabajo."));
        if (advanced) storage.processingProgress(jobId);
        notifications.notifyAfterSave(job);
    }

    /**
     * Guarda el resultado descargable limitando el vencimiento del worker a la retención
     * configurada y difunde el estado confirmado.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @param status Estado del trabajo o elemento correspondiente al evento o proyección.
     * @param objectKey Clave interna del ZIP en el almacén de objetos, nunca una URL firmada.
     * @param expiresAt Instante límite de disponibilidad del ZIP.
     * @throws es.ubu.batchdownloader.common.NotFoundException si el trabajo del evento no existe.
     */
    @Transactional
    public void applyReady(UUID jobId, DownloadJobStatus status, String objectKey, Instant expiresAt) {
        applyReadyInternal(jobId, status, objectKey, null, null, expiresAt);
    }

    /**
     * Guarda el resultado descargable limitando el vencimiento del worker a la retención
     * configurada y difunde el estado confirmado.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @param status Estado del trabajo o elemento correspondiente al evento o proyección.
     * @param objectKey Clave interna del ZIP en el almacén de objetos, nunca una URL firmada.
     * @param artifactSizeBytes Tamaño del ZIP en bytes o null en eventos sin metadatos de
     *     integridad.
     *
     * @param artifactSha256 SHA-256 hexadecimal del ZIP o null si el productor no lo proporciona.
     * @param expiresAt Instante límite de disponibilidad del ZIP.
     * @throws es.ubu.batchdownloader.common.NotFoundException si el trabajo del evento no existe.
     */
    @Transactional
    public void applyReady(
            UUID jobId,
            DownloadJobStatus status,
            String objectKey,
            Long artifactSizeBytes,
            String artifactSha256,
            Instant expiresAt) {
        applyReadyInternal(jobId, status, objectKey, artifactSizeBytes, artifactSha256, expiresAt);
    }

    private void applyReadyInternal(
            UUID jobId,
            DownloadJobStatus status,
            String objectKey,
            Long artifactSizeBytes,
            String artifactSha256,
            Instant expiresAt) {
        DownloadJob job = requireJob(jobId);
        Instant now = clock.instant();
        Instant maximumExpiry = now.plus(limits.zipRetention());
        Instant effectiveExpiry = expiresAt.isBefore(maximumExpiry) ? expiresAt : maximumExpiry;
        job.markReady(
                status, objectKey, artifactSizeBytes, artifactSha256, effectiveExpiry, now);
        DownloadJob saved = jobs.save(job);
        notifications.notifyAfterSave(saved);
    }

    /**
     * Reencola un trabajo no terminal con motivo y fecha de reintento y difunde su estado
     * confirmado.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @param waitReason Código seguro del motivo temporal de espera, o null cuando no hay
     *     aplazamiento.
     *
     * @param retryAt Instante previsto del siguiente intento por capacidad, o null cuando no
     *     corresponde.
     *
     * @throws es.ubu.batchdownloader.common.NotFoundException si el trabajo no existe.
     */
    @Transactional
    public void applyDeferred(UUID jobId, String waitReason, Instant retryAt) {
        DownloadJob job = requireJob(jobId);
        job.defer(waitReason, retryAt, clock.instant());
        notifications.notifyAfterSave(jobs.save(job));
    }

    /**
     * Registra el fallo global y solicita su difusión después del commit.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @param errorCode Código seguro del fallo del elemento o null si no hay un fallo que
     *     comunicar.
     *
     * @throws es.ubu.batchdownloader.common.NotFoundException si el trabajo no existe.
     */
    @Transactional
    public void applyFailed(UUID jobId, String errorCode) {
        DownloadJob job = requireJob(jobId);
        job.fail(errorCode, clock.instant());
        DownloadJob saved = jobs.save(job);
        notifications.notifyAfterSave(saved);
    }

    /**
     * Recupera el agregado destinatario de un evento del worker.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @return trabajo persistido.
     * @throws es.ubu.batchdownloader.common.NotFoundException si no se conoce el UUID recibido.
     */
    private DownloadJob requireJob(UUID jobId) {
        return jobs.findById(jobId)
                .orElseThrow(() -> new NotFoundException("download_job_not_found", "No existe el trabajo."));
    }

}
