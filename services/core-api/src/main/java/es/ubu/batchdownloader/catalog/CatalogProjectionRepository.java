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
 * Construye las vistas públicas de aplicaciones mediante consultas de fuentes, plataformas y
 * etiquetas compartidas por páginas y bundles.
 *
 * @see es.ubu.batchdownloader.catalog.CatalogRepository
 * @see es.ubu.batchdownloader.catalog.CatalogDtos
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
@Repository
public class CatalogProjectionRepository {
    private static final String EXTENSION_COLUMN = "extension";
    private static final String OPERATING_SYSTEM_COLUMN = "operating_system";
    private static final String APP_BASICS_COLUMNS = "id, winstall_id, slug, name, publisher, "
            + "description, long_description, icon_url, official_url, latest_version, "
            + "catalog_status, updated_at";

    private final JdbcTemplate jdbc;

    /**
     * Conecta las consultas SQL de metadatos y proyecciones del catálogo.
     *
     * @param jdbc Acceso SQL a catálogo, fuentes y proyecciones persistidas en MySQL.
     */
    public CatalogProjectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Consulta plataformas, etiquetas y fuentes una vez por lote y combina los resultados con el
     * estado público de cada aplicación.
     *
     * @param apps Metadatos base en el orden que debe conservar el enriquecimiento del lote.
     * @return proyecciones en el mismo orden que los metadatos base.
     */
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

    /**
     * Resuelve UUID, slug o paquete y exige que la aplicación esté activa antes de construir el
     * detalle.
     *
     * @param publicId UUID textual, slug o identificador Winstall de la aplicación.
     * @return vista pública con fuentes exactas y procedencia.
     * @throws es.ubu.batchdownloader.common.NotFoundException si no se encuentra la identidad o la
     *     aplicación no está activa.
     */
    public AppDetails details(String publicId) {
        UUID id = softwareAppId(publicId);
        List<AppDetails> matches = jdbc.query(
                """
                SELECT %s
                FROM software_apps a
                WHERE a.app_status = 'active' AND a.id = ?
                LIMIT 1
                """.formatted(APP_BASICS_COLUMNS),
                (rs, rowNum) -> mapDetails(rs),
                UuidBytes.fromUuid(id));
        if (matches.isEmpty()) {
            throw new NotFoundException("app_not_found", "La aplicacion no existe.");
        }
        return matches.get(0);
    }

