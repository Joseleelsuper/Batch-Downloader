package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminDtos.AdminAuditItem;
import es.ubu.batchdownloader.admin.AdminDtos.ResolverLogItem;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperEvent;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperCommandRequest;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperMetricItem;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperQueueMaintenanceResult;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperQueueState;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperRunSummary;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperSnapshotItem;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador de administración para el scraper.
 * 
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @version 1.0
 * @since 1.0
 * @apiNote Este controlador proporciona endpoints para la administración del scraper, incluyendo la visualización de ejecuciones, logs, colas y métricas, así como la ejecución de comandos administrativos.
 * @category Controlador de administración
 */
@RestController
public class AdminScraperController {
    private final AdminScraperRepository scraper;
    private final AdminAuditService audit;
    private final ScraperInternalClient scraperClient;

    public AdminScraperController(
            AdminScraperRepository scraper,
            AdminAuditService audit,
            ScraperInternalClient scraperClient) {
        this.scraper = scraper;
        this.audit = audit;
        this.scraperClient = scraperClient;
    }

    /**
     * Obtiene un resumen de las ejecuciones del scraper.
     * 
     * @param limit El número máximo de ejecuciones a recuperar. Por defecto es 30.
     * @return Una lista de resúmenes de ejecuciones del scraper.
     * @throws IllegalArgumentException Si el parámetro limit es negativo.
     * @since 1.0
     */
    @GetMapping("/api/admin/scraper/runs")
    public List<ScraperRunSummary> runs(@RequestParam(defaultValue = "30") int limit) {
        return scraper.runs(limit);
    }

    @GetMapping("/api/admin/scraper/current")
    public ScraperRunSummary current() {
        return scraper.current();
    }

    @GetMapping("/api/admin/scraper/logs")
    public List<ResolverLogItem> logs(@RequestParam(defaultValue = "120") int limit) {
        return scraper.logs(limit);
    }

    @GetMapping("/api/admin/scraper/queues")
    public List<ScraperQueueState> queues() {
        return scraper.queues();
    }

    @GetMapping("/api/admin/scraper/metrics")
    public List<ScraperMetricItem> metrics(@RequestParam(defaultValue = "60") int limit) {
        return scraper.metrics(limit);
    }

    @GetMapping("/api/admin/scraper/snapshots")
    public List<ScraperSnapshotItem> snapshots() {
        return scraper.snapshots();
    }

    @GetMapping("/api/admin/scraper/event")
    public ScraperEvent event() {
        return scraper.event();
    }

    @PostMapping("/api/admin/scraper/queues/recover-stuck")
    public ScraperQueueMaintenanceResult recoverStuckQueueItems(Principal principal) {
        int affected = scraper.recoverStuckQueueItems();
        audit.record(actor(principal), "scraper.queue.recover_stuck", "scraper", "queues", null);
        return new ScraperQueueMaintenanceResult("recover_stuck", affected);
    }

    @PostMapping("/api/admin/scraper/queues/retry-failed")
    public ScraperQueueMaintenanceResult retryFailedQueueItems(Principal principal) {
        int affected = scraper.retryFailedQueueItems();
        audit.record(actor(principal), "scraper.queue.retry_failed", "scraper", "queues", null);
        return new ScraperQueueMaintenanceResult("retry_failed", affected);
    }

    @PostMapping("/api/admin/scraper/queues/prune-terminal")
    public ScraperQueueMaintenanceResult pruneTerminalQueueItems(Principal principal) {
        int affected = scraper.pruneTerminalQueueItems();
        audit.record(actor(principal), "scraper.queue.prune_terminal", "scraper", "queues", null);
        return new ScraperQueueMaintenanceResult("prune_terminal", affected);
    }

    @PostMapping("/api/admin/scraper/queues/clear-pending")
    public ScraperQueueMaintenanceResult clearPendingQueueItems(Principal principal) {
        int affected = scraper.clearPendingQueueItems();
        audit.record(actor(principal), "scraper.queue.clear_pending", "scraper", "queues", null);
        return new ScraperQueueMaintenanceResult("clear_pending", affected);
    }

    @PostMapping("/api/admin/scraper/queues/clear-all")
    public ScraperQueueMaintenanceResult clearAllQueueItems(Principal principal) {
        int affected = scraper.clearAllQueueItems();
        audit.record(actor(principal), "scraper.queue.clear_all", "scraper", "queues", null);
        return new ScraperQueueMaintenanceResult("clear_all", affected);
    }

    @PostMapping("/api/admin/scraper/commands")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> command(
            @Valid @RequestBody ScraperCommandRequest request,
            Principal principal) {
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
        if ("run_once".equals(request.command())) {
            scraperClient.triggerRunOnce();
        }
        audit.record(actor, "scraper.command", "scraper", request.command(), null);
        return Map.of("status", "accepted", "command", request.command());
    }

    @PostMapping("/api/admin/scraper/descriptions/enqueue-missing")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ScraperInternalClient.ContentEnqueueResult enqueueMissingDescriptions(Principal principal) {
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

    @GetMapping("/api/admin/audit")
    public List<AdminAuditItem> audit(@RequestParam(defaultValue = "100") int limit) {
        return scraper.audit(limit);
    }

    private String actor(Principal principal) {
        return principal == null ? "admin" : principal.getName();
    }
}
