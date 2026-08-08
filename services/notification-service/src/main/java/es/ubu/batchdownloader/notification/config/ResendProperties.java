package es.ubu.batchdownloader.notification.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuración del adaptador HTTP de Resend. */
@ConfigurationProperties(prefix = "notification.resend")
public record ResendProperties(
        URI baseUrl,
        String apiKey,
        String from,
        Duration connectTimeout,
        Duration requestTimeout) {
    public ResendProperties {
        baseUrl = Objects.requireNonNull(baseUrl, "notification.resend.base-url es obligatorio");
        if (!baseUrl.isAbsolute()) throw new IllegalArgumentException("resend_base_url_must_be_absolute");
        apiKey = requireText(apiKey, "notification.resend.api-key");
        from = requireText(from, "notification.resend.from");
        connectTimeout = requirePositive(connectTimeout, "notification.resend.connect-timeout");
        requestTimeout = requirePositive(requestTimeout, "notification.resend.request-timeout");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " es obligatorio");
        return value.strip();
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " debe ser positivo");
        }
        return value;
    }
}
