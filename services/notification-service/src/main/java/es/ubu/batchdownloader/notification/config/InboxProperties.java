package es.ubu.batchdownloader.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.inbox")
public record InboxProperties(Duration leaseDuration) {

    public InboxProperties {
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("notification.inbox.lease-duration debe ser positivo");
        }
    }
}
