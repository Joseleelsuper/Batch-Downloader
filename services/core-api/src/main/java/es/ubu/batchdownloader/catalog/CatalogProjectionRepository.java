package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppListItem;
import es.ubu.batchdownloader.catalog.CatalogDtos.DownloadOption;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * Construye las proyecciones públicas de aplicaciones, sus detalles y sus fuentes descargables.
 *
 * <p>La clase encapsula el enriquecimiento por lotes para que las búsquedas no mezclen filtros
 * con resolución de fuentes, etiquetas y sistemas operativos.</p>
 */
@Repository
public class CatalogProjectionRepository {
    private final JdbcTemplate jdbc;

    /** Inicializa las proyecciones con el acceso JDBC compartido. */
    public CatalogProjectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Enriquece en lotes las filas básicas ya paginadas por el repositorio de búsqueda. */
    List<AppListItem> enrich(List<AppBasics> apps) {
        List<UUID> appIds = apps.stream().map(AppBasics::dbId).toList();
        Map<UUID, List<String>> systemsByApp = operatingSystemsFor(appIds);
        Map<UUID, List<String>> tagsByApp = tagsFor(appIds);
        Map<UUID, SourceSnapshot> sourcesByApp = sourcesFor(appIds);
        return apps.stream()
                .map(app -> mapListItem(
                        app,
                        systemsByApp.getOrDefault(app.dbId(), List.of()),
                        tagsByApp.getOrDefault(app.dbId(), List.of()),
                        sourcesByApp.getOrDefault(app.dbId(), SourceSnapshot.empty())
                                .effectiveFor(app.catalogStatus())))
                .toList();
    }

    public AppDetails details(String publicId) {
        UUID id = softwareAppId(publicId);
        List<AppDetails> matches = jdbc.query(
                """
                SELECT a.*
                FROM software_apps a
                WHERE a.app_status = 'active' AND a.id = ?
                LIMIT 1
                """,
                (rs, rowNum) -> mapDetails(rs),
                UuidBytes.fromUuid(id));
        if (matches.isEmpty()) {
            throw new NotFoundException("app_not_found", "La aplicacion no existe.");
        }
        return matches.get(0);
    }

