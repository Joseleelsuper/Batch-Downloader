package es.ubu.batchdownloader.downloads.application;

import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.RateLimitException;
import es.ubu.batchdownloader.common.ServiceUnavailableException;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner.RequestOwner;
import es.ubu.batchdownloader.downloads.application.port.CatalogSourceLookup;
import es.ubu.batchdownloader.downloads.application.port.DownloadEventPublisher;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobItem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admite selecciones de descarga bajo cuotas y conserva fuente exacta y dependencias Linux al
 * guardar trabajo, elementos y outbox.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.application.DownloadSelection
 * @see es.ubu.batchdownloader.downloads.application.port.CatalogSourceLookup
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobAccessService
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
@Service
@org.springframework.boot.context.properties.EnableConfigurationProperties(DownloadLimits.class)
public class DownloadJobService {
    /**
     * Persistencia de trabajos, elementos, contexto Linux y reservas de admisión.
     */
    private final DownloadJobStore jobs;
    /**
     * Consulta del catálogo que conserva fuentes exactas, compatibilidad y dependencias Linux.
     */
    private final CatalogSourceLookup sources;
    /**
     * Publicador de solicitudes durables mediante el outbox de la transacción actual.
     */
    private final DownloadEventPublisher events;
    /**
     * Reloj que determina cuotas, cambios de estado y vencimientos.
     */
    private final Clock clock;
    /**
     * Cuotas de admisión y duraciones de conservación y firma del ZIP.
     */
    private final DownloadLimits limits;

    /**
     * Conecta selección de fuentes, persistencia, publicación durable y límites de admisión.
     *
     * @param jobs Persistencia de trabajos, elementos, contexto Linux y reservas de admisión.
     * @param sources Consulta del catálogo que conserva fuentes exactas, compatibilidad y
     *     dependencias Linux.
     *
     * @param events Publicador de solicitudes durables mediante el outbox de la transacción actual.
     * @param clock Reloj que determina cuotas, cambios de estado y vencimientos.
     * @param limits Cuotas de admisión y duraciones de conservación y firma del ZIP.
     */
    public DownloadJobService(DownloadJobStore jobs, CatalogSourceLookup sources, DownloadEventPublisher events, Clock clock, DownloadLimits limits) {
        this.jobs = jobs;
        this.sources = sources;
        this.events = events;
        this.clock = clock;
        this.limits = limits;
    }

    /**
     * Resume compatibilidad e instalación manual de una selección Linux expandida con sus
     * dependencias.
     *
     * @param target Gestor Linux seleccionado o contexto validado del destino según la firma.
     * @param architecture Arquitectura del destino: x86_64, x86 o aarch64.
     * @param totalCount Total de aplicaciones evaluadas, incluidas dependencias.
     * @param automaticCount Aplicaciones con instalación automática compatible con el destino.
     * @param manualCount Aplicaciones que conservan una alternativa manual.
     * @param omittedCount Aplicaciones solicitadas sin instalador ni alternativa manual aceptada.
     * @param items Elementos en orden de admisión; el agregado conserva una copia de la lista.
     * @see
     *     DownloadJobService#previewLinux(es.ubu.batchdownloader.downloads.application.DownloadSelection)
     *
     * @since 0.1.0
     * @version 0.1.0
     * @category Descargas
     */
    public record LinuxPreview(String target, String architecture, int totalCount,
            int automaticCount, int manualCount, int omittedCount,
            List<LinuxPreviewItem> items) {}

    /**
     * Describe la fuente elegida y si una aplicación llegó por dependencia o por selección
     * explícita.
     *
     * @param appId UUID público de la aplicación del catálogo.
     * @param name Nombre visible de la aplicación o nombre del campo requerido según el método.
     * @param sourceRef UUID de la fuente exacta; null permite selección automática o representa una
     *     alternativa manual.
     *
     * @param installationSupport automatic, manual o unavailable según la capacidad de instalación
     *     del destino.
     *
     * @param dependency La aplicación se añadió como dependencia y no estaba en la selección
     *     original.
     *
     * @since 0.1.0
     * @version 0.1.0
     * @category Descargas
     */
    public record LinuxPreviewItem(UUID appId, String name, UUID sourceRef,
            String installationSupport, boolean dependency) {}

