package es.ubu.batchdownloader.downloads.application;

import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.RateLimitException;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner.RequestOwner;
import es.ubu.batchdownloader.downloads.application.port.CatalogSourceLookup;
import es.ubu.batchdownloader.downloads.application.port.DownloadArtifactCleaner;
import es.ubu.batchdownloader.downloads.application.port.DownloadEventPublisher;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobNotifier;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import es.ubu.batchdownloader.downloads.application.port.ZipUriSigner;
import es.ubu.batchdownloader.downloads.domain.DownloadItemStatus;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobItem;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Coordina las operaciones de negocio de {@code DownloadJobService}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Service
public class DownloadJobService {
    /**
     * Constante que define {@code LOGGER}.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadJobService.class);
    /**
     * Constante que define {@code MAX_METADATA_ITEMS}.
     */
    private static final int MAX_METADATA_ITEMS = 100;
    /**
     * Estado {@code jobs} mantenido por {@code DownloadJobService}.
     */
    private final DownloadJobStore jobs;
    /**
     * Estado {@code sources} mantenido por {@code DownloadJobService}.
     */
    private final CatalogSourceLookup sources;
    /**
     * Estado {@code users} mantenido por {@code DownloadJobService}.
     */
    private final UserAccountStore users;
    /**
     * Estado {@code events} mantenido por {@code DownloadJobService}.
     */
    private final DownloadEventPublisher events;
    /**
     * Estado {@code notifier} mantenido por {@code DownloadJobService}.
     */
    private final DownloadJobNotifier notifier;
    /**
     * Estado {@code artifacts} mantenido por {@code DownloadJobService}.
     */
    private final DownloadArtifactCleaner artifacts;
    /**
     * Estado {@code zipUris} mantenido por {@code DownloadJobService}.
     */
    private final ZipUriSigner zipUris;
    /**
     * Estado {@code clock} mantenido por {@code DownloadJobService}.
     */
    private final Clock clock;
    /**
     * Estado {@code maxApps} mantenido por {@code DownloadJobService}.
     */
    private final int maxApps;
    /**
     * Estado {@code retention} mantenido por {@code DownloadJobService}.
     */
    private final Duration retention;
    /**
     * Estado {@code signedUrlTtl} mantenido por {@code DownloadJobService}.
     */
    private final Duration signedUrlTtl;
    /**
     * Estado {@code anonymousMaxActiveJobs} mantenido por {@code DownloadJobService}.
     */
    private final int anonymousMaxActiveJobs;
    /**
     * Estado {@code anonymousMaxCreatesPerHour} mantenido por {@code DownloadJobService}.
     */
    private final int anonymousMaxCreatesPerHour;
    /**
     * Estado {@code anonymousMaxCreatesPerIpHour} mantenido por {@code DownloadJobService}.
     */
    private final int anonymousMaxCreatesPerIpHour;

