package es.ubu.batchdownloader.catalog;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reutiliza respuestas por operación, filtros normalizados y versión persistida, acotando tanto
 * entradas como tiempo de vida.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.catalog.CatalogController
 * @see es.ubu.batchdownloader.catalog.CatalogStatisticsRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
@Component
class PublicCatalogCache {
    /** Respuestas serializables del catálogo. */
    private final Cache<String, Object> responses;
    /** Última versión consultada. */
    private volatile String cachedVersion;
    /** Momento monotónico en el que caduca la versión local. */
    private volatile long versionExpiresAt;

    /**
     * Crea la caché de respuestas con tamaño acotado y caducidad desde la escritura.
     *
     * @param maximumSize Máximo de respuestas conservadas; los valores negativos se limitan a cero.
     * @param ttl Duración de cada respuesta en caché desde su inserción.
     */
    @Autowired
    PublicCatalogCache(
            @Value("${app.catalog.cache-maximum-size}") long maximumSize,
            @Value("${app.catalog.cache-ttl}") Duration ttl) {
        this.responses = Caffeine.newBuilder()
                .maximumSize(Math.max(0, maximumSize))
                .expireAfterWrite(ttl)
                .build();
    }

    /**
     * Compone la clave con operación, versión y argumentos y calcula la respuesta únicamente cuando
     * falta en caché.
     *
     * @param namespace Grupo de respuestas apps, facets, details o stats para impedir colisiones
     *     entre contratos.
     * @param versionSupplier Consulta que obtiene la versión persistida del catálogo; se reutiliza
     *     durante un segundo.
     * @param arguments Argumentos de la operación en orden; sus colecciones se normalizan sin
     *     depender del orden interno.
     * @param loader Cálculo de la respuesta no nula cuando la clave todavía no está en caché.
     * @param <T> Tipo de respuesta asociado de forma estable al namespace.
     * @return respuesta no nula cargada o reutilizada.
     */
    @SuppressWarnings("unchecked")
    <T> T get(
            String namespace,
            Supplier<String> versionSupplier,
            List<?> arguments,
            Supplier<T> loader) {
        String key = namespace + '|' + version(versionSupplier) + '|'
                + arguments.stream().map(PublicCatalogCache::normalize)
                        .reduce("", (left, right) -> left + '\u001f' + right);
        return (T) responses.get(key, ignored -> Objects.requireNonNull(loader.get()));
    }

    /**
     * Normaliza textos sin mayúsculas ni espacios extremos y ordena los valores de colecciones para
     * compartir consultas equivalentes.
     *
     * @param value Texto que se normaliza o clasifica según el método.
     * @return representación de clave; null se convierte en cadena vacía.
     */
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

    /**
     * Consulta la versión como máximo una vez por segundo bajo doble comprobación y bloqueo local.
     *
     * @param versionSupplier Consulta que obtiene la versión persistida del catálogo; se reutiliza
     *     durante un segundo.
     * @return versión persistida o 0 si el proveedor devuelve null.
     */
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
