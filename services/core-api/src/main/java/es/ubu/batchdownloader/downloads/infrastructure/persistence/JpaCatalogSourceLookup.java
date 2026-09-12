package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import es.ubu.batchdownloader.downloads.application.port.CatalogSourceLookup;
import es.ubu.batchdownloader.common.UuidBytes;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Selecciona fuentes descargables mediante consultas por lotes, con orden estable, elección exacta
 * y compatibilidad Linux.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.application.port.CatalogSourceLookup
 * @see es.ubu.batchdownloader.downloads.application.DownloadSelection
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
@Repository
class JpaCatalogSourceLookup implements CatalogSourceLookup {
    /**
     * Recorre dependencias por niveles, conserva primero la selección y evita ciclos mediante UUID
     * ya visitados.
     *
     * @param appIds Selección de UUID de aplicaciones en el orden solicitado.
     * @return selección seguida de dependencias nuevas, sin duplicados.
     * @throws es.ubu.batchdownloader.common.BadRequestException si el lote expandido supera cien
     *     aplicaciones.
     */
    @Override
    public List<UUID> expandLinuxDependencies(Collection<UUID> appIds) {
        var all = new java.util.LinkedHashSet<>(appIds);
        var pending = new ArrayList<>(all);
        while (!pending.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(pending.size(), "?"));
            List<UUID> found = jdbc.query(
                    "SELECT dependency_app_id FROM software_app_dependencies WHERE app_id IN ("
                            + placeholders + ") ORDER BY dependency_app_id",
                    (row, index) -> UuidBytes.toUuid(row.getBytes(1)),
                    pending.stream().map(UuidBytes::fromUuid).toArray());
            pending = new ArrayList<>();
            for (UUID dependency : found) if (all.add(dependency)) pending.add(dependency);
            if (all.size() > 100) throw new es.ubu.batchdownloader.common.BadRequestException(
                    "linux_dependency_limit", "El lote y sus dependencias superan 100 aplicaciones.");
        }
        return List.copyOf(all);
    }

    /**
     * Filtra fuentes descargables por extensión, arquitectura y perfil aprobado del destino; una
     * fuente exacta limita solo la primera aplicación.
     * Prioriza paquetes nativos, AppImage, arquitectura exacta y después versión, rango,
     * puntuación, fecha e identidad.
     *
     * @param appIds Selección de UUID de aplicaciones en el orden solicitado.
     * @param target Gestor Linux seleccionado o contexto validado del destino según la firma.
     * @param exactSource Fuente obligatoria para la primera aplicación del lote; null permite
     *     elección automática.
     * @return una fuente compatible por aplicación; nunca sustituye una fuente exacta incompatible.
     */
    @Override
    public Map<UUID, VerifiedSource> findLinuxSources(Collection<UUID> appIds,
            es.ubu.batchdownloader.downloads.application.LinuxTarget target, UUID exactSource) {
        if (appIds.isEmpty()) return Map.of();
        var ids = List.copyOf(appIds);
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String extensions = String.join(",", java.util.Collections.nCopies(target.extensions().size(), "?"));
        String sql = """
                SELECT ds.software_app_id, rs.id AS source_ref, ds.architecture,
                       app.name AS app_name, app.official_url, rs.extension,
                       JSON_UNQUOTE(JSON_EXTRACT(lip.profile_json, '$.strategy')) AS linux_strategy
                FROM software_apps app
                JOIN download_sources ds ON ds.software_app_id = app.id
                JOIN resolved_sources rs ON rs.download_source_id = ds.id
                LEFT JOIN linux_install_profiles lip ON lip.source_ref = rs.id AND lip.status = 'approved'
                WHERE app.app_status = 'active' AND app.catalog_status = 'available'
                  AND ds.resolution_status IN ('direct', 'fallback')
                  AND ds.validation_status = 'valid' AND ds.catalog_available = 1
                  AND rs.catalog_downloadable = 1 AND ds.operating_system = 'linux'
                  AND ds.software_app_id IN (%s) AND LOWER(rs.extension) IN (%s)
                  AND ds.architecture IN (?, 'any', 'all', 'noarch', 'universal', 'unknown')
                  AND (lip.source_ref IS NULL
                       OR COALESCE(JSON_LENGTH(JSON_EXTRACT(lip.profile_json, '$.linuxTargets')), 0) = 0
                       OR JSON_CONTAINS(JSON_EXTRACT(lip.profile_json, '$.linuxTargets'), JSON_QUOTE(?)))
                  AND (? IS NULL OR ds.software_app_id <> ? OR rs.id = ?)
                ORDER BY ds.software_app_id,
                         CASE WHEN LOWER(rs.extension) IN ('.deb', '.rpm', '.pkg.tar.zst') THEN 0
                              WHEN LOWER(rs.extension) = '.appimage' THEN 1 ELSE 2 END,
                         (ds.architecture = ?) DESC, rs.is_latest DESC,
                         COALESCE(rs.release_rank, 2147483647), rs.score DESC, rs.checked_at DESC, rs.id
                """.formatted(placeholders, extensions);
        List<Object> parameters = new ArrayList<>();
        ids.forEach(id -> parameters.add(UuidBytes.fromUuid(id)));
        parameters.addAll(target.extensions());
        parameters.add(target.architecture());
        parameters.add(target.manager());
        parameters.add(exactSource == null ? null : UuidBytes.fromUuid(exactSource));
        parameters.add(UuidBytes.fromUuid(ids.getFirst()));
        parameters.add(exactSource == null ? null : UuidBytes.fromUuid(exactSource));
        parameters.add(target.architecture());
        Map<UUID, VerifiedSource> result = new LinkedHashMap<>();
        jdbc.query(sql, (ResultSet row) -> {
            UUID appId = UuidBytes.toUuid(row.getBytes("software_app_id"));
            result.putIfAbsent(appId, new VerifiedSource(appId,
                    UuidBytes.toUuid(row.getBytes("source_ref")), "linux",
                    row.getString("architecture"), row.getString("app_name"), row.getString("official_url"),
                    es.ubu.batchdownloader.catalog.LinuxInstallationSupport.support("linux",
                            row.getString("extension"), row.getString("linux_strategy"))));
        }, parameters.toArray());
        return Map.copyOf(result);
    }
    /**
     * Constante que define {@code DEFAULT_OPERATING_SYSTEMS}.
     */
    private static final List<String> DEFAULT_OPERATING_SYSTEMS = List.of("windows", "linux", "macos");
    /**
     * Estado {@code jdbc} mantenido por {@code JpaCatalogSourceLookup}.
     */
    private final JdbcTemplate jdbc;

    /**
     * Conecta el acceso SQL al catálogo y sus perfiles Linux dentro de la transacción del llamador.
     *
     * @param jdbc Acceso SQL que participa en la transacción de Spring del llamador.
     */
    JpaCatalogSourceLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Selecciona una fuente descargable por aplicación con preferencia estable de plataforma y
     * versión; una comprobación caducada requiere revalidación posterior y no excluye por sí sola
     * la fuente.
     *
     * @param appIds Selección de UUID de aplicaciones en el orden solicitado.
     * @param operatingSystems Plataformas admitidas con semántica OR; la consulta conserva su
     *     política de selección.
     * @return mapa de hasta 101 aplicaciones; vacío para una selección nula o vacía.
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
     * Consulta en lote aplicaciones activas con página oficial no vacía, aunque no dispongan de
     * instalador descargable.
     *
     * @param appIds Selección de UUID de aplicaciones en el orden solicitado.
     * @return mapa de hasta 101 alternativas manuales.
     */
    @Override
    public Map<UUID, ManualSource> findManualSources(Collection<UUID> appIds) {
        if (appIds == null || appIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = appIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT id, name, official_url
                FROM software_apps
                WHERE id IN (
                """);
        appendPlaceholders(sql, ids.size());
        sql.append("""
                )
                  AND app_status = 'active'
                  AND official_url IS NOT NULL
                  AND TRIM(official_url) <> ''
                ORDER BY id
                LIMIT 101
                """);
        Map<UUID, ManualSource> selected = new LinkedHashMap<>();
        jdbc.query(sql.toString(), (ResultSet row) -> {
            UUID appId = UuidBytes.toUuid(row.getBytes("id"));
            selected.putIfAbsent(appId, new ManualSource(
                    appId,
                    row.getString("name"),
                    row.getString("official_url")));
        }, ids.stream().map(UuidBytes::fromUuid).toArray());
        return Map.copyOf(selected);
    }

    /**
     * Consulta exclusivamente la fuente indicada y exige que aplicación, plataforma y estados de
     * disponibilidad sigan siendo válidos.
     *
     * @param appId UUID público de la aplicación del catálogo.
     * @param sourceRef UUID de la fuente exacta; null permite selección automática o representa una
     *     alternativa manual.
     * @param operatingSystems Plataformas admitidas con semántica OR; la consulta conserva su
     *     política de selección.
     * @return fuente exacta o vacío; los UUID nulos no producen selección.
     */
    @Override
    public Optional<VerifiedSource> findVerifiedSource(
            UUID appId, UUID sourceRef, List<String> operatingSystems) {
        if (appId == null || sourceRef == null) {
            return Optional.empty();
        }
        List<String> systems = normalizedSystems(operatingSystems);
        StringBuilder sql = new StringBuilder("""
                SELECT ds.software_app_id, rs.id AS source_ref,
                       ds.operating_system, ds.architecture,
                       app.name AS app_name, app.official_url
                FROM software_apps app
                JOIN download_sources ds ON ds.software_app_id = app.id
                JOIN resolved_sources rs ON rs.download_source_id = ds.id
                WHERE ds.software_app_id = ?
                  AND rs.id = ?
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
                LIMIT 1
                """);
        List<Object> parameters = new ArrayList<>(2 + systems.size());
        parameters.add(UuidBytes.fromUuid(appId));
        parameters.add(UuidBytes.fromUuid(sourceRef));
        parameters.addAll(systems);
        List<VerifiedSource> selected = new ArrayList<>(1);
        jdbc.query(sql.toString(), (ResultSet row) -> {
            selected.add(new VerifiedSource(
                    UuidBytes.toUuid(row.getBytes("software_app_id")),
                    UuidBytes.toUuid(row.getBytes("source_ref")),
                    row.getString("operating_system"),
                    row.getString("architecture"),
                    row.getString("app_name"),
                    row.getString("official_url")));
        }, parameters.toArray());
        return selected.stream().findFirst();
    }

    /**
     * Ordena las plataformas con la preferencia windows, linux, macos; una selección vacía o sin
     * valores conocidos permite todas.
     *
     * @param operatingSystems Plataformas admitidas con semántica OR; la consulta conserva su
     *     política de selección.
     * @return lista canónica usada para construir el filtro SQL.
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
     * Añade marcadores SQL separados por comas para enlazar valores sin interpolarlos en la
     * sentencia.
     *
     * @param sql Sentencia en construcción; solo se añaden marcadores de parámetros, nunca valores.
     * @param count Cantidad de marcadores interrogantes separados por comas.
     */
    private static void appendPlaceholders(StringBuilder sql, int count) {
        for (int index = 0; index < count; index++) {
            if (index > 0) sql.append(", ");
            sql.append('?');
        }
    }
}
