package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogChangeEvent;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogStatsResponse;
import es.ubu.batchdownloader.catalog.CatalogDtos.LastScrapeRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Consulta contadores y última ejecución del scraper y produce versiones de invalidación para HTTP
 * y WebSocket.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.catalog.CatalogRepository
 * @see es.ubu.batchdownloader.catalog.CatalogChangeNotifier
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
     * Lee el singleton de contadores canónicos y añade última ejecución y fecha UTC de consulta.
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
        return new CatalogStatsResponse(snapshot.total(), filters, latestRun(), now());
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
     * Construye un evento catalog.changed con versión compuesta y fecha UTC actual.
     *
     * @return evento para invalidar las consultas del cliente.
     */
    public CatalogChangeEvent changeEvent() {
        return new CatalogChangeEvent("catalog.changed", changeVersion(), now());
    }

    /**
     * Combina cantidad y actualización de aplicaciones activas, contadores de catálogo y progreso
     * de la última ejecución en un hash textual.
     *
     * @return token opaco de cambio; no es una huella criptográfica.
     */
    public String changeVersion() {
        String appToken = jdbc.queryForObject(
                """
                SELECT CONCAT(COUNT(*), ':', COALESCE(UNIX_TIMESTAMP(MAX(updated_at)), 0))
                FROM software_apps
                WHERE app_status = 'active'
                """,
                String.class);
        String catalogToken = cacheVersion();
        List<String> runTokens = jdbc.query(
                """
                SELECT CONCAT(
                    HEX(id), ':', status, ':',
                    COALESCE(UNIX_TIMESTAMP(heartbeat_at), 0), ':',
                    apps_discovered, ':', apps_resolved, ':', apps_failed, ':',
                    apps_skipped, ':',
                    COALESCE(current_package_id, ''), ':',
                    COALESCE(current_phase, '')
                ) AS token
                FROM scrape_runs
                ORDER BY started_at DESC
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getString("token"));
        return Integer.toHexString(((appToken == null ? "" : appToken)
                + "|" + (catalogToken == null ? "" : catalogToken)
                + "|" + (runTokens.isEmpty() ? "" : runTokens.get(0))).hashCode());
    }

    /**
     * Selecciona la ejecución más reciente por fecha de inicio y proyecta fechas, contadores y
     * fase.
     *
     * @return última ejecución o null cuando no hay historial.
     */
    private LastScrapeRun latestRun() {
        List<LastScrapeRun> runs = jdbc.query(
                """
                SELECT status, started_at, heartbeat_at, finished_at,
                       apps_discovered, apps_resolved, apps_failed, apps_skipped,
                       current_package_id, current_app_name, current_phase
                FROM scrape_runs ORDER BY started_at DESC LIMIT 1
                """,
                (rs, rowNum) -> new LastScrapeRun(
                        rs.getString("status"),
                        rs.getTimestamp("started_at").toLocalDateTime(),
                        rs.getTimestamp("heartbeat_at").toLocalDateTime(),
                        nullableDate(rs, "finished_at"),
                        rs.getInt("apps_discovered"),
                        rs.getInt("apps_resolved"),
                        rs.getInt("apps_failed"),
                        rs.getInt("apps_skipped"),
                        rs.getString("current_package_id"),
                        rs.getString("current_app_name"),
                        rs.getString("current_phase")));
        return runs.isEmpty() ? null : runs.get(0);
    }

    /**
     * Lee una fecha SQL opcional sin convertir la ausencia en una fecha artificial.
     *
     * @param resultSet Fila SQL de la que se lee una fecha nullable.
     * @param column Nombre de una columna nullable que se interpreta con su tipo JDBC.
     * @return fecha y hora local del timestamp o null.
     * @throws java.sql.SQLException si falla la lectura de la columna.
     */
    private LocalDateTime nullableDate(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
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
