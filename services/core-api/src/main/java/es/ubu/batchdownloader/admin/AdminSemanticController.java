package es.ubu.batchdownloader.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * Actúa como fachada administrativa de modelos y operaciones semánticas, conservando actor,
 * idempotencia y estados HTTP y auditando solo solicitudes aceptadas.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.SemanticAdminClient
 * @see es.ubu.batchdownloader.admin.AdminAuditService
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración
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
     * Conecta el cliente semántico autenticado con el registro seguro de acciones administrativas.
     *
     * @param semantic Cliente interno que conserva actor e idempotencia y sanitiza errores de
     *     Semantic.
     * @param audit Registro de acciones con actor UUID y metadatos seguros sin URLs resueltas.
     */
    public AdminSemanticController(
            SemanticAdminClient semantic,
            AdminAuditService audit) {
        this.semantic = semantic;
        this.audit = audit;
    }

    /**
     * Consulta el resumen de disponibilidad, modelo activo y operaciones del servicio semántico.
     *
     * @return estado y JSON seguro devueltos por Semantic.
     */
    @GetMapping("/api/v1/admin/semantic/overview")
    public ResponseEntity<JsonNode> overview() {
        return response(semantic.get(INTERNAL_ROOT + "/overview"));
    }

    /**
     * Consulta el inventario de modelos registrado y sus estados de preparación y actividad.
     *
     * @return lista de modelos con el estado HTTP del servicio.
     */
    @GetMapping("/api/v1/admin/semantic/models")
    public ResponseEntity<JsonNode> models() {
        return response(semantic.get(INTERNAL_ROOT + "/models"));
    }

    /**
     * Consulta el estado administrativo de un modelo concreto por UUID.
     *
     * @param modelId UUID del modelo registrado en el servicio semántico.
     * @return detalle del modelo con el estado HTTP remoto.
     */
    @GetMapping("/api/v1/admin/semantic/models/{modelId}")
    public ResponseEntity<JsonNode> model(@PathVariable UUID modelId) {
        return response(semantic.get(INTERNAL_ROOT + "/models/" + modelId));
    }

    /**
     * Codifica el límite en la consulta interna del historial de benchmarks.
     *
     * @param limit Máximo solicitado de registros; el repositorio aplica el límite propio de cada
     *     consulta.
     * @return historial y estado HTTP del servicio semántico.
     */
    @GetMapping("/api/v1/admin/semantic/benchmarks")
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
     * Solicita un benchmark con actor e idempotencia y audita su operación solo si Semantic lo
     * acepta.
     *
     * @param body JSON del contrato interno correspondiente; Semantic o Scraper realiza su
     *     validación funcional.
     * @param idempotencyKey Clave opcional de idempotencia que se conserva al reenviar la mutación
     *     interna.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return respuesta de aceptación o rechazo del servicio.
     */
    @PostMapping("/api/v1/admin/semantic/benchmarks")
    public ResponseEntity<JsonNode> benchmark(
            @RequestBody JsonNode body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AccountPrincipal principal) {
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
     * Solicita la preparación persistente del modelo con actor e idempotencia y audita la
     * aceptación.
     *
     * @param modelId UUID del modelo registrado en el servicio semántico.
     * @param idempotencyKey Clave opcional de idempotencia que se conserva al reenviar la mutación
     *     interna.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return respuesta de la operación de preparación.
     */
    @PostMapping("/api/v1/admin/semantic/models/{modelId}/prepare")
    public ResponseEntity<JsonNode> prepare(
            @PathVariable UUID modelId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AccountPrincipal principal) {
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
     * Reenvía las condiciones de activación y distingue activate de rollback en auditoría, dejando
     * a Semantic comprobar integridad y modelo previo.
     *
     * @param modelId UUID del modelo registrado en el servicio semántico.
     * @param body JSON del contrato interno correspondiente; Semantic o Scraper realiza su
     *     validación funcional.
     * @param idempotencyKey Clave opcional de idempotencia que se conserva al reenviar la mutación
     *     interna.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return respuesta de activación o rechazo sin cambiar el contrato interno.
     */
    @PostMapping("/api/v1/admin/semantic/models/{modelId}/activate")
    public ResponseEntity<JsonNode> activate(
            @PathVariable UUID modelId,
            @RequestBody JsonNode body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AccountPrincipal principal) {
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
     * Solicita eliminación del modelo con actor e idempotencia y audita únicamente una respuesta
     * aceptada.
     *
     * @param modelId UUID del modelo registrado en el servicio semántico.
     * @param idempotencyKey Clave opcional de idempotencia que se conserva al reenviar la mutación
     *     interna.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return respuesta de la operación de borrado.
     */
    @DeleteMapping("/api/v1/admin/semantic/models/{modelId}")
    public ResponseEntity<JsonNode> deleteModel(
            @PathVariable UUID modelId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AccountPrincipal principal) {
        String actor = actor(principal);
        SemanticAdminClient.Result result = semantic.delete(
                INTERNAL_ROOT + "/models/" + modelId,
                actor,
                idempotencyKey);
        auditAccepted(result, actor, "semantic.model.delete", "semantic_model", modelId.toString());
        return response(result);
    }

    /**
     * Consulta operaciones con límite y filtro opcional de actividad codificados en la ruta
     * interna.
     *
     * @param limit Máximo solicitado de registros; el repositorio aplica el límite propio de cada
     *     consulta.
     * @param active true limita la consulta a operaciones semánticas todavía no terminales.
     * @return lista de operaciones con estado HTTP del servicio.
     */
    @GetMapping("/api/v1/admin/semantic/operations")
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
     * Recupera la operación persistida necesaria para seguir o recuperar un flujo administrativo.
     *
     * @param operationId UUID de la operación persistida de preparación, benchmark o activación.
     * @return estado y progreso de la operación.
     */
    @GetMapping("/api/v1/admin/semantic/operations/{operationId}")
    public ResponseEntity<JsonNode> operation(@PathVariable UUID operationId) {
        return response(semantic.get(INTERNAL_ROOT + "/operations/" + operationId));
    }

    /**
     * Solicita cancelación cooperativa de la operación e incluye el actor sin una clave nueva de
     * idempotencia.
     *
     * @param operationId UUID de la operación persistida de preparación, benchmark o activación.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return respuesta de cancelación, auditada solo si es aceptada.
     */
    @DeleteMapping("/api/v1/admin/semantic/operations/{operationId}")
    public ResponseEntity<JsonNode> cancelOperation(
            @PathVariable UUID operationId,
            @AuthenticationPrincipal AccountPrincipal principal) {
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
     * Solicita reintentar una operación bajo una clave de idempotencia y conserva en auditoría la
     * identidad original.
     *
     * @param operationId UUID de la operación persistida de preparación, benchmark o activación.
     * @param idempotencyKey Clave opcional de idempotencia que se conserva al reenviar la mutación
     *     interna.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return respuesta de reintento o rechazo.
     */
    @PostMapping("/api/v1/admin/semantic/operations/{operationId}/retry")
    public ResponseEntity<JsonNode> retryOperation(
            @PathVariable UUID operationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AccountPrincipal principal) {
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
     * Registra únicamente respuestas 2xx con estado y UUID de operación, sin copiar el cuerpo
     * completo a auditoría.
     *
     * @param result Estado y cuerpo seguro devueltos por el cliente administrativo de Semantic.
     * @param actor UUID textual de la cuenta administrativa responsable de la acción.
     * @param action Nombre estable de la acción auditada.
     * @param targetType Clase funcional del recurso auditado: aplicación, modelo, operación o
     *     ejecución.
     * @param targetId Identidad del recurso auditado, sin credenciales ni URLs privadas.
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
     * Conserva estado y cuerpo seguro del cliente interno en la respuesta administrativa.
     *
     * @param result Estado y cuerpo seguro devueltos por el cliente administrativo de Semantic.
     * @return respuesta HTTP con el mismo estado funcional.
     */
    private ResponseEntity<JsonNode> response(SemanticAdminClient.Result result) {
        return ResponseEntity.status(result.status()).body(result.body());
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
