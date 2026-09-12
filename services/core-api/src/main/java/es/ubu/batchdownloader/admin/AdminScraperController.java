package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminAuditDtos.AdminAuditItem;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ResolverLogItem;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperEvent;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperCommandRequest;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperMetricItem;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperQueueMaintenanceResult;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperQueueState;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperRunSummary;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperRunRequest;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperRunRequestResponse;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperSnapshotItem;
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
 * Expone observación, solicitudes de ejecución y mantenimiento controlado del scraper con auditoría
 * del actor administrativo.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.AdminScraperRepository
 * @see es.ubu.batchdownloader.admin.ScraperInternalClient
 * @see es.ubu.batchdownloader.admin.AdminAuditService
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración
 */
@RestController
public class AdminScraperController {
    /**
     * Estado {@code scraper} mantenido por {@code AdminScraperController}.
     */
    private final AdminScraperRepository scraper;
    /**
     * Proyección administrativa del estado persistido.
     */
    private final AdminAuditService audit;
    /**
     * Dependencia {@code scraperClient} utilizada por {@code AdminScraperController}.
     */
    private final ScraperInternalClient scraperClient;

    /**
     * Conecta estado persistido, comandos y generación de contenido con la auditoría
     * administrativa.
     *
     * @param scraper Consultas y mantenimiento de las colas del scraper, o su cliente HTTP según la
     *     firma.
     * @param audit Registro de acciones con actor UUID y metadatos seguros sin URLs resueltas.
     * @param scraperClient Cliente interno autenticado para inspección, descubrimiento y generación
     *     de contenido.
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
     * Consulta el historial reciente de ejecuciones del scraper.
     *
     * @param limit Máximo solicitado de registros; el repositorio aplica el límite propio de cada
     *     consulta.
     * @return proyección administrativa del estado persistido.
     */
    @GetMapping("/api/v1/admin/scraper/runs")
    public List<ScraperRunSummary> runs(@RequestParam(defaultValue = "30") int limit) {
        return scraper.runs(limit);
    }

    /**
     * Guarda una solicitud persistente con alcance y UUID seleccionados y audita la intención antes
     * de que el scheduler la reserve.
     *
     * @param request Cuerpo validado de la operación; las confirmaciones conservan selección y
     *     versión esperadas.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return 202 con identidad de solicitud y estado pending.
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
     * Consulta la última ejecución conocida, que puede haber terminado.
     *
     * @return proyección administrativa del estado persistido.
     */
    @GetMapping("/api/v1/admin/scraper/current")
    public ScraperRunSummary current() {
        return scraper.current();
    }

    /**
     * Consulta registros recientes de resolución con los campos seguros de diagnóstico.
     *
     * @param limit Máximo solicitado de registros; el repositorio aplica el límite propio de cada
     *     consulta.
     * @return proyección administrativa del estado persistido.
     */
    @GetMapping("/api/v1/admin/scraper/logs")
    public List<ResolverLogItem> logs(@RequestParam(defaultValue = "120") int limit) {
        return scraper.logs(limit);
    }

    /**
     * Consulta recuentos de estado y próxima actividad de las colas persistentes.
     *
     * @return proyección administrativa del estado persistido.
     */
    @GetMapping("/api/v1/admin/scraper/queues")
    public List<ScraperQueueState> queues() {
        return scraper.queues();
    }

    /**
     * Consulta mediciones recientes de las etapas del scraper.
     *
     * @param limit Máximo solicitado de registros; el repositorio aplica el límite propio de cada
     *     consulta.
     * @return proyección administrativa del estado persistido.
     */
    @GetMapping("/api/v1/admin/scraper/metrics")
    public List<ScraperMetricItem> metrics(@RequestParam(defaultValue = "60") int limit) {
        return scraper.metrics(limit);
    }

    /**
     * Consulta las instantáneas persistidas de sincronización de Winstall.
     *
     * @return proyección administrativa del estado persistido.
     */
    @GetMapping("/api/v1/admin/scraper/snapshots")
    public List<ScraperSnapshotItem> snapshots() {
        return scraper.snapshots();
    }

    /**
     * Reúne una instantánea administrativa con versión para actualizar la interfaz.
     *
     * @return proyección administrativa del estado persistido.
     */
    @GetMapping("/api/v1/admin/scraper/event")
    public ScraperEvent event() {
        return scraper.event();
    }

    /**
     * Solicita recuperar reservas vencidas y audita la operación de mantenimiento.
     *
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return tipo recover_stuck y número de elementos afectados.
     */
    @PostMapping("/api/v1/admin/scraper/queues/recover-stuck")
    public ScraperQueueMaintenanceResult recoverStuckQueueItems(
            @AuthenticationPrincipal AccountPrincipal principal) {
        int affected = scraper.recoverStuckQueueItems();
        audit.record(actor(principal), "scraper.queue.recover_stuck", "scraper", "queues", null);
        return new ScraperQueueMaintenanceResult("recover_stuck", affected);
    }

    /**
     * Devuelve fallos elegibles a la cola de procesamiento y registra la acción administrativa.
     *
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return tipo retry_failed y cantidad reencolada.
     */
    @PostMapping("/api/v1/admin/scraper/queues/retry-failed")
    public ScraperQueueMaintenanceResult retryFailedQueueItems(
            @AuthenticationPrincipal AccountPrincipal principal) {
        int affected = scraper.retryFailedQueueItems();
        audit.record(actor(principal), "scraper.queue.retry_failed", "scraper", "queues", null);
        return new ScraperQueueMaintenanceResult("retry_failed", affected);
    }

    /**
     * Poda únicamente elementos terminales elegibles bajo la ventana y límite del repositorio y
     * registra el mantenimiento.
     *
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return tipo prune_terminal y cantidad eliminada.
     */
    @PostMapping("/api/v1/admin/scraper/queues/prune-terminal")
    public ScraperQueueMaintenanceResult pruneTerminalQueueItems(
            @AuthenticationPrincipal AccountPrincipal principal) {
        int affected = scraper.pruneTerminalQueueItems();
        audit.record(actor(principal), "scraper.queue.prune_terminal", "scraper", "queues", null);
        return new ScraperQueueMaintenanceResult("prune_terminal", affected);
    }

    /**
     * Persiste el comando para el scheduler; force_stop marca ejecuciones activas como detenidas y
     * libera elementos en progreso, conservando recuentos en auditoría.
     *
     * @param request Cuerpo validado de la operación; las confirmaciones conservan selección y
     *     versión esperadas.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return 202 con command y status accepted.
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
     * Solicita encolar descripciones ausentes y audita coincidencias, nuevas tareas y tareas ya
     * activas.
     *
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return 202 con el resumen del encolado.
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
     * Consulta las acciones administrativas registradas más recientemente.
     *
     * @param limit Máximo solicitado de registros; el repositorio aplica el límite propio de cada
     *     consulta.
     * @return proyección administrativa del estado persistido.
     */
    @GetMapping("/api/v1/admin/audit")
    public List<AdminAuditItem> audit(@RequestParam(defaultValue = "100") int limit) {
        return scraper.audit(limit);
    }

    /**
     * Exige el principal administrativo y extrae su UUID estable para auditar la acción.
     *
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return UUID textual del actor.
     */
    private String actor(AccountPrincipal principal) {
        return AdminActor.require(principal);
    }
}