    /**
     * Evalúa compatibilidad y dependencias sin crear trabajos ni encolar descargas; una fuente
     * exacta incompatible se rechaza.
     *
     * @param selection Aplicaciones, plataformas, fuente exacta y destino Linux de la misma
     *     solicitud.
     *
     * @return resumen de instaladores automáticos, alternativas manuales y omisiones.
     * @throws es.ubu.batchdownloader.common.BadRequestException si faltan destino o aplicaciones
     *     válidas o la fuente exacta se aplica a varias aplicaciones.
     *
     * @throws es.ubu.batchdownloader.common.ConflictException si la fuente exacta no es compatible
     *     con el destino Linux.
     */
    @Transactional(readOnly = true)
    public LinuxPreview previewLinux(DownloadSelection selection) {
        LinuxTarget target = LinuxTarget.optional(selection.linuxTarget(), selection.targetArchitecture(), selection.operatingSystems());
        validatePreviewSelection(selection, target);
        List<UUID> ids = sources.expandLinuxDependencies(new LinkedHashSet<>(selection.appIds()));
        var selected = sources.findLinuxSources(ids, target, selection.sourceRef());
        if (selection.sourceRef() != null && !selected.containsKey(selection.appIds().getFirst())) {
            throw new ConflictException("linux_source_incompatible", "Esta fuente no es compatible con el destino.");
        }
        var manual = sources.findManualSources(ids);
        List<LinuxPreviewItem> items = ids.stream()
                .map(id -> previewItem(id, selected, manual, selection.appIds()))
                .toList();
        return new LinuxPreview(target.manager(), target.architecture(), ids.size(),
                (int) items.stream().filter(i -> "automatic".equals(i.installationSupport())).count(),
                (int) items.stream().filter(i -> "manual".equals(i.installationSupport())).count(),
                (int) items.stream().filter(i -> "unavailable".equals(i.installationSupport())).count(), items);
    }

    /**
     * Valida y deduplica la selección, amplía dependencias y comprueba cuotas bajo el bloqueo de
     * admisión.
     * Guarda trabajo, elementos, contexto Linux y solicitud de procesamiento dentro de una
     * transacción; una fuente exacta nunca se sustituye por otra.
     *
     * @param owner Identidad autenticada o hashes del navegador que solicita acceso o creación.
     * @param selection Aplicaciones, plataformas, fuente exacta y destino Linux de la misma
     *     solicitud.
     *
     * @param notifyWhenReady El propietario solicita aviso al terminar; la admisión lo habilita
     *     solo para cuentas autenticadas.
     *
     * @return vista inicial del trabajo admitido.
     * @throws es.ubu.batchdownloader.common.BadRequestException si la selección o el destino no son
     *     válidos o exceden el máximo.
     *
     * @throws es.ubu.batchdownloader.common.RateLimitException si la cuenta, el navegador o la IP
     *     exceden su cuota.
     *
     * @throws es.ubu.batchdownloader.common.ServiceUnavailableException si se alcanza el máximo
     *     global de trabajos pendientes.
     *
     * @throws es.ubu.batchdownloader.common.ConflictException si la fuente exacta no está
     *     disponible o no se admite ninguna aplicación.
     */
    @Transactional
    public DownloadJobView create(RequestOwner owner, DownloadSelection selection, boolean notifyWhenReady) {
        LinuxTarget target = LinuxTarget.optional(selection.linuxTarget(), selection.targetArchitecture(), selection.operatingSystems());
        LinkedHashSet<UUID> appIds = normalizedAppIds(selection);
        validateAppSelection(appIds, selection);
        List<UUID> originalAppIds = List.copyOf(appIds);
        if (target != null) appIds.addAll(sources.expandLinuxDependencies(appIds));
        validateDependencyLimit(appIds);
        jobs.lockAdmission();
        Instant now = clock.instant();
        enforceAdmission(owner, now);
        Map<UUID, CatalogSourceLookup.VerifiedSource> selected = selectSources(appIds, target, selection);
        Map<UUID, CatalogSourceLookup.ManualSource> manualSources = manualSources(appIds, target, selection);
        List<DownloadJobItem> items = buildItems(appIds, selected, manualSources, now);
        int omittedCount = appIds.size() - items.size();
        if (items.isEmpty()) {
            throw new ConflictException(
                    "no_downloadable_apps",
                    "Ninguna de las aplicaciones seleccionadas tiene un instalador o una página oficial segura.");
        }
        return persistJob(owner, items, appIds, originalAppIds, omittedCount, notifyWhenReady, now, target);
    }

