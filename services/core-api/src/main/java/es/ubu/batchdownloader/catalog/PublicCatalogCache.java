package es.ubu.batchdownloader.catalog;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mantiene respuestas públicas breves sin convertir la caché en fuente de verdad.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
class PublicCatalogCache {
    /** Respuestas serializables del catálogo. */
    private final Cache<String, Object> responses;
    /** Indica si la caché está habilitada. */
    private final boolean enabled;
    /** Última versión consultada. */
    private volatile String cachedVersion;
    /** Momento monotónico en el que caduca la versión local. */
    private volatile long versionExpiresAt;

    /**
     * Inicializa la caché acotada.
     *
     * @param maximumSize Número máximo de claves.
     * @param ttl Vigencia máxima de una respuesta.
     */
    PublicCatalogCache(
            @Value("${app.catalog.cache-maximum-size}") long maximumSize,
            @Value("${app.catalog.cache-ttl}") Duration ttl) {
        this(maximumSize, ttl, true);
    }

    /** Constructor interno utilizado por las pruebas unitarias existentes. */
    private PublicCatalogCache(long maximumSize, Duration ttl, boolean enabled) {
        this.enabled = enabled;
        this.responses = Caffeine.newBuilder()
                .maximumSize(Math.max(0, maximumSize))
                .expireAfterWrite(ttl)
                .build();
    }

    /** Crea una fachada sin caché para pruebas unitarias puras. */
    static PublicCatalogCache disabled() {
        return new PublicCatalogCache(0, Duration.ZERO, false);
    }

    /**
     * Obtiene o calcula una respuesta asociada a la versión transaccional del catálogo.
     *
     * @param namespace Familia de la consulta.
     * @param versionSupplier Consulta ligera de versión.
     * @param arguments Argumentos normalizados.
     * @param loader Cálculo real.
     * @param <T> Tipo de respuesta.
     * @return Respuesta vigente.
     */
    @SuppressWarnings("unchecked")
    <T> T get(
            String namespace,
            Supplier<String> versionSupplier,
            List<?> arguments,
            Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }
        String key = namespace + '|' + version(versionSupplier) + '|'
                + arguments.stream().map(PublicCatalogCache::normalize)
                        .reduce("", (left, right) -> left + '\u001f' + right);
        return (T) responses.get(key, ignored -> Objects.requireNonNull(loader.get()));
    }

    /** Normaliza cadenas y colecciones para que el orden irrelevante no duplique claves. */
    private static String normalize(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Iterable<?> values) {
            return java.util.stream.StreamSupport.stream(values.spliterator(), false)
                    .map(PublicCatalogCache::normalize)
                    .sorted()
                    .reduce("", (left, right) -> left + '\u001e' + right);
        }
        if (value instanceof String text) {
            return text.strip().toLowerCase(Locale.ROOT);
        }
        return String.valueOf(value);
    }

    /** Conserva durante un segundo la consulta de versión para evitar sustituir una carga por otra. */
    private String version(Supplier<String> versionSupplier) {
        long now = System.nanoTime();
        String current = cachedVersion;
        if (current != null && now < versionExpiresAt) {
            return current;
        }
        synchronized (this) {
            now = System.nanoTime();
            if (cachedVersion == null || now >= versionExpiresAt) {
                cachedVersion = Objects.requireNonNullElse(versionSupplier.get(), "0");
                versionExpiresAt = now + Duration.ofSeconds(1).toNanos();
            }
            return cachedVersion;
        }
    }
}
