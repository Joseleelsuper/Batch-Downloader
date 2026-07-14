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

@Service
public class DownloadJobService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadJobService.class);
    private final DownloadJobStore jobs;
    private final CatalogSourceLookup sources;
    private final UserAccountStore users;
    private final DownloadEventPublisher events;
    private final DownloadJobNotifier notifier;
    private final DownloadArtifactCleaner artifacts;
    private final ZipUriSigner zipUris;
    private final Clock clock;
    private final int maxApps;
    private final Duration retention;
    private final Duration signedUrlTtl;
    private final int anonymousMaxActiveJobs;
    private final int anonymousMaxCreatesPerHour;
    private final int anonymousMaxCreatesPerIpHour;

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
                .map(source -> DownloadJobItem.queued(source.appId(), source.sourceRef(), now))
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

    @Transactional(readOnly = true)
    public DownloadJobView get(RequestOwner owner, UUID jobId) {
        return DownloadJobView.from(accessibleJob(owner, jobId));
    }

    @Transactional
    public DownloadJobView cancel(RequestOwner owner, UUID jobId) {
        DownloadJob job = accessibleJob(owner, jobId);
        if (job.requestCancellation(clock.instant())) {
            jobs.save(job);
            events.cancellationRequested(job);
        }
        DownloadJobView view = DownloadJobView.from(job);
        notifier.changed(view);
        return view;
    }

    @Transactional(readOnly = true)
    public URI file(RequestOwner owner, UUID jobId) {
        DownloadJob job = accessibleJob(owner, jobId);
        if (!job.status().downloadable() || job.objectKey() == null || !job.expiresAt().isAfter(clock.instant())) {
            throw new ConflictException("download_not_ready", "El ZIP no está disponible.");
        }
        return zipUris.signGet(job.objectKey(), signedUrlTtl);
    }

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

    @Transactional
    public void applyReady(UUID jobId, DownloadJobStatus status, String objectKey, Instant expiresAt) {
        DownloadJob job = requireJob(jobId);
        job.markReady(status, objectKey, expiresAt, clock.instant());
        DownloadJob saved = jobs.save(job);
        notifyAfterSave(saved);
        requestTerminalNotification(saved);
    }

    @Transactional
    public void applyFailed(UUID jobId, String errorCode) {
        DownloadJob job = requireJob(jobId);
        job.fail(errorCode, clock.instant());
        DownloadJob saved = jobs.save(job);
        notifyAfterSave(saved);
        requestTerminalNotification(saved);
    }

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
                    // The job is already unavailable to users. Keep the next operational
                    // cleanup pass possible rather than reviving an expired download.
                    // Object storage lifecycle rules are an additional safety net.
                    LOGGER.warn("Could not remove expired download artifacts for job {}", expired.id(), exception);
                }
                notifyAfterSave(expired);
            }
        });
    }

    private DownloadJob requireJob(UUID jobId) {
        return jobs.findById(jobId)
                .orElseThrow(() -> new NotFoundException("download_job_not_found", "No existe el trabajo."));
    }

    private DownloadJob accessibleJob(RequestOwner owner, UUID jobId) {
        DownloadJob job = requireJob(jobId);
        if (!owner.canAccess(job.ownerId(), job.anonymousOwnerHash())) {
            throw new NotFoundException("download_job_not_found", "No existe el trabajo.");
        }
        return job;
    }

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

    private void notifyAfterSave(DownloadJob job) {
        notifier.changed(DownloadJobView.from(job));
    }

    private void requestTerminalNotification(DownloadJob job) {
        if (!job.notifyWhenReady() || job.ownerId() == null) {
            return;
        }
        users.findById(job.ownerId())
                .filter(UserAccount::notifyOnJobCompletion)
                .ifPresent(owner -> events.terminalNotificationRequested(owner, job));
    }
}
