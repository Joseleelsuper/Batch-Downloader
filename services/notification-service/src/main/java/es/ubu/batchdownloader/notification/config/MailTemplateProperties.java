package es.ubu.batchdownloader.notification.config;

import java.net.URI;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.mail")
public record MailTemplateProperties(String from, String zoneId, URI publicBaseUrl) {

    public MailTemplateProperties {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("notification.mail.from no puede estar vacío");
        }
        from = from.strip();
        ZoneId.of(zoneId);
        publicBaseUrl = Objects.requireNonNull(
                publicBaseUrl, "notification.mail.public-base-url no puede ser null");
        if (!publicBaseUrl.isAbsolute()) {
            throw new IllegalArgumentException("notification.mail.public-base-url debe ser absoluta");
        }
    }

    public ZoneId resolvedZoneId() {
        return ZoneId.of(zoneId);
    }
}
