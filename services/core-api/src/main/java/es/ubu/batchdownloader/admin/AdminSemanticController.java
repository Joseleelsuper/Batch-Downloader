package es.ubu.batchdownloader.admin;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Expone el único diagnóstico semántico que necesita la administración. */
@RestController
public class AdminSemanticController {
    private final SemanticStatusClient semantic;

    public AdminSemanticController(SemanticStatusClient semantic) {
        this.semantic = semantic;
    }

    @GetMapping("/api/v1/admin/semantic/overview")
    public ResponseEntity<JsonNode> overview() {
        SemanticStatusClient.Result result = semantic.get();
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
