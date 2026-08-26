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
 * Aísla las lecturas baratas de contadores, versiones y última ejecución del catálogo.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Repository
public class CatalogStatisticsRepository {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * Inicializa el repositorio de estadísticas.
     *
     * @param jdbc acceso JDBC compartido
     * @param clock reloj inyectado para respuestas deterministas
     */
    public CatalogStatisticsRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Obtiene los totales materializados y la última ejecución del scraper.
     *
     * @return estadísticas públicas del catálogo
     */
    public CatalogStatsResponse stats() {
        StatsSnapshot snapshot = jdbc.queryForObject("""
                SELECT total_apps, available_apps, review_apps, missing_installer_apps
                FROM application_totals
                """, (rs, rowNum) -> new StatsSnapshot(
                        rs.getLong("total_apps"),
                        rs.getLong("available_apps"),
                        rs.getLong("review_apps"),
                        rs.getLong("missing_installer_apps")));
        Map<String, Long> filters = new LinkedHashMap<>();
        filters.put("all", snapshot.total());
        filters.put("available", snapshot.available());
        filters.put("review", snapshot.review());
        filters.put("missing", snapshot.missing());
        return new CatalogStatsResponse(snapshot.total(), filters, latestRun(), now());
    }

    /**
     * Obtiene el token materializado usado para invalidar la caché pública.
     *
     * @return versión y contadores autoritativos
     */
    public String cacheVersion() {
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
     * Crea el evento canónico que anuncia un cambio observable del catálogo.
     *
     * @return evento versionado
     */
    public CatalogChangeEvent changeEvent() {
        return new CatalogChangeEvent("catalog.changed", changeVersion(), now());
    }

    /**
     * Combina proyección, contadores y estado del scraper en un token estable.
     *
     * @return hash barato del estado observable
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

    private LastScrapeRun latestRun() {
        List<LastScrapeRun> runs = jdbc.query(
                """
                SELECT * FROM scrape_runs ORDER BY started_at DESC LIMIT 1
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

    private LocalDateTime nullableDate(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record StatsSnapshot(long total, long available, long review, long missing) {}
}
