package es.ubu.batchdownloader.admin;

import com.fasterxml.jackson.databind.JsonNode;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fachada protegida por la política /api/v1/admin/** del servicio Core. */
@RestController
@RequestMapping("/api/v1/admin/apps/{appId}/linux")
public class LinuxInstallAdminController {
    private final ScraperInternalClient scraper;
    private final AdminAuditService audit;

    public LinuxInstallAdminController(ScraperInternalClient scraper, AdminAuditService audit) {
        this.scraper = scraper;
        this.audit = audit;
    }

    @GetMapping("/sources/{sourceRef}/profile")
    public JsonNode profile(@PathVariable UUID appId, @PathVariable UUID sourceRef) {
        return scraper.linuxProfile(appId, sourceRef, null);
    }

    @PutMapping("/sources/{sourceRef}/profile")
    public JsonNode saveProfile(@PathVariable UUID appId, @PathVariable UUID sourceRef,
            @RequestBody JsonNode body, @AuthenticationPrincipal AccountPrincipal principal) {
        JsonNode result = scraper.linuxProfile(appId, sourceRef, body);
        audit.record(principal.getUsername(), "app.linux.profile", "source", sourceRef.toString(),
                Map.of("appId", appId.toString(), "version", result.path("version").asLong()));
        return result;
    }

    @GetMapping("/dependencies")
    public JsonNode dependencies(@PathVariable UUID appId) {
        return scraper.linuxDependencies(appId, null);
    }

    @PutMapping("/dependencies")
    public JsonNode saveDependencies(@PathVariable UUID appId, @RequestBody JsonNode body,
            @AuthenticationPrincipal AccountPrincipal principal) {
        JsonNode result = scraper.linuxDependencies(appId, body);
        audit.record(principal.getUsername(), "app.linux.dependencies", "app", appId.toString(),
                Map.of("version", result.path("version").asLong()));
        return result;
    }
}
