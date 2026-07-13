package es.ubu.batchdownloader.translation.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "translation")
public record TranslationProperties(Path localesPath, Duration cacheMaxAge) {

    public TranslationProperties {
        localesPath = Objects.requireNonNull(localesPath, "translation.locales-path no puede ser null")
                .toAbsolutePath()
                .normalize();
        if (cacheMaxAge == null || cacheMaxAge.isNegative() || cacheMaxAge.isZero()) {
            throw new IllegalArgumentException("translation.cache-max-age debe ser positivo");
        }
    }
}
