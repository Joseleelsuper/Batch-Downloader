package es.ubu.batchdownloader.identity.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/** Descarta las sesiones administrativas revocadas antes de autorizar una petición. */
final class AdminSessionVersionFilter extends OncePerRequestFilter {
    private final AdminSessionVersion adminSessions;

    AdminSessionVersionFilter(AdminSessionVersion adminSessions) {
        this.adminSessions = adminSessions;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getAuthorities().stream().anyMatch(role -> "ROLE_ADMIN".equals(role.getAuthority()))
                && !adminSessions.isCurrent(authentication)) {
            new SecurityContextLogoutHandler().logout(request, response, authentication);
        }
        chain.doFilter(request, response);
    }
}
