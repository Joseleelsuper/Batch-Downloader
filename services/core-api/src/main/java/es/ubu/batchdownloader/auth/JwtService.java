package es.ubu.batchdownloader.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final byte[] secret;
    private final long ttlSeconds;

    public JwtService(
            @Value("${app.auth.jwt-secret}") String jwtSecret,
            @Value("${app.auth.jwt-ttl-seconds}") long ttlSeconds) {
        this.secret = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public String issueAdminToken(String username) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = Map.of(
                "sub", username,
                "role", "ADMIN",
                "iat", now,
                "exp", now + ttlSeconds);
        String unsigned = base64Json(header) + "." + base64Json(payload);
        return unsigned + "." + sign(unsigned);
    }

    public Map<String, Object> verify(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("invalid_token");
        }
        String unsigned = parts[0] + "." + parts[1];
        if (!constantTimeEquals(parts[2], sign(unsigned))) {
            throw new IllegalArgumentException("invalid_signature");
        }
        try {
            Map<String, Object> payload = MAPPER.readValue(
                    Base64.getUrlDecoder().decode(parts[1]),
                    new TypeReference<>() {});
            Number exp = (Number) payload.get("exp");
            if (exp == null || exp.longValue() < Instant.now().getEpochSecond()) {
                throw new IllegalArgumentException("expired_token");
            }
            return payload;
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid_payload", exception);
        }
    }

    private String base64Json(Map<String, Object> value) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MAPPER.writeValueAsBytes(value));
        } catch (Exception exception) {
            throw new IllegalStateException("jwt_json_failed", exception);
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("jwt_sign_failed", exception);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left.length() != right.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < left.length(); i++) {
            result |= left.charAt(i) ^ right.charAt(i);
        }
        return result == 0;
    }
}
