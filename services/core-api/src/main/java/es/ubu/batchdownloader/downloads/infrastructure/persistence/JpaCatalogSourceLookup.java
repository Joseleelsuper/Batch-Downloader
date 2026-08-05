package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import es.ubu.batchdownloader.downloads.application.port.CatalogSourceLookup;
import es.ubu.batchdownloader.common.UuidBytes;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Implementa el componente {@code JpaCatalogSourceLookup}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Repository
class JpaCatalogSourceLookup implements CatalogSourceLookup {
    /**
     * Constante que define {@code DEFAULT_OPERATING_SYSTEMS}.
     */
    private static final List<String> DEFAULT_OPERATING_SYSTEMS = List.of("windows", "linux", "macos");
    /**
     * Estado {@code jdbc} mantenido por {@code JpaCatalogSourceLookup}.
     */
    private final JdbcTemplate jdbc;

    /**
     * Inicializa una instancia de {@code JpaCatalogSourceLookup}.
     *
     * @param jdbc Valor de {@code jdbc} utilizado por la operación.
     */
    JpaCatalogSourceLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Busca el resultado solicitado mediante {@code findVerifiedSources}.
     *
     * @param appIds Colección de identificadores de {@code app}.
     * @param operatingSystems Valor de {@code operatingSystems} utilizado por la operación.
     * @return Mapa con los datos producidos por la operación.
     */
    @Override
    public Map<UUID, VerifiedSource> findVerifiedSources(
            Collection<UUID> appIds, List<String> operatingSystems) {
        if (appIds == null || appIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = appIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<String> systems = normalizedSystems(operatingSystems);
        StringBuilder sql = new StringBuilder("""
                SELECT software_app_id, source_ref, operating_system, architecture,
                       app_name, official_url
                FROM (
                    SELECT ds.software_app_id, rs.id AS source_ref,
                           ds.operating_system, ds.architecture,
                           app.name AS app_name, app.official_url,
                           ROW_NUMBER() OVER (
                               PARTITION BY ds.software_app_id
                               ORDER BY FIELD(ds.operating_system, 'windows', 'linux', 'macos') ASC,
                                        (JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.is_primary')) = 'true') DESC,
                                        rs.is_latest DESC,
                                        COALESCE(rs.release_rank, 2147483647) ASC,
                                        rs.score DESC,
                                        rs.checked_at DESC,
                                        rs.id ASC
                           ) AS source_rank
                    FROM software_apps app
                    JOIN download_sources ds ON ds.software_app_id = app.id
                    JOIN resolved_sources rs ON rs.download_source_id = ds.id
                    WHERE ds.software_app_id IN (
                """);
        appendPlaceholders(sql, ids.size());
        sql.append("""
                )
                  AND app.app_status = 'active'
                  AND app.catalog_status = 'available'
                  AND ds.resolution_status IN ('direct', 'fallback')
                  AND ds.validation_status = 'valid'
                  AND ds.catalog_available = 1
                  AND rs.catalog_downloadable = 1
                  AND ds.operating_system IN (
                """);
        appendPlaceholders(sql, systems.size());
        sql.append("""
                    )
                ) ranked_sources
                WHERE source_rank = 1
                ORDER BY software_app_id
                LIMIT 101
                """);
        List<Object> parameters = new ArrayList<>(ids.size() + systems.size());
        ids.forEach(id -> parameters.add(UuidBytes.fromUuid(id)));
        // La caducidad solo activa la revalidación JIT obligatoria del scraper; no
        // elimina del catálogo público un candidato que siga siendo válido.
        parameters.addAll(systems);
        Map<UUID, VerifiedSource> selected = new LinkedHashMap<>();
        jdbc.query(sql.toString(), (ResultSet row) -> {
            UUID appId = UuidBytes.toUuid(row.getBytes("software_app_id"));
            selected.putIfAbsent(appId, new VerifiedSource(
                    appId,
                    UuidBytes.toUuid(row.getBytes("source_ref")),
                    row.getString("operating_system"),
                    row.getString("architecture"),
                    row.getString("app_name"),
                    row.getString("official_url")));
        }, parameters.toArray());
        return Map.copyOf(selected);
    }

    /**
     * Normaliza el valor recibido mediante {@code normalizedSystems}.
     *
     * @param operatingSystems Valor de {@code operatingSystems} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    private static List<String> normalizedSystems(List<String> operatingSystems) {
        if (operatingSystems == null || operatingSystems.isEmpty()) {
            return DEFAULT_OPERATING_SYSTEMS;
        }
        // Mantiene estable la preferencia sin depender del orden en que el cliente HTTP
        // serialice los sistemas. El trabajo guarda un ejecutable por aplicación, así que
        // una solicitud con varios sistemas elige la primera plataforma verificada según
        // este orden canónico; el servicio omite aplicaciones sin esas plataformas.
        List<String> filtered = DEFAULT_OPERATING_SYSTEMS.stream()
                .filter(operatingSystems::contains)
                .toList();
        return filtered.isEmpty() ? DEFAULT_OPERATING_SYSTEMS : filtered;
    }

    /**
     * Ejecuta la operación {@code appendPlaceholders}.
     *
     * @param sql Valor de {@code sql} utilizado por la operación.
     * @param count Valor de {@code count} utilizado por la operación.
     */
    private static void appendPlaceholders(StringBuilder sql, int count) {
        for (int index = 0; index < count; index++) {
            if (index > 0) sql.append(", ");
            sql.append('?');
        }
    }
}