    /**
     * Inicializa una instancia de {@code DownloadJobService}.
     *
     * @param jobs Valor de {@code jobs} utilizado por la operación.
     * @param sources Colección de fuentes de descarga que debe procesarse.
     * @param users Valor de {@code users} utilizado por la operación.
     * @param events Valor de {@code events} utilizado por la operación.
     * @param notifier Valor de {@code notifier} utilizado por la operación.
     * @param artifacts Valor de {@code artifacts} utilizado por la operación.
     * @param zipUris Valor de {@code zipUris} utilizado por la operación.
     * @param clock Valor de {@code clock} utilizado por la operación.
     * @param maxApps Valor de {@code maxApps} utilizado por la operación.
     * @param retention Valor de {@code retention} utilizado por la operación.
     * @param signedUrlTtl Valor de {@code signedUrlTtl} utilizado por la operación.
     * @param anonymousMaxActiveJobs Valor de {@code anonymousMaxActiveJobs} utilizado por la
     *     operación.
     * @param anonymousMaxCreatesPerHour Valor de {@code anonymousMaxCreatesPerHour} utilizado por
     *     la operación.
     * @param anonymousMaxCreatesPerIpHour Valor de {@code anonymousMaxCreatesPerIpHour} utilizado
     *     por la operación.
     */
    public DownloadJobService(
            DownloadJobStore jobs,
            CatalogSourceLookup sources,
            UserAccountStore users,
            DownloadEventPublisher events,
            DownloadJobNotifier notifier,
            DownloadArtifactCleaner artifacts,
            ZipUriSigner zipUris,
            Clock clock,
            @Value("${app.download.max-apps}") int maxApps,
            @Value("${app.download.zip-retention}") Duration retention,
            @Value("${app.download.presigned-url-ttl}") Duration signedUrlTtl,
            @Value("${app.download.anonymous-max-active-jobs}") int anonymousMaxActiveJobs,
            @Value("${app.download.anonymous-max-creates-per-hour}") int anonymousMaxCreatesPerHour,
            @Value("${app.download.anonymous-max-creates-per-ip-hour}") int anonymousMaxCreatesPerIpHour) {
        this.jobs = jobs;
        this.sources = sources;
        this.users = users;
        this.events = events;
        this.notifier = notifier;
        this.artifacts = artifacts;
        this.zipUris = zipUris;
        this.clock = clock;
        this.maxApps = maxApps;
        this.retention = retention;
        this.signedUrlTtl = signedUrlTtl;
        this.anonymousMaxActiveJobs = anonymousMaxActiveJobs;
        this.anonymousMaxCreatesPerHour = anonymousMaxCreatesPerHour;
        this.anonymousMaxCreatesPerIpHour = anonymousMaxCreatesPerIpHour;
    }

    /**
     * Crea el recurso solicitado mediante {@code create}.
     *
     * @param owner Valor de {@code owner} utilizado por la operación.
     * @param requestedAppIds Colección de identificadores de {@code requestedApp}.
     * @param operatingSystems Valor de {@code operatingSystems} utilizado por la operación.
     * @param notifyWhenReady Valor de {@code notifyWhenReady} utilizado por la operación.
     * @return Resultado producido por {@code create}.
     * @throws BadRequestException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     * @throws ConflictException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    @Transactional
    public DownloadJobView create(
            RequestOwner owner,
            List<UUID> requestedAppIds,
            List<String> operatingSystems,
            boolean notifyWhenReady) {
        LinkedHashSet<UUID> appIds = new LinkedHashSet<>(requestedAppIds == null ? List.of() : requestedAppIds);
        appIds.remove(null);
        if (appIds.isEmpty() || appIds.size() > maxApps) {
            throw new BadRequestException("invalid_job_size", "Selecciona entre 1 y " + maxApps + " aplicaciones.");
        }
        Instant now = clock.instant();
        if (!owner.authenticated()) {
            enforceAnonymousLimits(owner, now);
        }
        Map<UUID, CatalogSourceLookup.VerifiedSource> selected =
                sources.findVerifiedSources(appIds, operatingSystems);
        List<DownloadJobItem> items = appIds.stream()
                .map(selected::get)
                .filter(Objects::nonNull)
                .map(source -> DownloadJobItem.queued(
                        source.appId(),
                        source.sourceRef(),
                        source.appName(),
                        source.officialPageUrl(),
                        now))
                .toList();
        int omittedCount = appIds.size() - items.size();
        if (items.isEmpty()) {
            throw new ConflictException(
                    "no_downloadable_apps", "Ninguna de las aplicaciones seleccionadas tiene una fuente verificable.");
        }
        DownloadJob job = jobs.save(DownloadJob.queue(
                owner.authenticated() ? owner.userId() : null,
                owner.authenticated() ? null : owner.requireAnonymousOwnerHash(),
                owner.authenticated() ? null : owner.anonymousIpHash(),
                items,
                appIds.size(),
                omittedCount,
                notifyWhenReady && owner.authenticated(),
                now,
                now.plus(retention)));
        events.jobRequested(job);
        return DownloadJobView.from(job);
    }

    /**
     * Obtiene el resultado solicitado mediante {@code get}.
     *
     * @param owner Valor de {@code owner} utilizado por la operación.
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @return Resultado producido por {@code get}.
     */
    @Transactional(readOnly = true)
    public DownloadJobView get(RequestOwner owner, UUID jobId) {
        return DownloadJobView.from(accessibleJob(owner, jobId));
    }

