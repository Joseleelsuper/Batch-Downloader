package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminDtos.AdminAuditItem;
import es.ubu.batchdownloader.admin.AdminDtos.ResolverLogItem;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperEvent;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperCommandRequest;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperMetricItem;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperQueueMaintenanceResult;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperQueueState;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperRunSummary;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperRunRequest;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperRunRequestResponse;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperSnapshotItem;
import jakarta.validation.Valid;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expone las operaciones HTTP gestionadas por {@code AdminScraperController}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @apiNote Expone operaciones HTTP sin modificar los contratos de dominio.
 */
@RestController
public class AdminScraperController {
    /**
     * Estado {@code scraper} mantenido por {@code AdminScraperController}.
     */
    private final AdminScraperRepository scraper;
    /**
     * Estado {@code audit} mantenido por {@code AdminScraperController}.
     */
    private final AdminAuditService audit;
    /**
     * Dependencia {@code scraperClient} utilizada por {@code AdminScraperController}.
     */
    private final ScraperInternalClient scraperClient;

    /**
     * Inicializa una instancia de {@code AdminScraperController}.
     *
     * @param scraper Valor de {@code scraper} utilizado por la operación.
     * @param audit Valor de {@code audit} utilizado por la operación.
     * @param scraperClient Valor de {@code scraperClient} utilizado por la operación.
     */
    public AdminScraperController(
            AdminScraperRepository scraper,
            AdminAuditService audit,
            ScraperInternalClient scraperClient) {
        this.scraper = scraper;
        this.audit = audit;
        this.scraperClient = scraperClient;
    }

    /**
     * Ejecuta la operación {@code runs}.
     *
     * @param limit Número máximo de elementos que se recuperarán.
     * @return Colección de elementos obtenidos por la operación.
     */
    @GetMapping("/api/v1/admin/scraper/runs")
    public List<ScraperRunSummary> runs(@RequestParam(defaultValue = "30") int limit) {
        return scraper.runs(limit);
    }

    /**
     * Crea una solicitud durable; el scheduler la ejecutará cuando no exista otro run activo.
     *
     * @param request Scope y selección ya validados.
     * @param principal Identidad autenticada.
     * @return Acuse con el ID estable de la solicitud.
     */
    @PostMapping("/api/v1/admin/scraper/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ScraperRunRequestResponse createRun(
            @Valid @RequestBody ScraperRunRequest request,
            @AuthenticationPrincipal AccountPrincipal principal) {
        String actor = actor(principal);
        List<UUID> appIds = request.appIds() == null ? List.of() : List.copyOf(request.appIds());
        UUID requestId = scraper.enqueueRun(request.scope(), appIds, actor);
        audit.record(
                actor,
                "scraper.run.requested",
                "scraper_run_request",
                requestId.toString(),
                Map.of("scope", request.scope(), "appCount", appIds.size()));
        return new ScraperRunRequestResponse(requestId.toString(), request.scope(), "pending");
    }

    /**
     * Ejecuta la operación {@code current}.
     *
     * @return Resultado producido por {@code current}.
     */
    @GetMapping("/api/v1/admin/scraper/current")
    public ScraperRunSummary current() {
        return scraper.current();
    }

    /**
     * Ejecuta la operación {@code logs}.
     *
     * @param limit Número máximo de elementos que se recuperarán.
     * @return Colección de elementos obtenidos por la operación.
     */
    @GetMapping("/api/v1/admin/scraper/logs")
    public List<ResolverLogItem> logs(@RequestParam(defaultValue = "120") int limit) {
        return scraper.logs(limit);
    }

    /**
     * Ejecuta la operación {@code queues}.
     *
     * @return Colección de elementos obtenidos por la operación.
     */
    @GetMapping("/api/v1/admin/scraper/queues")
    public List<ScraperQueueState> queues() {
        return scraper.queues();
    }

    /**
     * Ejecuta la operación {@code metrics}.
     *
     * @param limit Número máximo de elementos que se recuperarán.
     * @return Colección de elementos obtenidos por la operación.
     */
    @GetMapping("/api/v1/admin/scraper/metrics")
    public List<ScraperMetricItem> metrics(@RequestParam(defaultValue = "60") int limit) {
        return scraper.metrics(limit);
    }

    /**
     * Ejecuta la operación {@code snapshots}.
     *
     * @return Colección de elementos obtenidos por la operación.
     */
    @GetMapping("/api/v1/admin/scraper/snapshots")
    public List<ScraperSnapshotItem> snapshots() {
        return scraper.snapshots();
    }