    /**
     * Enumera los elementos solicitados mediante {@code listItems}.
     *
     * @param requestedIds Colección de identificadores de {@code requested}.
     * @return Mapa con los datos producidos por la operación.
     */
    public Map<UUID, AppListItem> listItems(Collection<UUID> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = requestedIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT a.*
                FROM software_apps a
                WHERE a.app_status = 'active'
                  AND a.id IN (
                """);
        CatalogSql.appendPlaceholders(sql, ids.size());
        sql.append(")");
        List<AppBasics> apps = jdbc.query(
                sql.toString(),
                (rs, rowNum) -> readBasics(rs),
                ids.stream().map(UuidBytes::fromUuid).toArray());
        List<UUID> foundIds = apps.stream().map(AppBasics::dbId).toList();
        Map<UUID, List<String>> systemsByApp = operatingSystemsFor(foundIds);
        Map<UUID, List<String>> tagsByApp = tagsFor(foundIds);
        Map<UUID, SourceSnapshot> sourcesByApp = sourcesFor(foundIds);
        Map<UUID, AppListItem> result = new LinkedHashMap<>();
        for (AppBasics app : apps) {
            result.put(app.dbId(), mapListItem(
                    app,
                    systemsByApp.getOrDefault(app.dbId(), List.of()),
                    tagsByApp.getOrDefault(app.dbId(), List.of()),
                    sourcesByApp.getOrDefault(app.dbId(), SourceSnapshot.empty())
                            .effectiveFor(app.catalogStatus())));
        }
        return Map.copyOf(result);
    }

    /**
     * Ejecuta la operación {@code softwareAppId}.
     *
     * @param publicId Identificador de {@code public} utilizado por la operación.
     * @return Resultado producido por {@code softwareAppId}.
     * @throws NotFoundException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    public UUID softwareAppId(String publicId) {
        UUID parsed = parseUuid(publicId);
        List<UUID> ids = jdbc.query(
                """
                SELECT id FROM software_apps
                WHERE (? IS NOT NULL AND id = ?) OR slug = ? OR winstall_id = ?
                LIMIT 1
                """,
                (rs, rowNum) -> UuidBytes.toUuid(rs.getBytes("id")),
                parsed == null ? null : UuidBytes.fromUuid(parsed),
                parsed == null ? null : UuidBytes.fromUuid(parsed),
                publicId,
                publicId);
        if (ids.isEmpty()) {
            throw new NotFoundException("app_not_found", "La aplicacion no existe.");
        }
        return ids.get(0);
    }

    /** Resuelve solo aplicaciones visibles en el catálogo público. */
    public UUID publicSoftwareAppId(String publicId) {
        UUID parsed = parseUuid(publicId);
        List<UUID> ids = jdbc.query(
                """
                SELECT id FROM software_apps
                WHERE app_status = 'active'
                  AND ((? IS NOT NULL AND id = ?) OR slug = ? OR winstall_id = ?)
                LIMIT 1
                """,
                (rs, rowNum) -> UuidBytes.toUuid(rs.getBytes("id")),
                parsed == null ? null : UuidBytes.fromUuid(parsed),
                parsed == null ? null : UuidBytes.fromUuid(parsed),
                publicId,
                publicId);
        if (ids.isEmpty()) {
            throw new NotFoundException("app_not_found", "La aplicación no existe.");
        }
        return ids.get(0);
    }

    /**
     * Ejecuta la operación {@code stats}.
     *
     * @return Resultado producido por {@code stats}.
     */
    private AppListItem mapListItem(
            AppBasics app,
            List<String> operatingSystems,
            List<String> tags,
            SourceSnapshot source) {
        return new AppListItem(
                app.dbId().toString(),
                app.slug(),
                app.winstallId(),
                app.name(),
                app.publisher(),
                app.description(),
                app.longDescription(),
                tags,
                operatingSystems,
                app.iconUrl(),
                app.latestVersion(),
                source.sourceLabel(),
                source.resolutionStatus(),
                source.validationStatus(),
                source.downloadable(),
                app.updatedAt());
    }

    /**
     * Transforma el valor recibido mediante {@code mapDetails}.
     *
     * @param rs Valor de {@code rs} utilizado por la operación.
     * @return Resultado producido por {@code mapDetails}.
     * @throws SQLException Si no puede completarse la operación bajo las condiciones requeridas.
     */
    private AppDetails mapDetails(ResultSet rs) throws SQLException {
        AppBasics app = readBasics(rs);
        SourceSnapshot source = sourceFor(app.dbId()).effectiveFor(app.catalogStatus());
        List<DownloadOption> options = downloadOptions(app.dbId());
        return new AppDetails(
                app.dbId().toString(),
                app.slug(),
                app.winstallId(),
                app.name(),
                app.publisher(),
                app.description(),
                app.longDescription(),
                tagsFor(app.dbId()),
                operatingSystemsFor(List.of(app.dbId())).getOrDefault(app.dbId(), List.of()),
                app.iconUrl(),
                app.officialUrl(),
                originUrl(app.winstallId(), app.officialUrl(), source.originUrl()),
                app.latestVersion(),
                source.filename(),
                source.extension() == null ? null : source.extension().replace(".", "").toUpperCase(Locale.ROOT),
                source.contentType(),
                source.sizeBytes(),
                source.finalDomain(),
                source.score(),
                source.resolutionStatus(),
                source.validationStatus(),
                source.downloadable(),
                app.updatedAt(),
                source.sourceLabel(),
                source.checkedAt(),
                source.expiresAt(),
                options,
                notesFor(source));
    }

    /**
     * Ejecuta la operación {@code readBasics}.
     *
     * @param rs Valor de {@code rs} utilizado por la operación.
     * @return Resultado producido por {@code readBasics}.
     * @throws SQLException Si no puede completarse la operación bajo las condiciones requeridas.
     */
    AppBasics readBasics(ResultSet rs) throws SQLException {
        return new AppBasics(
                UuidBytes.toUuid(rs.getBytes("id")),
                rs.getString("winstall_id"),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("publisher"),
                rs.getString("description"),
                rs.getString("long_description"),
                rs.getString("icon_url"),
                rs.getString("official_url"),
                rs.getString("latest_version"),
                rs.getString("catalog_status"),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    /**
     * Ejecuta la operación {@code sourceFor}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @return Resultado producido por {@code sourceFor}.
     */
    private SourceSnapshot sourceFor(UUID appId) {
        List<SourceSnapshot> snapshots = jdbc.query(
                """
                SELECT ds.id AS source_id, ds.initial_url,
                       ds.resolution_status AS source_resolution_status,
                       ds.validation_status AS source_validation_status,
                       rs.id AS resolved_id, rs.filename, rs.extension, rs.content_type, rs.size_bytes,
                       rs.final_domain, rs.score, rs.checked_at, rs.expires_at, rs.metadata_json,
                       rs.release_rank, rs.is_latest
                FROM download_sources ds
                LEFT JOIN resolved_sources rs ON rs.download_source_id = ds.id
                    AND rs.catalog_downloadable = 1
                WHERE ds.software_app_id = ?
                ORDER BY ds.catalog_available DESC,
                         (ds.resolution_status = 'requires_manual_review') DESC,
                         (ds.resolution_status IN ('missing', 'broken')) DESC,
                         rs.is_latest DESC,
                         COALESCE(rs.release_rank, 9999) ASC,
                         (JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.is_primary')) = 'true') DESC,
                         rs.score DESC, rs.checked_at DESC, ds.id ASC, rs.id ASC
                LIMIT 1
                """,
                (rs, rowNum) -> readSourceSnapshot(rs),
                UuidBytes.fromUuid(appId));
        return snapshots.isEmpty()
                ? SourceSnapshot.empty()
                : snapshots.get(0);
    }

    /**
     * Ejecuta la operación {@code sourcesFor}.
     *
     * @param appIds Colección de identificadores de {@code app}.
     * @return Mapa con los datos producidos por la operación.
     */
    private Map<UUID, SourceSnapshot> sourcesFor(Collection<UUID> appIds) {
        if (appIds == null || appIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = appIds.stream().distinct().toList();
        StringBuilder sql = new StringBuilder("""
                SELECT ds.software_app_id, ds.id AS source_id, ds.initial_url,
                       ds.resolution_status AS source_resolution_status,
                       ds.validation_status AS source_validation_status,
                       rs.id AS resolved_id, rs.filename, rs.extension, rs.content_type, rs.size_bytes,
                       rs.final_domain, rs.score, rs.checked_at, rs.expires_at, rs.metadata_json,
                       rs.release_rank, rs.is_latest
                FROM download_sources ds
                LEFT JOIN resolved_sources rs ON rs.download_source_id = ds.id
                    AND rs.catalog_downloadable = 1
                WHERE ds.software_app_id IN (
                """);
        CatalogSql.appendPlaceholders(sql, ids.size());
        sql.append("""
                )
                ORDER BY ds.software_app_id,
                         ds.catalog_available DESC,
                         (ds.resolution_status = 'requires_manual_review') DESC,
                         (ds.resolution_status IN ('missing', 'broken')) DESC,
                         rs.is_latest DESC,
                         COALESCE(rs.release_rank, 9999) ASC,
                         (JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.is_primary')) = 'true') DESC,
                         rs.score DESC, rs.checked_at DESC, ds.id ASC, rs.id ASC
                """);
        Map<UUID, SourceSnapshot> result = new HashMap<>();
        List<Object> parameters = new ArrayList<>(ids.size());
        ids.stream().map(UuidBytes::fromUuid).forEach(parameters::add);
        jdbc.query(sql.toString(), (RowCallbackHandler) rs -> result.putIfAbsent(
                UuidBytes.toUuid(rs.getBytes("software_app_id")), readSourceSnapshot(rs)),
                parameters.toArray());
        return result;
    }

    /**
     * Ejecuta la operación {@code readSourceSnapshot}.
     *
     * @param rs Valor de {@code rs} utilizado por la operación.
     * @return Resultado producido por {@code readSourceSnapshot}.
     * @throws SQLException Si no puede completarse la operación bajo las condiciones requeridas.
     */
    private SourceSnapshot readSourceSnapshot(ResultSet rs) throws SQLException {
        String resolution = rs.getString("source_resolution_status");
        String validation = rs.getString("source_validation_status");
        boolean downloadable = rs.getBytes("resolved_id") != null
                && "valid".equals(validation)
                && ("direct".equals(resolution) || "fallback".equals(resolution));
        return new SourceSnapshot(
                resolution == null ? "missing" : resolution,
                validation == null ? "unchecked" : validation,
                sourceLabel(resolution),
                rs.getString("initial_url"),
                rs.getString("filename"),
                rs.getString("extension"),
                rs.getString("content_type"),
                nullableLong(rs, "size_bytes"),
                rs.getString("final_domain"),
                nullableInt(rs, "score"),
                nullableDate(rs, "checked_at"),
                nullableDate(rs, "expires_at"),
                downloadable);
    }

    /**
     * Ejecuta la operación {@code downloadOptions}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    private List<DownloadOption> downloadOptions(UUID appId) {
        return jdbc.query(
                """
                SELECT rs.id, rs.filename, rs.extension, rs.final_domain, rs.score, rs.status, rs.metadata_json,
                       ds.operating_system, ds.architecture, rs.version, rs.is_latest, rs.version_status,
                       rs.release_rank
                FROM download_sources ds
                JOIN resolved_sources rs ON rs.download_source_id = ds.id
                WHERE ds.software_app_id = ?
                  AND ds.catalog_available = 1
                  AND rs.catalog_downloadable = 1
                ORDER BY rs.is_latest DESC,
                         COALESCE(rs.release_rank, 9999) ASC,
                         (JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.is_primary')) = 'true') DESC,
                         rs.score DESC, rs.checked_at DESC
                LIMIT 50
                """,
                (rs, rowNum) -> new DownloadOption(
                        UuidBytes.toUuid(rs.getBytes("id")).toString(),
                        rs.getString("filename"),
                        rs.getString("extension"),
                        rs.getString("operating_system"),
                        rs.getString("architecture"),
                        rs.getString("version"),
                        rs.getBoolean("is_latest"),
                        rs.getString("version_status"),
                        sourceLabel(rs.getString("status")),
                        rs.getInt("score"),
                        rs.getString("final_domain"),
                        rowNum == 0),
                UuidBytes.fromUuid(appId));
    }

    /**
     * Ejecuta la operación {@code operatingSystemsFor}.
     *
     * @param appIds Colección de identificadores de {@code app}.
     * @return Mapa con los datos producidos por la operación.
     */
    private Map<UUID, List<String>> operatingSystemsFor(Collection<UUID> appIds) {
        if (appIds == null || appIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = appIds.stream().distinct().toList();
        StringBuilder sql = new StringBuilder("""
                SELECT a.id AS software_app_id, projected.operating_system
                FROM software_apps a
                CROSS JOIN JSON_TABLE(
                    COALESCE(a.operating_systems_json, JSON_ARRAY()),
                    '$[*]' COLUMNS(operating_system VARCHAR(16) PATH '$')
                ) AS projected
                WHERE a.id IN (
                """);
        CatalogSql.appendPlaceholders(sql, ids.size());
        sql.append("""
                )
                  AND projected.operating_system IN ('windows', 'linux', 'macos')
                ORDER BY a.id, FIELD(projected.operating_system, 'windows', 'linux', 'macos')
                """);
        Map<UUID, List<String>> result = new HashMap<>();
        jdbc.query(sql.toString(), row -> {
            UUID appId = UuidBytes.toUuid(row.getBytes("software_app_id"));
            result.computeIfAbsent(appId, ignored -> new ArrayList<>()).add(row.getString("operating_system"));
        }, ids.stream().map(UuidBytes::fromUuid).toArray());
        result.replaceAll((id, systems) -> systems.stream().distinct().toList());
        return result;
    }

    /**
     * Ejecuta la operación {@code tagsFor}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    private List<String> tagsFor(UUID appId) {
        return jdbc.queryForList(
                "SELECT tag FROM software_app_tags WHERE software_app_id = ? ORDER BY tag",
                String.class,
                UuidBytes.fromUuid(appId));
    }

    /**
     * Ejecuta la operación {@code tagsFor}.
     *
     * @param appIds Colección de identificadores de {@code app}.
     * @return Mapa con los datos producidos por la operación.
     */
    private Map<UUID, List<String>> tagsFor(Collection<UUID> appIds) {
        if (appIds == null || appIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = appIds.stream().distinct().toList();
        StringBuilder sql = new StringBuilder("""
                SELECT software_app_id, tag
                FROM software_app_tags
                WHERE software_app_id IN (
                """);
        CatalogSql.appendPlaceholders(sql, ids.size());
        sql.append(") ORDER BY software_app_id, tag");
        Map<UUID, List<String>> result = new HashMap<>();
        jdbc.query(sql.toString(), (RowCallbackHandler) rs -> result
                        .computeIfAbsent(UuidBytes.toUuid(rs.getBytes("software_app_id")), ignored -> new ArrayList<>())
                        .add(rs.getString("tag")),
                ids.stream().map(UuidBytes::fromUuid).toArray());
        result.replaceAll((id, tags) -> List.copyOf(tags));
        return result;
    }

    /**
     * Ejecuta la operación {@code orderBy}.
     *
     * @param sort Valor de {@code sort} utilizado por la operación.
     * @param relevancePrefix Valor de {@code relevancePrefix} utilizado por la operación.
     * @return Resultado producido por {@code orderBy}.
     */
    private String sourceLabel(String status) {
        if ("direct".equals(status)) {
            return "Sitio oficial";
        }
        if ("fallback".equals(status)) {
            return "Fallback Winstall";
        }
        if ("requires_manual_review".equals(status)) {
            return "Revisión";
        }
        return "No disponible";
    }

    /**
     * Ejecuta la operación {@code originUrl}.
     *
     * @param winstallId Identificador de {@code winstall} utilizado por la operación.
     * @param officialUrl Dirección de {@code official} que debe procesarse.
     * @param sourceOriginUrl Dirección de {@code sourceOrigin} que debe procesarse.
     * @return Resultado producido por {@code originUrl}.
     */
    static String originUrl(
            String winstallId,
            String officialUrl,
            String sourceOriginUrl) {
        if (winstallId != null && winstallId.startsWith("manual.")) {
            return sourceOriginUrl == null || sourceOriginUrl.isBlank()
                    ? officialUrl
                    : sourceOriginUrl;
        }
        return "https://winstall.app/apps/" + winstallId;
    }

    /**
     * Ejecuta la operación {@code notesFor}.
     *
     * @param source Fuente de descarga sobre la que se actúa.
     * @return Resultado producido por {@code notesFor}.
     */
    private String notesFor(SourceSnapshot source) {
        if ("direct".equals(source.resolutionStatus())) {
            return "Instalador obtenido desde la fuente oficial validada.";
        }
        if ("fallback".equals(source.resolutionStatus())) {
            return "Instalador obtenido desde el fallback de Winstall.";
        }
        return "El instalador necesita revision o no esta disponible.";
    }

    /**
     * Ejecuta la operación {@code nullableDate}.
     *
     * @param rs Valor de {@code rs} utilizado por la operación.
     * @param column Valor de {@code column} utilizado por la operación.
     * @return Resultado producido por {@code nullableDate}.
     * @throws SQLException Si no puede completarse la operación bajo las condiciones requeridas.
     */
    private LocalDateTime nullableDate(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /**
     * Ejecuta la operación {@code nullableLong}.
     *
     * @param rs Valor de {@code rs} utilizado por la operación.
     * @param column Valor de {@code column} utilizado por la operación.
     * @return Resultado producido por {@code nullableLong}.
     * @throws SQLException Si no puede completarse la operación bajo las condiciones requeridas.
     */
    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * Ejecuta la operación {@code nullableInt}.
     *
     * @param rs Valor de {@code rs} utilizado por la operación.
     * @param column Valor de {@code column} utilizado por la operación.
     * @return Resultado producido por {@code nullableInt}.
     * @throws SQLException Si no puede completarse la operación bajo las condiciones requeridas.
     */
    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * Analiza el contenido recibido mediante {@code parseUuid}.
     *
     * @param raw Valor de {@code raw} utilizado por la operación.
     * @return Resultado producido por {@code parseUuid}.
     */
    private UUID parseUuid(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Datos mínimos de una aplicación antes de enriquecer su proyección pública. */
    record AppBasics(
            UUID dbId,
            String winstallId,
            String slug,
            String name,
            String publisher,
            String description,
            String longDescription,
            String iconUrl,
            String officialUrl,
            String latestVersion,
            String catalogStatus,
            LocalDateTime updatedAt) {}

    private record SourceSnapshot(
            String resolutionStatus,
            String validationStatus,
            String sourceLabel,
            String originUrl,
            String filename,
            String extension,
            String contentType,
            Long sizeBytes,
            String finalDomain,
            Integer score,
            LocalDateTime checkedAt,
            LocalDateTime expiresAt,
            boolean downloadable) {
        /**
         * Ejecuta la operación {@code effectiveFor}.
         *
         * @param catalogStatus Valor de {@code catalogStatus} utilizado por la operación.
         * @return Resultado producido por {@code effectiveFor}.
         */
        SourceSnapshot effectiveFor(String catalogStatus) {
            if ("available".equals(catalogStatus) && downloadable) {
                return this;
            }
            if ("review".equals(catalogStatus)) {
                if ("requires_manual_review".equals(resolutionStatus)) {
                    return this;
                }
                return new SourceSnapshot(
                        "requires_manual_review", "unchecked", "Revisión", originUrl,
                        null, null, null, null, null, null, null, null, false);
            }
            if ("missing".equals(catalogStatus)) {
                if (("missing".equals(resolutionStatus) || "broken".equals(resolutionStatus)) && !downloadable) {
                    return this;
                }
                return new SourceSnapshot(
                        "missing", "unchecked", "No disponible", originUrl,
                        null, null, null, null, null, null, null, null, false);
            }
            return this;
        }

        /**
         * Ejecuta la operación {@code empty}.
         *
         * @return Resultado producido por {@code empty}.
         */
        static SourceSnapshot empty() {
            return new SourceSnapshot(
                    "missing", "unchecked", "No disponible", null, null, null, null,
                    null, null, null, null, null, false);
        }
    }

}