    /**
     * Ejecuta la operación {@code itemMetadata}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @param requestedItemIds Colección de identificadores de {@code requestedItem}.
     * @return Colección de elementos obtenidos por la operación.
     * @throws BadRequestException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     * @throws NotFoundException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
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
     * Indica si puede realizarse la operación mediante {@code cancel}.
     *
     * @param owner Valor de {@code owner} utilizado por la operación.
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @return Resultado producido por {@code cancel}.
     */
    @Transactional
    public DownloadJobView cancel(RequestOwner owner, UUID jobId) {
        DownloadJob job = accessibleJob(owner, jobId);
        if (job.requestCancellation(clock.instant())) {
            jobs.save(job);
            events.cancellationRequested(job);
        }
        DownloadJobView view = DownloadJobView.from(job);
        notifyAfterCommit(view);
        return view;
    }

    /**
     * Ejecuta la operación {@code file}.
     *
     * @param owner Valor de {@code owner} utilizado por la operación.
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @return Resultado producido por {@code file}.
     * @throws ConflictException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    @Transactional(readOnly = true)
    public URI file(RequestOwner owner, UUID jobId) {
        DownloadJob job = accessibleJob(owner, jobId);
        if (!job.status().downloadable() || job.objectKey() == null || !job.expiresAt().isAfter(clock.instant())) {
            throw new ConflictException("download_not_ready", "El ZIP no está disponible.");
        }
        return zipUris.signGet(job.objectKey(), signedUrlTtl);
    }

    /**
     * Ejecuta la operación {@code applyProgress}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @param itemId Identificador de {@code item} utilizado por la operación.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param bytesDownloaded Valor de {@code bytesDownloaded} utilizado por la operación.
     * @param sha256 Valor de {@code sha256} utilizado por la operación.
     * @param errorCode Valor de {@code errorCode} utilizado por la operación.
     */
    @Transactional
    public void applyProgress(
            UUID jobId,
            UUID itemId,
            DownloadItemStatus status,
            long bytesDownloaded,
            String sha256,
            String errorCode) {
        DownloadJob job = requireJob(jobId);
        job.updateItem(itemId, status, bytesDownloaded, sha256, errorCode, clock.instant());
        notifyAfterSave(jobs.save(job));
    }

    /**
     * Ejecuta la operación {@code applyReady}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param objectKey Valor de {@code objectKey} utilizado por la operación.
     * @param expiresAt Valor de {@code expiresAt} utilizado por la operación.
     */
    @Transactional
    public void applyReady(UUID jobId, DownloadJobStatus status, String objectKey, Instant expiresAt) {
        DownloadJob job = requireJob(jobId);
        job.markReady(status, objectKey, expiresAt, clock.instant());
        DownloadJob saved = jobs.save(job);
        notifyAfterSave(saved);
        requestTerminalNotification(saved);
    }

    /**
     * Ejecuta la operación {@code applyFailed}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @param errorCode Valor de {@code errorCode} utilizado por la operación.
     */
    @Transactional
    public void applyFailed(UUID jobId, String errorCode) {
        DownloadJob job = requireJob(jobId);
        job.fail(errorCode, clock.instant());
        DownloadJob saved = jobs.save(job);
        notifyAfterSave(saved);
        requestTerminalNotification(saved);
    }

    /**
     * Ejecuta la operación {@code expireReadyJobs}.
     */
    @Scheduled(fixedDelayString = "PT10M")
    @Transactional
    public void expireReadyJobs() {
        Instant now = clock.instant();
        jobs.findDownloadableExpiredBefore(now).forEach(job -> {
            if (job.expire(now)) {
                DownloadJob expired = jobs.save(job);
                try {
                    artifacts.deleteJobArtifacts(expired.id());
                } catch (RuntimeException exception) {
                    // El trabajo ya no está disponible para los usuarios. Permite la
                    // siguiente limpieza operativa en vez de reactivar una descarga
                    // caducada. El ciclo de vida del almacenamiento añade otra protección.
                    LOGGER.warn("Could not remove expired download artifacts for job {}", expired.id(), exception);
                }
                notifyAfterSave(expired);
            }
        });
    }

