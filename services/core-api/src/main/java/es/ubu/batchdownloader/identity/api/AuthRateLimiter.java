package es.ubu.batchdownloader.identity.api;

import es.ubu.batchdownloader.common.RateLimitException;
import es.ubu.batchdownloader.identity.application.port.AuthAttemptBudget;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Cuotas compartidas de autenticación sin persistir IP, nombres ni correos. */
@Component
class AuthRateLimiter {
    private final AuthAttemptBudget budget;
    private final byte[] secret;
    private final int loginLimit;
    private final int magicEmailLimit;
    private final int magicIpLimit;

    AuthRateLimiter(
            AuthAttemptBudget budget,
            @Value("${app.download.anonymous-owner-secret}") String secret,
            @Value("${app.auth.login-max-per-minute}") int loginLimit,
            @Value("${app.auth.magic-link-max-per-email-hour}") int magicEmailLimit,
            @Value("${app.auth.magic-link-max-per-ip-hour}") int magicIpLimit) {
        if (secret.isBlank()) throw new IllegalStateException("auth_budget_secret_missing");
        this.budget = budget;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.loginLimit = loginLimit;
        this.magicEmailLimit = magicEmailLimit;
        this.magicIpLimit = magicIpLimit;
    }

    void adminLogin(String ip, String username) {
        require("admin-login", normalizeIp(ip) + '\u0000' + normalizeIdentity(username), loginLimit, 60);
    }

    void magicLinkRequest(String ip, String email) {
        require("magic-ip", normalizeIp(ip), magicIpLimit, 3600);
        require("magic-email", normalizeIdentity(email), magicEmailLimit, 3600);
    }

    void magicLinkConfirmation(String ip) {
        require("magic-confirm", normalizeIp(ip), loginLimit, 60);
    }

    private void require(String scope, String identity, int limit, int retryAfterSeconds) {
        if (!budget.allow(scope, fingerprint(scope, identity), limit, Duration.ofSeconds(retryAfterSeconds))) {
            throw new RateLimitException(
                    "rate_limited",
                    "Se han realizado demasiadas solicitudes. Inténtalo más tarde.",
                    retryAfterSeconds);
        }
    }

    private String fingerprint(String scope, String identity) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("auth-budget\u0000" + scope + '\u0000' + identity)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC is unavailable", exception);
        }
    }

    private static String normalizeIp(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip.strip();
    }

    private static String normalizeIdentity(String identity) {
        return identity == null ? "" : identity.strip().toLowerCase(Locale.ROOT);
    }
}
