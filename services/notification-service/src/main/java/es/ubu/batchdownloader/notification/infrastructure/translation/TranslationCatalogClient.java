package es.ubu.batchdownloader.notification.infrastructure.translation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.notification.application.PermanentNotificationException;
import es.ubu.batchdownloader.notification.application.RetryableNotificationException;
import es.ubu.batchdownloader.notification.config.TranslationProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Consulta y cachea catálogos publicados por translation-service. */
@Component
public class TranslationCatalogClient {
    private static final TypeReference<Map<String, String>> CATALOG_TYPE = new TypeReference<>() {};

    private final TranslationProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Clock clock;
    private final Map<String, CachedCatalog> cache = new ConcurrentHashMap<>();

    @Autowired
    public TranslationCatalogClient(
            TranslationProperties properties, ObjectMapper mapper, Clock clock) {
        this(properties, mapper, clock, HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    TranslationCatalogClient(
            TranslationProperties properties, ObjectMapper mapper, Clock clock, HttpClient client) {
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        this.client = client;
    }

    /** Obtiene el catálogo solicitado con fallback a español para locales no publicados. */
    public Map<String, String> catalog(String requestedLocale) {
        String locale = normalizeLocale(requestedLocale);
        try {
            return exactCatalog(locale);
        } catch (UnsupportedLocaleException exception) {
            if (properties.fallbackLocale().equals(locale)) {
                throw new PermanentNotificationException("translation_locale_not_found");
            }
            return exactCatalog(properties.fallbackLocale());
        }
    }

    private Map<String, String> exactCatalog(String locale) {
        Instant now = clock.instant();
        CachedCatalog cached = cache.get(locale);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.messages();
        }

        URI endpoint = properties.baseUrl().resolve(
                "/api/v1/locales/" + URLEncoder.encode(locale, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.requestTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RetryableNotificationException("translation_interrupted", exception);
        } catch (IOException exception) {
            throw new RetryableNotificationException("translation_io_failure", exception);
        }
        int status = response.statusCode();
        if (status == 404) throw new UnsupportedLocaleException();
        if (status == 429 || status >= 500) {
            throw new RetryableNotificationException(
                    "translation_temporarily_unavailable", (java.time.Duration) null);
        }
        if (status < 200 || status >= 300) {
            throw new PermanentNotificationException("translation_request_rejected");
        }
        Map<String, String> messages;
        try {
            messages = Map.copyOf(mapper.readValue(response.body(), CATALOG_TYPE));
        } catch (RuntimeException | IOException exception) {
            throw new PermanentNotificationException("translation_catalog_invalid");
        }
        if (messages.isEmpty() || messages.entrySet().stream()
                .anyMatch(entry -> entry.getKey().isBlank() || entry.getValue() == null
                        || entry.getValue().isBlank())) {
            throw new PermanentNotificationException("translation_catalog_invalid");
        }
        cache.put(locale, new CachedCatalog(messages, clock.instant().plus(properties.cacheTtl())));
        return messages;
    }

    private static String normalizeLocale(String locale) {
        String value = locale == null || locale.isBlank() ? "es" : locale.strip();
        if (!value.matches("[A-Za-z]{2,12}(?:[-_][A-Za-z0-9]{2,12})*")) {
            throw new PermanentNotificationException("translation_locale_invalid");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private record CachedCatalog(Map<String, String> messages, Instant expiresAt) {}

    private static final class UnsupportedLocaleException extends RuntimeException {}
}
