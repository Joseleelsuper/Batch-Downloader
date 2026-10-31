package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogStatsResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Consulta contadores del catálogo y produce versiones de invalidación para el catálogo.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.catalog.CatalogRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
@Repository
public class CatalogStatisticsRepository {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * Conecta las proyecciones estadísticas persistidas con el reloj UTC del servicio.
     *
     * @param jdbc Acceso SQL a catálogo, fuentes y proyecciones persistidas en MySQL.
     * @param clock Reloj que determina la fecha UTC de estadísticas y eventos.
     */
    public CatalogStatisticsRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Lee el singleton de contadores canónicos y añade la fecha UTC de consulta.
     *
     * @return estadísticas públicas sin contar de nuevo cada aplicación.
     */
    public CatalogStatsResponse stats() {
        ensureCounterRow();
        StatsSnapshot snapshot = jdbc.queryForObject("""
                SELECT total_count, available_count, review_count, missing_count
                FROM catalog_counters
                WHERE id = 1
                """, (rs, rowNum) -> new StatsSnapshot(
                        rs.getLong("total_count"),
                        rs.getLong("available_count"),
                        rs.getLong("review_count"),
                        rs.getLong("missing_count")));
        Map<String, Long> filters = new LinkedHashMap<>();
        filters.put("all", snapshot.total());
        filters.put("available", snapshot.available());
        filters.put("review", snapshot.review());
        filters.put("missing", snapshot.missing());
        return new CatalogStatsResponse(snapshot.total(), filters, now());
    }

    /**
     * Combina la versión persistida de catalog_counters con sus cuatro recuentos para invalidar
     * respuestas del catálogo.
     *
     * @return token textual de versión y cantidades.
     */
    public String cacheVersion() {
        ensureCounterRow();
        return jdbc.queryForObject(
                """
                SELECT CONCAT(version, ':', total_count, ':', available_count, ':', review_count, ':', missing_count)
                FROM catalog_counters
                WHERE id = ?
                """,
                String.class,
                1);
    }

    /**
     * Restaura el singleton vacío después de una limpieza de datos que conserve el esquema.
     *
     * <p>Las migraciones lo crean en instalaciones normales, pero el catálogo debe seguir
     * sirviendo respuestas vacías si se eliminan sus filas operativas durante un reinicio.
     */
    private void ensureCounterRow() {
        int inserted = jdbc.update("""
                INSERT IGNORE INTO catalog_counters (
                    id, total_count, available_count, review_count, missing_count, version, updated_at
                ) VALUES (1, 0, 0, 0, 0, 0, UTC_TIMESTAMP(6))
                """);
        if (inserted > 0) {
            jdbc.update("""
                    UPDATE catalog_counters
                    SET total_count = (SELECT COUNT(*) FROM software_apps),
                        available_count = (
                            SELECT COALESCE(SUM(catalog_status = 'available'), 0)
                            FROM software_apps
                        ),
                        review_count = (
                            SELECT COALESCE(SUM(catalog_status = 'review'), 0)
                            FROM software_apps
                        ),
                        missing_count = (
                            SELECT COALESCE(SUM(catalog_status = 'missing'), 0)
                            FROM software_apps
                        ),
                        version = version + 1,
                        updated_at = UTC_TIMESTAMP(6)
                    WHERE id = 1
                    """);
        }
    }

    /**
     * Convierte el instante del reloj inyectado a fecha y hora local de la zona UTC.
     *
     * @return fecha UTC sin dependencia de la zona del servidor.
     */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * Recoge los cuatro contadores del singleton catalog_counters en una sola lectura.
     *
     * @param total Número de aplicaciones que cumplen el conjunto completo de filtros antes de
     *     paginar.
     * @param available Aplicaciones con instalador seleccionable.
     * @param review Aplicaciones cuyo instalador requiere revisión.
     * @param missing Aplicaciones sin instalador disponible.
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
    private record StatsSnapshot(long total, long available, long review, long missing) {}
}
