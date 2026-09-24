package es.ubu.batchdownloader.downloads.application;

import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.ServiceUnavailableException;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner.RequestOwner;
import es.ubu.batchdownloader.downloads.application.port.DownloadEventPublisher;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import es.ubu.batchdownloader.downloads.application.port.ZipUriSigner;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobItem;
import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consulta, cancela y entrega trabajos tras comprobar su propietario y, para el ZIP, su
 * disponibilidad temporal.
 *
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobService
 * @see es.ubu.batchdownloader.downloads.application.port.DownloadJobStore
 * @see es.ubu.batchdownloader.downloads.application.port.ZipUriSigner
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
@Service
public class DownloadJobAccessService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadJobAccessService.class);
    private static final int MAX_METADATA_ITEMS = 100;
    /**
     * Persistencia de trabajos, elementos, contexto Linux y reservas de admisión.
     */
    private final DownloadJobStore jobs;
    /**
     * Puerto que firma la lectura temporal del objeto ZIP ya publicado.
     */
    private final ZipUriSigner zipUris;
    /**
     * Reloj que determina cuotas, cambios de estado y vencimientos.
     */
    private final Clock clock;
    /**
     * Cuotas de admisión y duraciones de conservación y firma del ZIP.
     */
    private final DownloadLimits limits;
    /**
     * Publicador de solicitudes durables mediante el outbox de la transacción actual.
     */
    private final DownloadEventPublisher events;
    /**
     * Coordinador de difusión después del commit.
     */
    private final DownloadJobNotifications notifications;
    private final DownloadStorageCoordinator storage;

    /**
     * Conecta persistencia, identidad temporal y notificaciones para las operaciones sobre trabajos
     * existentes.
     *
     * @param jobs Persistencia de trabajos, elementos, contexto Linux y reservas de admisión.
     * @param zipUris Puerto que firma la lectura temporal del objeto ZIP ya publicado.
     * @param clock Reloj que determina cuotas, cambios de estado y vencimientos.
     * @param limits Cuotas de admisión y duraciones de conservación y firma del ZIP.
     * @param events Publicador de solicitudes durables mediante el outbox de la transacción actual.
     * @param notifications Coordinador de difusión después del commit.
     */
    public DownloadJobAccessService(DownloadJobStore jobs, ZipUriSigner zipUris, Clock clock, DownloadLimits limits, DownloadEventPublisher events, DownloadJobNotifications notifications, DownloadStorageCoordinator storage) {
        this.jobs = jobs;
        this.zipUris = zipUris;
        this.clock = clock;
        this.limits = limits;
        this.events = events;
        this.notifications = notifications;
        this.storage = storage;
    }

    /**
     * Entrega al worker identidad y página oficial de elementos ya admitidos, sin exponer URLs de
     * descarga protegidas.
     *
     * @param itemId UUID de un elemento perteneciente al trabajo indicado.
     * @param appId UUID público de la aplicación del catálogo.
     * @param appName Nombre visible de la aplicación conservado en el momento de admisión.
     * @param officialPageUrl Página oficial para la alternativa manual; no es una URL de instalador
     *     resuelta.
     *
     * @see DownloadJobAccessService#itemMetadata(java.util.UUID, java.util.List)
     * @since 0.1.0
     * @version 0.1.0
     * @category Descargas
     */
    public record DownloadItemMetadata(
            UUID itemId,
            UUID appId,
            String appName,
            String officialPageUrl) {}

    /**
     * Consulta un trabajo accesible y recupera su contexto Linux persistido.
     *
     * @param owner Identidad autenticada o hashes del navegador que solicita acceso o creación.
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @return vista del trabajo con sus elementos y contexto.
     * @throws es.ubu.batchdownloader.common.NotFoundException si no existe o no pertenece al
     *     solicitante.
     */
    @Transactional(readOnly = true)
    public DownloadJobView get(RequestOwner owner, UUID jobId) {
        return storage.decorate(DownloadJobView.from(accessibleJob(owner, jobId)).withLinuxContext(jobs.linuxContext(jobId)));
    }

    /**
     * Resuelve los UUID solicitados únicamente dentro del trabajo indicado y conserva el orden de
     * entrada.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @param requestedItemIds Entre 1 y 100 UUID únicos, no nulos; el resultado conserva su orden.
     * @return metadatos del lote para resolución interna.
     * @throws es.ubu.batchdownloader.common.BadRequestException si la selección está vacía,
     *     contiene duplicados o nulos o supera cien elementos.
     *
     * @throws es.ubu.batchdownloader.common.NotFoundException si falta el trabajo o algún elemento
     *     no le pertenece.
     */
    @Transactional(readOnly = true)
    public List<DownloadItemMetadata> itemMetadata(UUID jobId, List<UUID> requestedItemIds) {
        if (requestedItemIds == null
                || requestedItemIds.isEmpty()
                || requestedItemIds.size() > MAX_METADATA_ITEMS
                || requestedItemIds.stream().anyMatch(Objects::isNull)
                || new LinkedHashSet<>(requestedItemIds).size() != requestedItemIds.size()) {
            throw new BadRequestException(
                    "invalid_download_item_ids",
                    "Indica entre 1 y " + MAX_METADATA_ITEMS + " identificadores de item únicos.");
        }
        DownloadJob job = requireJob(jobId);
        Map<UUID, DownloadJobItem> itemsById = job.items().stream()
                .collect(java.util.stream.Collectors.toMap(DownloadJobItem::id, item -> item));
        if (!itemsById.keySet().containsAll(requestedItemIds)) {
            throw new NotFoundException("download_job_not_found", "No existe el trabajo.");
        }
        return requestedItemIds.stream()
                .map(itemsById::get)
                .map(item -> new DownloadItemMetadata(
                        item.id(),
                        item.appId(),
                        item.appName(),
                        item.officialPageUrl()))
                .toList();
    }

    /**
     * Solicita una única cancelación y guarda su evento durable junto al cambio de estado; difunde
     * la vista tras el commit.
     *
     * @param owner Identidad autenticada o hashes del navegador que solicita acceso o creación.
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @return vista resultante, incluida una cancelación ya aplicada.
     * @throws es.ubu.batchdownloader.common.NotFoundException si falta el trabajo o pertenece a
     *     otro solicitante.
     */
    @Transactional
    public DownloadJobView cancel(RequestOwner owner, UUID jobId) {
        DownloadJob job = accessibleJob(owner, jobId);
        if (job.requestCancellation(clock.instant())) {
            jobs.save(job);
            events.cancellationRequested(job);
        }
        DownloadJobView view = storage.decorate(DownloadJobView.from(job));
        notifications.notifyAfterCommit(view);
        return view;
    }

    /**
     * Firma una lectura temporal solo cuando el trabajo tiene ZIP descargable y no ha vencido.
     *
     * @param owner Identidad autenticada o hashes del navegador que solicita acceso o creación.
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @return URI de lectura con la vigencia configurada.
     * @throws es.ubu.batchdownloader.common.NotFoundException si el trabajo no es accesible.
     * @throws es.ubu.batchdownloader.common.ConflictException si aún no hay ZIP disponible o ya
     *     venció.
     *
     * @throws es.ubu.batchdownloader.common.ServiceUnavailableException si el proveedor no puede
     *     firmar la lectura; permite reintentar tras cinco segundos.
     */
    public URI file(RequestOwner owner, UUID jobId) {
        DownloadJob job = accessibleJob(owner, jobId);
        if (!job.status().downloadable() || job.objectKey() == null || !job.expiresAt().isAfter(clock.instant())) {
            throw new ConflictException("download_not_ready", "El ZIP no está disponible.");
        }
        try {
            return zipUris.signGet(
                    job.objectKey(), "batch-downloader-" + job.id() + ".zip", limits.presignedUrlTtl());
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not sign download artifact for job {}", job.id(), exception);
            throw new ServiceUnavailableException(
                    "download_signing_unavailable",
                    "No se pudo preparar el enlace. Inténtalo de nuevo en unos segundos.",
                    5);
        }
    }

    /**
     * Carga el agregado requerido para una operación de consulta o entrega.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @return trabajo persistido.
     * @throws es.ubu.batchdownloader.common.NotFoundException si el UUID no existe.
     */
    private DownloadJob requireJob(UUID jobId) {
        return jobs.findById(jobId)
                .orElseThrow(() -> new NotFoundException("download_job_not_found", "No existe el trabajo."));
    }

    /**
     * Exige coincidencia de cuenta o hash de navegador antes de devolver el agregado.
     *
     * @param owner Identidad autenticada o hashes del navegador que solicita acceso o creación.
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @return trabajo del solicitante.
     * @throws es.ubu.batchdownloader.common.NotFoundException si no existe o no pertenece a la
     *     identidad recibida.
     */
    private DownloadJob accessibleJob(RequestOwner owner, UUID jobId) {
        DownloadJob job = requireJob(jobId);
        if (!owner.canAccess(job.ownerId(), job.anonymousOwnerHash())) {
            throw new NotFoundException("download_job_not_found", "No existe el trabajo.");
        }
        return job;
    }

}