    /**
     * Ejecuta la operación {@code requireJob}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @return Resultado producido por {@code requireJob}.
     */
    private DownloadJob requireJob(UUID jobId) {
        return jobs.findById(jobId)
                .orElseThrow(() -> new NotFoundException("download_job_not_found", "No existe el trabajo."));
    }

    /**
     * Ejecuta la operación {@code accessibleJob}.
     *
     * @param owner Valor de {@code owner} utilizado por la operación.
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @return Resultado producido por {@code accessibleJob}.
     * @throws NotFoundException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private DownloadJob accessibleJob(RequestOwner owner, UUID jobId) {
        DownloadJob job = requireJob(jobId);
        if (!owner.canAccess(job.ownerId(), job.anonymousOwnerHash())) {
            throw new NotFoundException("download_job_not_found", "No existe el trabajo.");
        }
        return job;
    }

    /**
     * Ejecuta la operación {@code enforceAnonymousLimits}.
     *
     * @param owner Valor de {@code owner} utilizado por la operación.
     * @param now Valor de {@code now} utilizado por la operación.
     * @throws RateLimitException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private void enforceAnonymousLimits(RequestOwner owner, Instant now) {
        String browserHash = owner.requireAnonymousOwnerHash();
        if (jobs.countAnonymousNonTerminal(browserHash) >= anonymousMaxActiveJobs) {
            throw new RateLimitException(
                    "anonymous_active_jobs_limit", "Este navegador ya tiene el máximo de descargas en curso.");
        }
        Instant hourAgo = now.minus(Duration.ofHours(1));
        if (jobs.countAnonymousCreatedSince(browserHash, hourAgo) >= anonymousMaxCreatesPerHour) {
            throw new RateLimitException(
                    "anonymous_browser_rate_limit", "Has alcanzado el límite horario de descargas para este navegador.");
        }
        if (owner.anonymousIpHash() != null
                && jobs.countAnonymousIpCreatedSince(owner.anonymousIpHash(), hourAgo) >= anonymousMaxCreatesPerIpHour) {
            throw new RateLimitException(
                    "anonymous_ip_rate_limit", "La dirección de red ha alcanzado el límite horario de descargas.");
        }
    }

    /**
     * Ejecuta la operación {@code notifyAfterSave}.
     *
     * @param job Trabajo de descarga sobre el que se actúa.
     */
    private void notifyAfterSave(DownloadJob job) {
        notifyAfterCommit(DownloadJobView.from(job));
    }

    /**
     * Ejecuta la operación {@code notifyAfterCommit}.
     *
     * @param view Valor de {@code view} utilizado por la operación.
     */
    private void notifyAfterCommit(DownloadJobView view) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notifier.changed(view);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * Implementa {@code afterCommit} para {@code }.
             */
            @Override
            public void afterCommit() {
                notifier.changed(view);
            }
        });
    }

    /**
     * Ejecuta la operación {@code requestTerminalNotification}.
     *
     * @param job Trabajo de descarga sobre el que se actúa.
     */
    private void requestTerminalNotification(DownloadJob job) {
        if (!job.notifyWhenReady() || job.ownerId() == null) {
            return;
        }
        users.findById(job.ownerId())
                .filter(UserAccount::notifyOnJobCompletion)
                .ifPresent(owner -> events.terminalNotificationRequested(owner, job));
    }

    /**
     * Representa los datos inmutables de {@code DownloadItemMetadata}.
     *
     * @param itemId Valor de {@code itemId} incluido en el record.
     * @param appId Valor de {@code appId} incluido en el record.
     * @param appName Valor de {@code appName} incluido en el record.
     * @param officialPageUrl Valor de {@code officialPageUrl} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record DownloadItemMetadata(
            UUID itemId,
            UUID appId,
            String appName,
            String officialPageUrl) {}
}
