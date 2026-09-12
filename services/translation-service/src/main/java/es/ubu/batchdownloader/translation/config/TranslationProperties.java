package es.ubu.batchdownloader.translation.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Define el directorio de páginas de traducción y la duración de la caché HTTP del catálogo.
 *
 * @param localesPath Directorio raíz que contiene las páginas template y es.
 * @param cacheMaxAge Duración positiva durante la que una respuesta puede permanecer en caché.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.translation.infrastructure.file.JsonFileLocaleCatalog
 * @see es.ubu.batchdownloader.translation.infrastructure.web.LocaleController
 * @since 0.1.0
 * @version 0.1.0
 * @category Traducciones
 */
@ConfigurationProperties(prefix = "translation")
public record TranslationProperties(Path localesPath, Duration cacheMaxAge) {

    /**
     * Normaliza la raíz a una ruta absoluta y exige una duración de caché estrictamente positiva.
     *
     * @param localesPath Directorio raíz que contiene las páginas template y es.
     * @param cacheMaxAge Duración positiva durante la que una respuesta puede permanecer en caché.
     * @throws NullPointerException si no se proporciona la raíz de catálogos.
     * @throws IllegalArgumentException si la duración de caché falta, es cero o negativa.
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