    private void validatePreviewSelection(DownloadSelection selection, LinuxTarget target) {
        if (target == null || selection.appIds() == null || selection.appIds().isEmpty()
                || selection.appIds().size() > limits.maxApps()
                || (selection.sourceRef() != null && selection.appIds().size() != 1)) {
            throw new BadRequestException("invalid_linux_selection", "Indica un lote Linux válido.");
        }
    }

    private static LinuxPreviewItem previewItem(
            UUID id,
            Map<UUID, CatalogSourceLookup.VerifiedSource> selected,
            Map<UUID, CatalogSourceLookup.ManualSource> manual,
            List<UUID> requested) {
        CatalogSourceLookup.VerifiedSource source = selected.get(id);
        CatalogSourceLookup.ManualSource fallback = manual.get(id);
        String name = id.toString();
        String support = "unavailable";
        UUID sourceRef = null;
        if (source != null) {
            name = source.appName();
            sourceRef = source.sourceRef();
            support = source.installationSupport();
        } else if (fallback != null) {
            name = fallback.appName();
            support = "manual";
        }
        return new LinuxPreviewItem(id, name, sourceRef, support, !requested.contains(id));
    }

    private LinkedHashSet<UUID> normalizedAppIds(DownloadSelection selection) {
        LinkedHashSet<UUID> appIds = new LinkedHashSet<>(
                selection.appIds() == null ? List.of() : selection.appIds());
        appIds.remove(null);
        return appIds;
    }

    private void validateAppSelection(LinkedHashSet<UUID> appIds, DownloadSelection selection) {
        if (appIds.isEmpty() || appIds.size() > limits.maxApps()) {
            throw new BadRequestException(
                    "invalid_job_size", "Selecciona entre 1 y " + limits.maxApps() + " aplicaciones.");
        }
        if (selection.sourceRef() != null && appIds.size() != 1) {
            throw new BadRequestException(
                    "invalid_source_selection",
                    "La fuente seleccionada requiere una única aplicación.");
        }
    }

    private void validateDependencyLimit(LinkedHashSet<UUID> appIds) {
        if (appIds.size() > limits.maxApps()) {
            throw new BadRequestException(
                    "linux_dependency_limit", "El lote y sus dependencias superan el límite.");
        }
    }

    private void enforceAdmission(RequestOwner owner, Instant now) {
        if (jobs.countNonTerminal() >= limits.globalMaxPendingJobs()) {
            throw new ServiceUnavailableException(
                    "service_busy", "La cola de descargas está llena. Inténtalo de nuevo.", 30);
        }
        if (owner.authenticated()) {
            enforceAuthenticatedLimit(owner);
            return;
        }
        enforceAnonymousLimits(owner, now);
    }

    private void enforceAuthenticatedLimit(RequestOwner owner) {
        if (jobs.countNonTerminalByOwner(owner.userId()) >= limits.authenticatedMaxActiveJobs()) {
            throw new RateLimitException(
                    "rate_limited",
                    "La cuenta ya tiene el máximo de descargas activas o pendientes.",
                    60);
        }
    }

    private Map<UUID, CatalogSourceLookup.VerifiedSource> selectSources(
            LinkedHashSet<UUID> appIds, LinuxTarget target, DownloadSelection selection) {
        if (target != null) {
            Map<UUID, CatalogSourceLookup.VerifiedSource> result =
                    sources.findLinuxSources(appIds, target, selection.sourceRef());
            if (selection.sourceRef() != null && !result.containsKey(appIds.getFirst())) {
                throw new ConflictException(
                        "linux_source_incompatible", "La fuente elegida no es compatible con el destino Linux.");
            }
            return result;
        }
        if (selection.sourceRef() == null) {
            return sources.findVerifiedSources(appIds, selection.operatingSystems());
        }
        UUID appId = appIds.getFirst();
        CatalogSourceLookup.VerifiedSource exactSource = sources
                .findVerifiedSource(appId, selection.sourceRef(), selection.operatingSystems())
                .orElseThrow(() -> new ConflictException(
                        "selected_source_unavailable",
                        "La versión seleccionada ya no está disponible para descargar."));
        return Map.of(appId, exactSource);
    }