    /**
     * Ejecuta la operación {@code event}.
     *
     * @return Resultado producido por {@code event}.
     */
    @GetMapping("/api/v1/admin/scraper/event")
    public ScraperEvent event() {
        return scraper.event();
    }

    /**
     * Recupera los elementos afectados mediante {@code recoverStuckQueueItems}.
     *
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code recoverStuckQueueItems}.
     */
    @PostMapping("/api/v1/admin/scraper/queues/recover-stuck")
    public ScraperQueueMaintenanceResult recoverStuckQueueItems(
            @AuthenticationPrincipal AccountPrincipal principal) {
        int affected = scraper.recoverStuckQueueItems();
        audit.record(actor(principal), "scraper.queue.recover_stuck", "scraper", "queues", null);
        return new ScraperQueueMaintenanceResult("recover_stuck", affected);
    }

    /**
     * Reintenta los elementos afectados mediante {@code retryFailedQueueItems}.
     *
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code retryFailedQueueItems}.
     */
    @PostMapping("/api/v1/admin/scraper/queues/retry-failed")
    public ScraperQueueMaintenanceResult retryFailedQueueItems(
            @AuthenticationPrincipal AccountPrincipal principal) {
        int affected = scraper.retryFailedQueueItems();
        audit.record(actor(principal), "scraper.queue.retry_failed", "scraper", "queues", null);
        return new ScraperQueueMaintenanceResult("retry_failed", affected);
    }

    /**
     * Ejecuta la operación {@code pruneTerminalQueueItems}.
     *
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code pruneTerminalQueueItems}.
     */
    @PostMapping("/api/v1/admin/scraper/queues/prune-terminal")
    public ScraperQueueMaintenanceResult pruneTerminalQueueItems(
            @AuthenticationPrincipal AccountPrincipal principal) {
        int affected = scraper.pruneTerminalQueueItems();
        audit.record(actor(principal), "scraper.queue.prune_terminal", "scraper", "queues", null);
        return new ScraperQueueMaintenanceResult("prune_terminal", affected);
    }

    /**
     * Ejecuta la operación {@code command}.
     *
     * @param request Solicitud recibida por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Mapa con los datos producidos por la operación.
     */
    @PostMapping("/api/v1/admin/scraper/commands")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> command(
            @Valid @RequestBody ScraperCommandRequest request,
            @AuthenticationPrincipal AccountPrincipal principal) {
        String actor = actor(principal);
        scraper.enqueueCommand(request.command(), actor);
        if ("force_stop".equals(request.command())) {
            int stopped = scraper.forceStopRunningRuns();
            int recovered = scraper.releaseInProgressQueueItems();
            audit.record(
                    actor,
                    "scraper.command.force_stop",
                    "scraper",
                    request.command(),
                    Map.of("runs", stopped, "recoveredQueueItems", recovered));
            return Map.of("status", "accepted", "command", request.command());
        }
        audit.record(actor, "scraper.command", "scraper", request.command(), null);
        return Map.of("status", "accepted", "command", request.command());
    }

    /**
     * Encola la operación solicitada mediante {@code enqueueMissingDescriptions}.
     *
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code enqueueMissingDescriptions}.
     */
    @PostMapping("/api/v1/admin/scraper/descriptions/enqueue-missing")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ScraperInternalClient.ContentEnqueueResult enqueueMissingDescriptions(
            @AuthenticationPrincipal AccountPrincipal principal) {
        ScraperInternalClient.ContentEnqueueResult result = scraperClient.enqueueMissingDescriptions();
        audit.record(
                actor(principal),
                "scraper.description.enqueue_missing",
                "scraper",
                "descriptions",
                Map.of(
                        "matched", result.matched(),
                        "enqueued", result.enqueued(),
                        "alreadyActive", result.alreadyActive()));
        return result;
    }

    /**
     * Ejecuta la operación {@code audit}.
     *
     * @param limit Número máximo de elementos que se recuperarán.
     * @return Colección de elementos obtenidos por la operación.
     */
    @GetMapping("/api/v1/admin/audit")
    public List<AdminAuditItem> audit(@RequestParam(defaultValue = "100") int limit) {
        return scraper.audit(limit);
    }

    /**
     * Ejecuta la operación {@code actor}.
     *
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code actor}.
     */
    private String actor(AccountPrincipal principal) {
        return AdminActor.require(principal);
    }
}
