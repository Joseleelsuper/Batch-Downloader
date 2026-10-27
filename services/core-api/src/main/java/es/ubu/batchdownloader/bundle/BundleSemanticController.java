package es.ubu.batchdownloader.bundle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Proyecta únicamente metadatos propios de bundles públicos al indexador autenticado. */
@RestController
@RequestMapping("/internal/v1/semantic/bundles")
public class BundleSemanticController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final byte[] token;

    public BundleSemanticController(JdbcTemplate jdbc, ObjectMapper mapper,
            @Value("${app.scraper-internal-service-token}") String token) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/source-status")
    public Map<String, Long> status(@RequestHeader(value="X-Internal-Service-Token", required=false) String supplied) {
        authorize(supplied);
        return Map.of("revision", jdbc.queryForObject("SELECT revision FROM bundle_semantic_source_state WHERE id=1", Long.class));
    }

    @GetMapping("/documents")
    public Map<String, Object> documents(@RequestHeader(value="X-Internal-Service-Token", required=false) String supplied,
            @RequestParam(required=false) UUID afterBundleId, @RequestParam(defaultValue="500") int limit) {
        authorize(supplied);
        int size = Math.max(1, Math.min(limit, 500));
        byte[] after = afterBundleId == null ? null : UuidBytes.fromUuid(afterBundleId);
        List<Map<String, Object>> docs = jdbc.query("""
                SELECT b.id, b.name, b.description,
                  COALESCE((SELECT JSON_ARRAYAGG(t.tag) FROM bundle_tags t WHERE t.bundle_id=b.id),JSON_ARRAY()) AS tags
                FROM bundles b WHERE b.visibility IN ('public','official') AND (? IS NULL OR b.id>?)
                ORDER BY b.id LIMIT ?
                """, (rs, n) -> {
                    try {
                        List<String> tags = mapper.readValue(rs.getString("tags"), new TypeReference<List<String>>() {});
                        String content = "Name: " + rs.getString("name") + "\nDescription: "
                                + java.util.Objects.toString(rs.getString("description"), "")
                                + "\nTags: " + String.join(", ", tags.stream().sorted().toList());
                        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
                        return Map.<String, Object>of("bundleId", UuidBytes.toUuid(rs.getBytes("id")).toString(),
                                "content", content, "contentHash", hash);
                    } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
                        throw new IllegalStateException("bundle_semantic_document_invalid", e);
                    }
                }, after, after, size);
        Map<String, Object> page = new java.util.LinkedHashMap<>();
        page.put("documents", docs);
        page.put("nextAfterBundleId", docs.size() == size ? docs.getLast().get("bundleId") : null);
        return page;
    }

    private void authorize(String supplied) {
        if (token.length == 0 || supplied == null || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8)))
            throw new UnauthorizedException("internal_service_token_invalid", "La credencial interna no es válida.");
    }
}
