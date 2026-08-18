package es.ubu.batchdownloader.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Expone las operaciones HTTP gestionadas por {@code AdminSemanticController}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @apiNote Expone operaciones HTTP sin modificar los contratos de dominio.
 */
@RestController
public class AdminSemanticController {
    /**
     * Constante que define {@code INTERNAL_ROOT}.
     */
    private static final String INTERNAL_ROOT = "/internal/v1/admin/semantic";

    /**
     * Estado {@code semantic} mantenido por {@code AdminSemanticController}.
     */
    private final SemanticAdminClient semantic;
    /**
     * Estado {@code audit} mantenido por {@code AdminSemanticController}.
     */
    private final AdminAuditService audit;

    /**
     * Inicializa una instancia de {@code AdminSemanticController}.
     *
     * @param semantic Valor de {@code semantic} utilizado por la operación.
     * @param audit Valor de {@code audit} utilizado por la operación.
     */
    public AdminSemanticController(
            SemanticAdminClient semantic,
            AdminAuditService audit) {
        this.semantic = semantic;
        this.audit = audit;
    }

    /**
     * Ejecuta la operación {@code overview}.
     *
     * @return Resultado producido por {@code overview}.
     */
    @GetMapping("/api/admin/semantic/overview")
    public ResponseEntity<JsonNode> overview() {
        return response(semantic.get(INTERNAL_ROOT + "/overview"));
    }

    /**
     * Ejecuta la operación {@code models}.
     *
     * @return Resultado producido por {@code models}.
     */
    @GetMapping("/api/admin/semantic/models")
    public ResponseEntity<JsonNode> models() {
        return response(semantic.get(INTERNAL_ROOT + "/models"));
    }

    /**
     * Ejecuta la operación {@code model}.
     *
     * @param modelId Identificador de {@code model} utilizado por la operación.
     * @return Resultado producido por {@code model}.
     */
    @GetMapping("/api/admin/semantic/models/{modelId}")
    public ResponseEntity<JsonNode> model(@PathVariable UUID modelId) {
        return response(semantic.get(INTERNAL_ROOT + "/models/" + modelId));
    }

    /**
     * Ejecuta la operación {@code benchmarks}.
     *
     * @param limit Número máximo de elementos que se recuperarán.
     * @return Resultado producido por {@code benchmarks}.
     */
    @GetMapping("/api/admin/semantic/benchmarks")
    public ResponseEntity<JsonNode> benchmarks(
            @RequestParam(defaultValue = "50") int limit) {
        String path = UriComponentsBuilder.fromPath(INTERNAL_ROOT + "/benchmarks")
                .queryParam("limit", limit)
                .build()
                .encode()
                .toUriString();
        return response(semantic.get(path));
    }

    /**
     * Ejecuta la operación {@code benchmark}.
     *
     * @param body Cuerpo recibido por la solicitud.
     * @param idempotencyKey Valor de {@code idempotencyKey} utilizado por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code benchmark}.
     */
    @PostMapping("/api/admin/semantic/benchmarks")
    public ResponseEntity<JsonNode> benchmark(
            @RequestBody JsonNode body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Principal principal) {
        String actor = actor(principal);
        SemanticAdminClient.Result result = semantic.post(
                INTERNAL_ROOT + "/benchmarks",
                body,
                actor,
                idempotencyKey);
        auditAccepted(
                result,
                actor,
                "semantic.benchmark.start",
                "semantic_benchmark",
                result.body().path("operationId").asText("pending"));
        return response(result);
    }

    /**
     * Ejecuta la operación {@code prepare}.
     *
     * @param modelId Identificador de {@code model} utilizado por la operación.
     * @param idempotencyKey Valor de {@code idempotencyKey} utilizado por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code prepare}.
     */
    @PostMapping("/api/admin/semantic/models/{modelId}/prepare")
    public ResponseEntity<JsonNode> prepare(
            @PathVariable UUID modelId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Principal principal) {
        String actor = actor(principal);
        SemanticAdminClient.Result result = semantic.post(
                INTERNAL_ROOT + "/models/" + modelId + "/prepare",
                JsonNodeFactory.instance.objectNode(),
                actor,
                idempotencyKey);
        auditAccepted(result, actor, "semantic.model.prepare", "semantic_model", modelId.toString());
        return response(result);
    }

    /**
     * Ejecuta la operación {@code activate}.
     *
     * @param modelId Identificador de {@code model} utilizado por la operación.
     * @param body Cuerpo recibido por la solicitud.
     * @param idempotencyKey Valor de {@code idempotencyKey} utilizado por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code activate}.
     */
    @PostMapping("/api/admin/semantic/models/{modelId}/activate")
    public ResponseEntity<JsonNode> activate(
            @PathVariable UUID modelId,
            @RequestBody JsonNode body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Principal principal) {
        String actor = actor(principal);
        SemanticAdminClient.Result result = semantic.post(
                INTERNAL_ROOT + "/models/" + modelId + "/activate",
                body,
                actor,
                idempotencyKey);
        auditAccepted(
                result,
                actor,
                "rollback".equals(body.path("activationKind").asText())
                        ? "semantic.model.rollback"
                        : "semantic.model.activate",
                "semantic_model",
                modelId.toString());
        return response(result);
    }

