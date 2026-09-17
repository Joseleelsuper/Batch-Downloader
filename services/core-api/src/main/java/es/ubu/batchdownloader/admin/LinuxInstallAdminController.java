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

/**
 * Expone lectura y edición administrativa de perfiles y dependencias Linux y audita las versiones
 * guardadas por el scraper.
 *
 * @see es.ubu.batchdownloader.admin.ScraperInternalClient
 * @see es.ubu.batchdownloader.admin.AdminAuditService
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración
 */
@RestController
@RequestMapping("/api/v1/admin/apps/{appId}/linux")
public class LinuxInstallAdminController {
    private static final String VERSION_FIELD = "version";

    private final ScraperInternalClient scraper;
    private final AdminAuditService audit;

    /**
     * Conecta lectura y escritura explícitas del contrato Linux con su auditoría.
     *
     * @param scraper Consultas y mantenimiento de las colas del scraper, o su cliente HTTP según la
     *     firma.
     * @param audit Registro de acciones con actor UUID y metadatos seguros sin URLs resueltas.
     */
    public LinuxInstallAdminController(ScraperInternalClient scraper, AdminAuditService audit) {
        this.scraper = scraper;
        this.audit = audit;
    }

    /**
     * Consulta el perfil Linux de la fuente exacta dentro de su aplicación.
     *
     * @param appId UUID textual o identificador público de la aplicación; las rutas internas
     *     requieren UUID.
     * @param sourceRef UUID de la fuente resuelta exacta cuyo perfil Linux se consulta o modifica.
     * @return perfil y versión comunicados por el scraper.
     */
    @GetMapping("/sources/{sourceRef}/profile")
    public JsonNode profile(@PathVariable UUID appId, @PathVariable UUID sourceRef) {
        return scraper.readLinuxProfile(appId, sourceRef);
    }

    /**
     * Guarda el perfil de la fuente mediante PUT y audita aplicación, fuente y versión resultante.
     *
     * @param appId UUID textual o identificador público de la aplicación; las rutas internas
     *     requieren UUID.
     * @param sourceRef UUID de la fuente resuelta exacta cuyo perfil Linux se consulta o modifica.
     * @param body JSON del contrato interno correspondiente; Semantic o Scraper realiza su
     *     validación funcional.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return perfil guardado con su versión.
     */
    @PutMapping("/sources/{sourceRef}/profile")
    public JsonNode saveProfile(@PathVariable UUID appId, @PathVariable UUID sourceRef,
            @RequestBody JsonNode body, @AuthenticationPrincipal AccountPrincipal principal) {
        JsonNode result = scraper.writeLinuxProfile(appId, sourceRef, body);
        audit.record(principal.getUsername(), "app.linux.profile", "source", sourceRef.toString(),
                Map.of("appId", appId.toString(), VERSION_FIELD, result.path(VERSION_FIELD).asLong()));
        return result;
    }

    /**
     * Consulta la selección vigente de dependencias Linux de una aplicación.
     *
     * @param appId UUID textual o identificador público de la aplicación; las rutas internas
     *     requieren UUID.
     * @return dependencias y versión comunicadas por el scraper.
     */
    @GetMapping("/dependencies")
    public JsonNode dependencies(@PathVariable UUID appId) {
        return scraper.readLinuxDependencies(appId);
    }

    /**
     * Sustituye dependencias mediante PUT y audita el UUID de aplicación y la versión guardada.
     *
     * @param appId UUID textual o identificador público de la aplicación; las rutas internas
     *     requieren UUID.
     * @param body JSON del contrato interno correspondiente; Semantic o Scraper realiza su
     *     validación funcional.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return dependencias guardadas con su versión.
     */
    @PutMapping("/dependencies")
    public JsonNode saveDependencies(@PathVariable UUID appId, @RequestBody JsonNode body,
            @AuthenticationPrincipal AccountPrincipal principal) {
        JsonNode result = scraper.writeLinuxDependencies(appId, body);
        audit.record(principal.getUsername(), "app.linux.dependencies", "app", appId.toString(),
                Map.of(VERSION_FIELD, result.path(VERSION_FIELD).asLong()));
        return result;
    }
}
