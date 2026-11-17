package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.identity.domain.UserRole;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/** Audita mutaciones rechazadas en MVC sin copiar URI real, query, cuerpo ni excepción. */
final class AdminFailureAuditInterceptor implements HandlerInterceptor {
    private static final Set<String> MUTATIONS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final Supplier<AdminAuditService> audit;

    AdminFailureAuditInterceptor(Supplier<AdminAuditService> audit) { this.audit = audit; }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        int status = response.getStatus();
        if (exception != null && status < 400 && !response.isCommitted()) status = 500;
        if ((status < 400 && exception == null) || !MUTATIONS.contains(request.getMethod())) return;
        if (!(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String route)
                || !route.startsWith("/api/v1/admin/")) return;
        if (!(request.getUserPrincipal() instanceof Authentication authentication) || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AccountPrincipal account) || account.role() != UserRole.ADMIN) return;
        audit.get().record(account.userId().toString(), "admin.request.failed", "admin-route", target(request),
                Map.of("outcome", "failure", "method", request.getMethod(), "status", status, "route", route));
    }

    /** Solo un UUID canónico puede convertirse en objetivo; otros identificadores no se copian. */
    private static String target(HttpServletRequest request) {
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables) {
            for (String name : List.of("sourceId", "bundleId", "requestId", "jobId", "appId", "id")) {
                if (variables.get(name) instanceof String value) {
                    try {
                        String canonical = UUID.fromString(value).toString();
                        if (canonical.equalsIgnoreCase(value)) return canonical;
                    } catch (IllegalArgumentException ignored) { /* No conservar texto arbitrario de la ruta. */ }
                }
            }
        }
        return null;
    }
}
