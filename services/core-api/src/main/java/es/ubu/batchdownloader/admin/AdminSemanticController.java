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

@RestController
public class AdminSemanticController {
    private static final String INTERNAL_ROOT = "/internal/v1/admin/semantic";

    private final SemanticAdminClient semantic;
    private final AdminAuditService audit;

    public AdminSemanticController(
            SemanticAdminClient semantic,
            AdminAuditService audit) {
        this.semantic = semantic;
        this.audit = audit;
    }

    @GetMapping("/api/admin/semantic/overview")
    public ResponseEntity<JsonNode> overview() {
        return response(semantic.get(INTERNAL_ROOT + "/overview"));
    }

    @GetMapping("/api/admin/semantic/models")
    public ResponseEntity<JsonNode> models() {
        return response(semantic.get(INTERNAL_ROOT + "/models"));
    }

    @GetMapping("/api/admin/semantic/models/{modelId}")
    public ResponseEntity<JsonNode> model(@PathVariable UUID modelId) {
        return response(semantic.get(INTERNAL_ROOT + "/models/" + modelId));
    }

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

    @GetMapping("/api/admin/semantic/hugging-face/models")
    public ResponseEntity<JsonNode> huggingFaceModels(
            @RequestParam String query,
            @RequestParam(defaultValue = "25") int limit) {
        String path = UriComponentsBuilder.fromPath(INTERNAL_ROOT + "/hugging-face/models")
                .queryParam("query", query)
                .queryParam("limit", limit)
                .build()
                .encode()
                .toUriString();
        return response(semantic.get(path));
    }

    @GetMapping("/api/admin/semantic/hugging-face/model")
    public ResponseEntity<JsonNode> huggingFaceModel(
            @RequestParam String repository,
            @RequestParam(required = false) String revision) {
        UriComponentsBuilder path = UriComponentsBuilder
                .fromPath(INTERNAL_ROOT + "/hugging-face/model")
                .queryParam("repository", repository);
        if (revision != null && !revision.isBlank()) {
            path.queryParam("revision", revision);
        }
        return response(semantic.get(path.build().encode().toUriString()));
    }

    @PostMapping("/api/admin/semantic/downloads")
    public ResponseEntity<JsonNode> download(
            @RequestBody JsonNode body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Principal principal) {
        String actor = actor(principal);
        SemanticAdminClient.Result result = semantic.post(
                INTERNAL_ROOT + "/downloads",
                body,
                actor,
                idempotencyKey);
        auditAccepted(
                result,
                actor,
                "semantic.model.download",
                "semantic_model",
                body.path("repository").asText("unknown"));
        return response(result);
    }

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

    @GetMapping("/api/admin/semantic/operations/{operationId}")
    public ResponseEntity<JsonNode> operation(@PathVariable UUID operationId) {
        return response(semantic.get(INTERNAL_ROOT + "/operations/" + operationId));
    }

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

    private ResponseEntity<JsonNode> response(SemanticAdminClient.Result result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }

    private String actor(Principal principal) {
        return principal == null ? "admin" : principal.getName();
    }
}