    /**
     * Elimina el recurso solicitado mediante {@code deleteModel}.
     *
     * @param modelId Identificador de {@code model} utilizado por la operación.
     * @param idempotencyKey Valor de {@code idempotencyKey} utilizado por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code deleteModel}.
     */
    @DeleteMapping("/api/admin/semantic/models/{modelId}")
    public ResponseEntity<JsonNode> deleteModel(
            @PathVariable UUID modelId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Principal principal) {
        String actor = actor(principal);
        SemanticAdminClient.Result result = semantic.delete(
                INTERNAL_ROOT + "/models/" + modelId,
                actor,
                idempotencyKey);
        auditAccepted(result, actor, "semantic.model.delete", "semantic_model", modelId.toString());
        return response(result);
    }

    /**
     * Ejecuta la operación {@code operations}.
     *
     * @param limit Número máximo de elementos que se recuperarán.
     * @param active Valor de {@code active} utilizado por la operación.
     * @return Resultado producido por {@code operations}.
     */
    @GetMapping("/api/admin/semantic/operations")
    public ResponseEntity<JsonNode> operations(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "false") boolean active) {
        String path = UriComponentsBuilder.fromPath(INTERNAL_ROOT + "/operations")
                .queryParam("limit", limit)
                .queryParam("active", active)
                .build()
                .encode()
                .toUriString();
        return response(semantic.get(path));
    }

    /**
     * Ejecuta la operación {@code operation}.
     *
     * @param operationId Identificador de {@code operation} utilizado por la operación.
     * @return Resultado producido por {@code operation}.
     */
    @GetMapping("/api/admin/semantic/operations/{operationId}")
    public ResponseEntity<JsonNode> operation(@PathVariable UUID operationId) {
        return response(semantic.get(INTERNAL_ROOT + "/operations/" + operationId));
    }

    /**
     * Indica si puede realizarse la operación mediante {@code cancelOperation}.
     *
     * @param operationId Identificador de {@code operation} utilizado por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code cancelOperation}.
     */
    @DeleteMapping("/api/admin/semantic/operations/{operationId}")
    public ResponseEntity<JsonNode> cancelOperation(
            @PathVariable UUID operationId,
            Principal principal) {
        String actor = actor(principal);
        SemanticAdminClient.Result result = semantic.delete(
                INTERNAL_ROOT + "/operations/" + operationId,
                actor,
                null);
        auditAccepted(
                result,
                actor,
                "semantic.operation.cancel",
                "semantic_operation",
                operationId.toString());
        return response(result);
    }

    /**
     * Reintenta los elementos afectados mediante {@code retryOperation}.
     *
     * @param operationId Identificador de {@code operation} utilizado por la operación.
     * @param idempotencyKey Valor de {@code idempotencyKey} utilizado por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code retryOperation}.
     */
    @PostMapping("/api/admin/semantic/operations/{operationId}/retry")
    public ResponseEntity<JsonNode> retryOperation(
            @PathVariable UUID operationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Principal principal) {
        String actor = actor(principal);
        SemanticAdminClient.Result result = semantic.post(
                INTERNAL_ROOT + "/operations/" + operationId + "/retry",
                JsonNodeFactory.instance.objectNode(),
                actor,
                idempotencyKey);
        auditAccepted(
                result,
                actor,
                "semantic.operation.retry",
                "semantic_operation",
                operationId.toString());
        return response(result);
    }

    /**
     * Ejecuta la operación {@code auditAccepted}.
     *
     * @param result Resultado que debe procesarse.
     * @param actor Identidad del actor que solicita la operación.
     * @param action Valor de {@code action} utilizado por la operación.
     * @param targetType Valor de {@code targetType} utilizado por la operación.
     * @param targetId Identificador de {@code target} utilizado por la operación.
     */
    private void auditAccepted(
            SemanticAdminClient.Result result,
            String actor,
            String action,
            String targetType,
            String targetId) {
        if (result.status() >= 200 && result.status() < 300) {
            audit.record(
                    actor,
                    action,
                    targetType,
                    targetId,
                    Map.of(
                            "status", result.status(),
                            "operationId", result.body().path("operationId").asText("")));
        }
    }

    /**
     * Ejecuta la operación {@code response}.
     *
     * @param result Resultado que debe procesarse.
     * @return Resultado producido por {@code response}.
     */
    private ResponseEntity<JsonNode> response(SemanticAdminClient.Result result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /**
     * Ejecuta la operación {@code actor}.
     *
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code actor}.
     */
    private String actor(Principal principal) {
        return principal == null ? "admin" : principal.getName();
    }
}
