package es.ubu.batchdownloader.auth;

import es.ubu.batchdownloader.admin.AdminAuditService;
import es.ubu.batchdownloader.common.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final String adminUsername;
    private final String adminPasswordHash;
    private final boolean cookieSecure;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AdminAuditService audit;

    public AuthController(
            @Value("${app.auth.admin-username}") String adminUsername,
            @Value("${app.auth.admin-password-hash}") String adminPasswordHash,
            @Value("${app.cookie-secure}") boolean cookieSecure,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AdminAuditService audit) {
        this.adminUsername = adminUsername;
        this.adminPasswordHash = adminPasswordHash;
        this.cookieSecure = cookieSecure;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.audit = audit;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        if (!adminUsername.equals(request.username()) || !passwordEncoder.matches(request.password(), adminPasswordHash)) {
            audit.record(request.username(), "auth.login_failed", "auth", null, Map.of());
            return ResponseEntity.status(401).body(ApiError.of("invalid_credentials", "Credenciales incorrectas."));
        }
        String token = jwtService.issueAdminToken(adminUsername);
        audit.record(adminUsername, "auth.login", "auth", null, Map.of());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookie(token, jwtService.ttlSeconds()).toString())
                .body(new AuthUser(adminUsername, "ADMIN"));
    }

    @PostMapping("/logout")
    public ResponseEntity<AuthUser> logout(Authentication authentication, HttpServletResponse response) {
        String actor = authentication == null ? "anonymous" : authentication.getName();
        audit.record(actor, "auth.logout", "auth", null, Map.of());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookie("", 0).toString());
        return ResponseEntity.ok(new AuthUser(actor, "ANONYMOUS"));
    }

    @GetMapping("/me")
    public AuthUser me(Authentication authentication) {
        return new AuthUser(authentication.getName(), "ADMIN");
    }

    private ResponseCookie authCookie(String token, long maxAgeSeconds) {
        return ResponseCookie.from(JwtAuthenticationFilter.COOKIE_NAME, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record AuthUser(String username, String role) {}
}