    /**
     * Deduplica UUID y carga metadatos y enriquecimiento con hasta cuatro consultas por lote,
     * independientemente del número solicitado.
     *
     * @param requestedIds UUID solicitados; null, listas vacías y entradas nulas se omiten.
     * @return mapa inmutable de aplicaciones activas por UUID; omite nulos y ausentes.
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
        StringBuilder sql = new StringBuilder(("""
                SELECT %s
                FROM software_apps a
                WHERE a.app_status = 'active'
                  AND a.id IN (
                """).formatted(APP_BASICS_COLUMNS));
        CatalogSql.appendPlaceholders(sql, ids.size());
        sql.append(")");
        List<AppBasics> apps = jdbc.query(
                sql.toString(),
                (rs, rowNum) -> readBasics(rs),
                ids.stream().map(UuidBytes::fromUuid).toArray());
        List<AppListItem> enriched = enrich(apps);
        Map<UUID, AppListItem> result = new LinkedHashMap<>();
        for (int i = 0; i < apps.size(); i++) {
            result.put(apps.get(i).dbId(), enriched.get(i));
        }
        return Map.copyOf(result);
    }

    /**
     * Resuelve UUID textual, slug o identificador Winstall sin exigir que la aplicación esté
     * activa.
     *
     * @param publicId UUID textual, slug o identificador Winstall de la aplicación.
     * @return UUID persistido de la aplicación.
     * @throws es.ubu.batchdownloader.common.NotFoundException si ninguna identidad coincide.
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

    /**
     * Resuelve una identidad de aplicación únicamente dentro del catálogo activo que puede
     * seleccionar un usuario.
     *
     * @param publicId UUID textual, slug o identificador Winstall de la aplicación.
     * @return UUID de una aplicación activa.
     * @throws es.ubu.batchdownloader.common.NotFoundException si la identidad no existe o la
     *     aplicación está inactiva.
     */
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
     * Combina metadatos base con plataformas, etiquetas y estado efectivo de la fuente sin
     * consultar la base de datos.
     *
     * @param app Metadatos base de la aplicación que se combina con fuentes, etiquetas y
     *     plataformas.
     * @param operatingSystems Plataformas con semántica OR; una lista vacía representa todas las
     *     plataformas.
     * @param tags Etiquetas que debe cumplir conjuntamente cada aplicación, sin distinguir
     *     mayúsculas.
     * @param source Instantánea de fuente ya conciliada con el estado público del catálogo.
     * @return tarjeta pública de aplicación sin direcciones resueltas.
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
     * Enriquece una aplicación con procedencia, fuente principal, opciones exactas, plataformas y
     * notas de disponibilidad.
     *
     * @param rs Fila SQL posicionada en una aplicación o fuente con las columnas de la consulta
     *     correspondiente.
     * @return detalle completo sin exponer la URL final del instalador.
     * @throws java.sql.SQLException si una columna de los metadatos no puede leerse.
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
     * Lee únicamente identidad, descripción, procedencia y estado de la fila de aplicación.
     *
     * @param rs Fila SQL posicionada en una aplicación o fuente con las columnas de la consulta
     *     correspondiente.
     * @return metadatos base que pueden enriquecerse en lote.
     * @throws java.sql.SQLException si una columna falta o tiene un tipo incompatible.
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
     * Selecciona la fuente de presentación priorizando disponibilidad, revisión, versión, rango,
     * puntuación e identidades de desempate.
     *
     * @param appId UUID de la aplicación; las rutas textuales también admiten slug o identificador
     *     Winstall.
     * @return instantánea preferida o una representación explícita de ausencia.
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
     * Aplica en lote el mismo orden de preferencia de la fuente principal y conserva la primera
     * fila por aplicación.
     *
     * @param appIds UUID de las aplicaciones del lote a enriquecer o consultar.
     * @return mapa de instantáneas por UUID; las aplicaciones sin fuente no aparecen.
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
     * Considera descargable una fuente resuelta con validación valid y resolución direct o
     * fallback; conserva metadatos nullable y estados por defecto de ausencia.
     *
     * @param rs Fila SQL posicionada en una aplicación o fuente con las columnas de la consulta
     *     correspondiente.
     * @return instantánea de la fuente antes de conciliarla con catalogStatus.
     * @throws java.sql.SQLException si no puede leer una columna de fuente.
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
                rs.getString(EXTENSION_COLUMN),
                rs.getString("content_type"),
                nullableLong(rs, "size_bytes"),
                rs.getString("final_domain"),
                nullableInt(rs, "score"),
                nullableDate(rs, "checked_at"),
                nullableDate(rs, "expires_at"),
                downloadable);
    }

    /**
     * Consulta hasta cincuenta fuentes disponibles en orden de versión, rango, preferencia,
     * puntuación y fecha; añade soporte del perfil Linux aprobado cuando existe.
     *
     * @param appId UUID de la aplicación; las rutas textuales también admiten slug o identificador
     *     Winstall.
     * @return opciones con UUID exacto, sin URL final de descarga.
     */
    private List<DownloadOption> downloadOptions(UUID appId) {
        return jdbc.query(
                """
                SELECT rs.id, rs.filename, rs.extension, rs.final_domain, rs.score, rs.status, rs.metadata_json,
                       ds.operating_system, ds.architecture, rs.version, rs.is_latest, rs.version_status,
                       rs.release_rank, JSON_UNQUOTE(JSON_EXTRACT(lip.profile_json, '$.strategy')) AS linux_strategy
                FROM download_sources ds
                JOIN resolved_sources rs ON rs.download_source_id = ds.id
                LEFT JOIN linux_install_profiles lip ON lip.source_ref = rs.id AND lip.status = 'approved'
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
                        rs.getString(EXTENSION_COLUMN),
                        rs.getString(OPERATING_SYSTEM_COLUMN),
                        rs.getString("architecture"),
                        rs.getString("version"),
                        rs.getBoolean("is_latest"),
                        rs.getString("version_status"),
                        sourceLabel(rs.getString("status")),
                        rs.getInt("score"),
                        rs.getString("final_domain"),
                        rowNum == 0,
                        LinuxInstallationSupport.support(rs.getString(OPERATING_SYSTEM_COLUMN),
                                rs.getString(EXTENSION_COLUMN), rs.getString("linux_strategy")),
                        "linux".equals(rs.getString(OPERATING_SYSTEM_COLUMN))
                                ? LinuxInstallationSupport.targets(rs.getString(EXTENSION_COLUMN)) : List.of()),
                UuidBytes.fromUuid(appId));
    }

    /**
     * Expande la proyección JSON de plataformas de todas las aplicaciones y deduplica solo las tres
     * soportadas.
     *
     * @param appIds UUID de las aplicaciones del lote a enriquecer o consultar.
     * @return plataformas por UUID en orden windows, linux, macos.
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
            result.computeIfAbsent(appId, ignored -> new ArrayList<>()).add(row.getString(OPERATING_SYSTEM_COLUMN));
        }, ids.stream().map(UuidBytes::fromUuid).toArray());
        result.replaceAll((id, systems) -> systems.stream().distinct().toList());
        return result;
    }

    /**
     * Consulta las etiquetas visibles de una aplicación ordenadas por texto.
     *
     * @param appId UUID de la aplicación; las rutas textuales también admiten slug o identificador
     *     Winstall.
     * @return etiquetas de la aplicación.
     */
    private List<String> tagsFor(UUID appId) {
        return jdbc.queryForList(
                "SELECT tag FROM software_app_tags WHERE software_app_id = ? ORDER BY tag",
                String.class,
                UuidBytes.fromUuid(appId));
    }

    /**
     * Consulta etiquetas de todas las aplicaciones en un lote y conserva listas inmutables
     * ordenadas por texto.
     *
     * @param appIds UUID de las aplicaciones del lote a enriquecer o consultar.
     * @return mapa por UUID; vacío cuando no se solicita ninguna aplicación.
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
     * Traduce resolución directa, fallback y revisión a las etiquetas visibles del catálogo.
     *
     * @param status Estado de resolución de la fuente, no estado de ejecución del scraper.
     * @return Sitio oficial, Fallback Winstall, Revisión o No disponible.
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
     * Usa la página inicial u oficial para aplicaciones manual.* y el enlace al paquete Winstall
     * para las demás.
     *
     * @param winstallId Identificador Winstall o clave manual.* para aplicaciones incorporadas
     *     manualmente.
     * @param officialUrl Página oficial pública de la aplicación; no es un instalador resuelto.
     * @param sourceOriginUrl Página inicial de la fuente de una aplicación manual; si falta se
     *     utiliza su web oficial.
     * @return procedencia visible que no inventa un paquete Winstall para una aplicación manual.
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
     * Explica si el instalador procede de la fuente oficial, del fallback o necesita revisión.
     *
     * @param source Instantánea de fuente ya conciliada con el estado público del catálogo.
     * @return nota de procedencia basada en la resolución efectiva.
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
     * Lee una fecha SQL opcional sin convertir la ausencia en una fecha artificial.
     *
     * @param rs Fila SQL posicionada en una aplicación o fuente con las columnas de la consulta
     *     correspondiente.
     * @param column Nombre de una columna nullable que se interpreta con su tipo JDBC.
     * @return fecha y hora local del timestamp o null.
     * @throws java.sql.SQLException si falla la lectura de la columna.
     */
    private LocalDateTime nullableDate(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /**
     * Conserva la distinción entre SQL NULL y cero al leer un entero de 64 bits.
     *
     * @param rs Fila SQL posicionada en una aplicación o fuente con las columnas de la consulta
     *     correspondiente.
     * @param column Nombre de una columna nullable que se interpreta con su tipo JDBC.
     * @return valor presente o null.
     * @throws java.sql.SQLException si no puede leerse la columna numérica.
     */
    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * Conserva la distinción entre SQL NULL y cero al leer un entero de 32 bits.
     *
     * @param rs Fila SQL posicionada en una aplicación o fuente con las columnas de la consulta
     *     correspondiente.
     * @param column Nombre de una columna nullable que se interpreta con su tipo JDBC.
     * @return valor presente o null.
     * @throws java.sql.SQLException si no puede leerse la columna numérica.
     */
    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * Permite intentar primero una búsqueda por UUID sin rechazar identificadores que serán slugs o
     * paquetes.
     *
     * @param raw Identificador textual que se intenta interpretar como UUID.
     * @return UUID interpretado o null para ausencia o formato incompatible.
     */
    private UUID parseUuid(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Conserva los metadatos de aplicación necesarios para enriquecer una página sin repetir sus
     * consultas auxiliares.
     *
     * @param dbId UUID persistido de la aplicación, usado como clave de los lotes de
     *     enriquecimiento.
     * @param winstallId Identificador Winstall o clave manual.* para aplicaciones incorporadas
     *     manualmente.
     * @param slug Identificador legible de la aplicación en la ruta de detalle.
     * @param name Nombre visible de la aplicación.
     * @param publisher Editor singular opcional; no se descompone por comas.
     * @param description Descripción breve del propósito de la aplicación.
     * @param longDescription Descripción ampliada que se muestra en el detalle y participa en la
     *     búsqueda léxica.
     * @param iconUrl URL del icono público de la aplicación cuando está disponible.
     * @param officialUrl Página oficial pública de la aplicación; no es un instalador resuelto.
     * @param latestVersion Versión vigente de la aplicación según el catálogo.
     * @param catalogStatus Proyección pública available, review o missing que determina la
     *     disponibilidad efectiva.
     * @param updatedAt Fecha de la última actualización de la aplicación.
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
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

    /**
     * Agrupa estado, procedencia y metadatos de una fuente de presentación antes de aplicar la
     * proyección pública del catálogo.
     *
     * @param resolutionStatus Resultado de resolución de la fuente: directa, fallback, revisión o
     *     ausencia.
     * @param validationStatus Resultado de validación del instalador; unchecked representa falta de
     *     validación vigente en la vista.
     * @param sourceLabel Etiqueta visible que distingue sitio oficial, fallback, revisión o
     *     ausencia.
     * @param originUrl Página de procedencia que puede mostrarse al usuario, sin revelar la URL
     *     final protegida.
     * @param filename Nombre conocido del instalador o null si no hay una fuente resuelta
     *     utilizable.
     * @param extension Extensión del instalador con punto inicial, o null si no se conoce.
     * @param contentType Tipo MIME observado para el instalador cuando está disponible.
     * @param sizeBytes Tamaño conocido del instalador en bytes, o null si no existe medición.
     * @param finalDomain Dominio final observado, sin revelar la URL resuelta completa.
     * @param score Puntuación de preferencia del candidato según el resolvedor.
     * @param checkedAt Fecha de la última comprobación de la fuente, o null si no existe.
     * @param expiresAt Fecha que activa revalidación de la fuente; por sí sola no retira un
     *     candidato válido del catálogo.
     * @param downloadable Indica que la vista dispone de una fuente resuelta válida y
     *     seleccionable.
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
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
         * Concilia la fuente con review o missing retirando metadatos de descarga cuando no
         * corresponden; mantiene available si la fuente es descargable.
         *
         * @param catalogStatus Proyección pública available, review o missing que determina la
         *     disponibilidad efectiva.
         * @return misma instantánea cuando es coherente o una vista sin instalador que conserva la
         *     procedencia.
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
         * Representa de forma explícita una aplicación sin fuente ni comprobación de instalador.
         *
         * @return instantánea missing, unchecked y no descargable sin metadatos de archivo.
         */
        static SourceSnapshot empty() {
            return new SourceSnapshot(
                    "missing", "unchecked", "No disponible", null, null, null, null,
                    null, null, null, null, null, false);
        }
    }

}
