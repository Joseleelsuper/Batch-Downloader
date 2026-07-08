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
import es.ubu.batchdownloader.common.ConflictException;
import jakarta.validation.Valid;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminScraperController {
    private final AdminScraperRepository scraper;
    private final AdminAuditService audit;
    private final String scraperApiUrl;
    private final HttpClient httpClient;

    public AdminScraperController(
            AdminScraperRepository scraper,
            AdminAuditService audit,
            @Value("${app.scraper-api-url}") String scraperApiUrl) {
        this.scraper = scraper;
        this.audit = audit;
        this.scraperApiUrl = scraperApiUrl.replaceAll("/+$", "");
        this.httpClient = HttpClient.newHttpClient();
    }

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

    @PostMapping("/api/admin/scraper/commands")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> command(
            @Valid @RequestBody ScraperCommandRequest request,
            Principal principal) throws Exception {
        String actor = actor(principal);
        scraper.enqueueCommand(request.command(), actor);
        if ("run_once".equals(request.command())) {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(scraperApiUrl + "/api/internal/scraper/run-once"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new ConflictException("scraper_run_once_failed", "No se pudo lanzar el scraper.");
            }
        }
        audit.record(actor, "scraper.command", "scraper", request.command(), null);
        return Map.of("status", "accepted", "command", request.command());
    }

    @GetMapping("/api/admin/audit")
    public List<AdminAuditItem> audit(@RequestParam(defaultValue = "100") int limit) {
        return scraper.audit(limit);
    }

    private String actor(Principal principal) {
        return principal == null ? "admin" : principal.getName();
    }
}