    private Map<UUID, CatalogSourceLookup.ManualSource> manualSources(
            LinkedHashSet<UUID> appIds, LinuxTarget target, DownloadSelection selection) {
        if (selection.sourceRef() != null && target == null) {
            return Map.of();
        }
        return sources.findManualSources(appIds);
    }

    private static List<DownloadJobItem> buildItems(
            LinkedHashSet<UUID> appIds,
            Map<UUID, CatalogSourceLookup.VerifiedSource> selected,
            Map<UUID, CatalogSourceLookup.ManualSource> manualSources,
            Instant now) {
        return appIds.stream()
                .map(appId -> itemFor(appId, selected, manualSources, now))
                .filter(Objects::nonNull)
                .toList();
    }

    private static DownloadJobItem itemFor(
            UUID appId,
            Map<UUID, CatalogSourceLookup.VerifiedSource> selected,
            Map<UUID, CatalogSourceLookup.ManualSource> manualSources,
            Instant now) {
        CatalogSourceLookup.VerifiedSource source = selected.get(appId);
        if (source != null) {
            return DownloadJobItem.queued(
                    source.appId(), source.sourceRef(), source.appName(), source.officialPageUrl(), now);
        }
        CatalogSourceLookup.ManualSource manual = manualSources.get(appId);
        if (manual == null) {
            return null;
        }
        return DownloadJobItem.manual(manual.appId(), manual.appName(), manual.officialPageUrl(), now);
    }

    private DownloadJobView persistJob(
            RequestOwner owner,
            List<DownloadJobItem> items,
            LinkedHashSet<UUID> appIds,
            List<UUID> originalAppIds,
            int omittedCount,
            boolean notifyWhenReady,
            Instant now,
            LinuxTarget target) {
        DownloadJob job = jobs.save(DownloadJob.queue(
                owner.authenticated() ? owner.userId() : null,
                owner.authenticated() ? null : owner.requireAnonymousOwnerHash(),
                owner.authenticated() ? null : owner.anonymousIpHash(),
                items,
                appIds.size(),
                omittedCount,
                notifyWhenReady && owner.authenticated(),
                now,
                now.plus(limits.zipRetention())));
        events.jobRequested(job);
        if (target == null) {
            return DownloadJobView.from(job);
        }
        var context = new DownloadJobView.LinuxContext(target.manager(), target.architecture(),
                appIds.stream().filter(id -> !originalAppIds.contains(id)).toList());
        jobs.saveLinuxContext(job.id(), context);
        return DownloadJobView.from(job).withLinuxContext(context);
    }

    /**
     * Aplica cuota de trabajos activos por navegador y creaciones en la última hora por navegador
     * y, cuando existe, IP.
     *
     * @param owner Identidad autenticada o hashes del navegador que solicita acceso o creación.
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
     * @throws es.ubu.batchdownloader.common.RateLimitException si se agota cualquiera de las cuotas
     *     anónimas.
     *
     * @throws es.ubu.batchdownloader.common.NotFoundException si falta una identidad válida del
     *     navegador.
     */
    private void enforceAnonymousLimits(RequestOwner owner, Instant now) {
        String browserHash = owner.requireAnonymousOwnerHash();
        if (jobs.countAnonymousNonTerminal(browserHash) >= limits.anonymousMaxActiveJobs()) {
            throw new RateLimitException(
                    "anonymous_active_jobs_limit", "Este navegador ya tiene el máximo de descargas en curso.");
        }
        Instant hourAgo = now.minus(Duration.ofHours(1));
        if (jobs.countAnonymousCreatedSince(browserHash, hourAgo) >= limits.anonymousMaxCreatesPerHour()) {
            throw new RateLimitException(
                    "anonymous_browser_rate_limit", "Has alcanzado el límite horario de descargas para este navegador.");
        }
        if (owner.anonymousIpHash() != null
                && jobs.countAnonymousIpCreatedSince(owner.anonymousIpHash(), hourAgo) >= limits.anonymousMaxCreatesPerIpHour()) {
            throw new RateLimitException(
                    "anonymous_ip_rate_limit", "La dirección de red ha alcanzado el límite horario de descargas.");
        }
    }

}
