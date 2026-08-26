package es.ubu.batchdownloader.translation.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Representa los datos inmutables de {@code TranslationProperties}.
 *
 * @param localesPath Valor de {@code localesPath} incluido en el record.
 * @param cacheMaxAge Valor de {@code cacheMaxAge} incluido en el record.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@ConfigurationProperties(prefix = "translation")
public record TranslationProperties(Path localesPath, Duration cacheMaxAge) {

    /**
     * Inicializa una instancia de {@code TranslationProperties}.
     *
     * @param localesPath Valor de {@code localesPath} utilizado por la operación.
     * @param cacheMaxAge Valor de {@code cacheMaxAge} utilizado por la operación.
     * @throws IllegalArgumentException Si los argumentos recibidos no cumplen las restricciones
     *     requeridas.
     */
    public TranslationProperties {
        localesPath = Objects.requireNonNull(localesPath, "translation.locales-path no puede ser null")
                .toAbsolutePath()
                .normalize();
        if (cacheMaxAge == null || cacheMaxAge.isNegative() || cacheMaxAge.isZero()) {
            throw new IllegalArgumentException("translation.cache-max-age debe ser positivo");
        }
    }
}
