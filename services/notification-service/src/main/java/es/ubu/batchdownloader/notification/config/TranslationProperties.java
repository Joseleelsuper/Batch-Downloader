package es.ubu.batchdownloader.notification.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configura el cliente interno que consulta los catálogos de traducción. */
@ConfigurationProperties(prefix = "notification.translation")
public record TranslationProperties(
        URI baseUrl,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration cacheTtl,
        String fallbackLocale) {

    public TranslationProperties {
        baseUrl = Objects.requireNonNull(baseUrl, "notification.translation.base-url es obligatorio");
        if (!baseUrl.isAbsolute()) {
            throw new IllegalArgumentException("translation_base_url_must_be_absolute");
        }
        connectTimeout = requirePositive(connectTimeout, "notification.translation.connect-timeout");
        requestTimeout = requirePositive(requestTimeout, "notification.translation.request-timeout");
        cacheTtl = requirePositive(cacheTtl, "notification.translation.cache-ttl");
        fallbackLocale = requireLocale(fallbackLocale);
    }

    private static Duration requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " debe ser positivo");
        }
        return value;
    }

    private static String requireLocale(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("notification.translation.fallback-locale es obligatorio");
        }
        return value.strip().toLowerCase(java.util.Locale.ROOT);
    }
}
