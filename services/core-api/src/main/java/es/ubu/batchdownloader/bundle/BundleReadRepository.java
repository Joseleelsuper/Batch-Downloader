package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.bundle.BundleDtos.BundleDetails;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleSummary;
import es.ubu.batchdownloader.bundle.BundleDtos.PlatformAvailability;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppListItem;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Centraliza las lecturas, el enriquecimiento y la autorización de bundles. */
@Repository
public class BundleReadRepository {
    private final JdbcTemplate jdbc;
    private final CatalogRepository catalog;

    /** Inicializa las lecturas con sus dos fuentes de datos. */
    public BundleReadRepository(JdbcTemplate jdbc, CatalogRepository catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    public List<BundleSummary> list(String type, String sort, int page, int pageSize) {
        String order = "stars".equals(sort) ? "star_count DESC, updated_at DESC" : "updated_at DESC";
        String sql = """
                SELECT * FROM bundles
                WHERE (? IS NULL OR type = ? OR (? = 'community' AND type = 'user'))
                  AND visibility IN ('public', 'official')
                ORDER BY %s
                LIMIT ? OFFSET ?
                """.formatted(order);
        List<BundleBase> bundles = jdbc.query(
                sql,
                (rs, rowNum) -> bundleBase(rs),
                BundleValues.blankToNull(type),
                BundleValues.blankToNull(type),
                BundleValues.blankToNull(type),
                pageSize,
                (page - 1) * pageSize);
        return enrichSummaries(bundles);
    }

    /**
     * Ejecuta la operación {@code count}.
     *
     * @param type Valor de {@code type} utilizado por la operación.
     * @return Número de elementos afectados por la operación.
     */
    public long count(String type) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM bundles
                WHERE (? IS NULL OR type = ? OR (? = 'community' AND type = 'user'))
                  AND visibility IN ('public', 'official')
                """,
                Long.class,
                BundleValues.blankToNull(type),
                BundleValues.blankToNull(type),
                BundleValues.blankToNull(type));
        return count == null ? 0 : count;
    }

    /**
     * Enumera los elementos solicitados mediante {@code listForAdministration}.
     *
     * @param type Valor de {@code type} utilizado por la operación.
     * @param sort Valor de {@code sort} utilizado por la operación.
     * @param page Número de página solicitado.
     * @param pageSize Número máximo de elementos incluidos en una página.
     * @return Colección de elementos obtenidos por la operación.
     */
    public List<BundleSummary> listForAdministration(String type, String sort, int page, int pageSize) {
        String order = "stars".equals(sort) ? "star_count DESC, updated_at DESC" : "updated_at DESC";
        String sql = """
                SELECT * FROM bundles
                WHERE (? IS NULL OR type = ?)
                ORDER BY %s
                LIMIT ? OFFSET ?
                """.formatted(order);
        List<BundleBase> bundles = jdbc.query(
                sql,
                (rs, rowNum) -> bundleBase(rs),
                BundleValues.blankToNull(type),
                BundleValues.blankToNull(type),
                pageSize,
                (page - 1) * pageSize);
        return enrichSummaries(bundles);
    }

    /**
     * Ejecuta la operación {@code countForAdministration}.
     *
     * @param type Valor de {@code type} utilizado por la operación.
     * @return Número de elementos afectados por la operación.
     */
    public long countForAdministration(String type) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bundles WHERE (? IS NULL OR type = ?)",
                Long.class,
                BundleValues.blankToNull(type),
                BundleValues.blankToNull(type));
        return count == null ? 0 : count;
    }

    /**
     * Ejecuta la operación {@code details}.
     *
     * @param publicId Identificador de {@code public} utilizado por la operación.
     * @param viewerId UUID de la cuenta que solicita el recurso, o {@code null}.
     * @param administrator Valor de {@code administrator} utilizado por la operación.
     * @return Resultado producido por {@code details}.
     * @throws NotFoundException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    public BundleDetails details(String publicId, UUID viewerId, boolean administrator) {
        BundleRecord bundle = findBundle(publicId);
        if (!BundleAccessPolicy.isVisible(
                bundle.details().visibility(), bundle.ownerId(), viewerId, administrator)) {
            // No revela si existe un identificador o slug privado.
            throw new NotFoundException("bundle_not_found", "El bundle no existe.");
        }
        return bundle.details();
    }

    /**
     * Ejecuta la operación {@code detailsInternal}.
     *
     * @param publicId Identificador de {@code public} utilizado por la operación.
     * @return Resultado producido por {@code detailsInternal}.
     */
    public BundleDetails detailsInternal(String publicId) {
        return findBundle(publicId).details();
    }

    /**
     * Ejecuta la operación {@code appIdsForDownload}.
     *
     * @param publicId Identificador de {@code public} utilizado por la operación.
     * @param viewerId UUID de la cuenta que solicita la descarga, o {@code null}.
     * @param administrator Valor de {@code administrator} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     * @throws NotFoundException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     * @throws ConflictException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    public List<UUID> appIdsForDownload(
            String publicId, UUID viewerId, boolean administrator) {
        BundleAccess bundle = findBundleAccess(publicId);
        if (!BundleAccessPolicy.isVisible(
                bundle.visibility(), bundle.ownerId(), viewerId, administrator)) {
            throw new NotFoundException("bundle_not_found", "El bundle no existe.");
        }
        List<UUID> appIds = jdbc.query(
                """
                SELECT item.software_app_id
                FROM bundle_items item
                JOIN software_apps app ON app.id = item.software_app_id
                WHERE item.bundle_id = ?
                  AND app.app_status = 'active'
                ORDER BY item.sort_order ASC
                LIMIT 101
                """,
                (rs, rowNum) -> UuidBytes.toUuid(rs.getBytes("software_app_id")),
                UuidBytes.fromUuid(bundle.id()));
        if (appIds.size() > BundleValues.MAX_BUNDLE_APPS) {
            throw new ConflictException(
                    "bundle_too_large",
                    "Este bundle supera el máximo de " + BundleValues.MAX_BUNDLE_APPS
                            + " aplicaciones y debe reducirse antes de descargarse.");
        }
        return List.copyOf(appIds);
    }

    /**
     * Carga únicamente los campos necesarios para autorizar una descarga de bundle.
     *
     * @param publicId Identificador público o slug.
     * @return Datos mínimos de acceso.
     */
    private BundleAccess findBundleAccess(String publicId) {
        List<BundleAccess> bundles = jdbc.query(
                """
                SELECT id, visibility, owner_id
                FROM bundles
                WHERE (? IS NOT NULL AND id = ?) OR slug = ?
                LIMIT 1
                """,
                (rs, rowNum) -> new BundleAccess(
                        UuidBytes.toUuid(rs.getBytes("id")),
                        rs.getString("visibility"),
                        nullableUuid(rs, "owner_id")),
                BundleValues.uuidBytesOrNull(publicId),
                BundleValues.uuidBytesOrNull(publicId),
                publicId);
        if (bundles.isEmpty()) {
            throw new NotFoundException("bundle_not_found", "El bundle no existe.");
        }
        return bundles.get(0);
    }

    /**
     * Busca el resultado solicitado mediante {@code findBundle}.
     *
     * @param publicId Identificador de {@code public} utilizado por la operación.
     * @return Resultado producido por {@code findBundle}.
     * @throws NotFoundException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private BundleRecord findBundle(String publicId) {
        List<BundleRecord> bundles = jdbc.query(
                """
                SELECT * FROM bundles
                WHERE (? IS NOT NULL AND id = ?) OR slug = ?
                LIMIT 1
                """,
                (rs, rowNum) -> new BundleRecord(
                        detailsFromRow(rs),
                        nullableUuid(rs, "owner_id")),
                BundleValues.uuidBytesOrNull(publicId),
                BundleValues.uuidBytesOrNull(publicId),
                publicId);
        if (bundles.isEmpty()) {
            throw new NotFoundException("bundle_not_found", "El bundle no existe.");
        }
        return bundles.get(0);
    }

    /** Convierte una fila base sin ejecutar consultas relacionadas desde el mapeador. */
    private BundleBase bundleBase(ResultSet rs) throws SQLException {
        return new BundleBase(
                UuidBytes.toUuid(rs.getBytes("id")),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("type"),
                rs.getString("visibility"),
                rs.getInt("star_count"),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    /**
     * Enriquece una página completa mediante consultas por lotes y fuera del mapeador JDBC.
     *
     * @param bundles Filas base de la página.
     * @return Resúmenes completos en el mismo orden.
     */
    private List<BundleSummary> enrichSummaries(List<BundleBase> bundles) {
        if (bundles.isEmpty()) {
            return List.of();
        }
        List<UUID> bundleIds = bundles.stream().map(BundleBase::id).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(bundleIds.size(), "?"));
        Object[] parameters = bundleIds.stream().map(UuidBytes::fromUuid).toArray();

        Map<UUID, List<String>> tagsByBundle = new LinkedHashMap<>();
        jdbc.query(
                "SELECT bundle_id, tag FROM bundle_tags WHERE bundle_id IN (" + placeholders
                        + ") ORDER BY bundle_id, tag",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> tagsByBundle
                        .computeIfAbsent(
                                UuidBytes.toUuid(row.getBytes("bundle_id")),
                                ignored -> new ArrayList<>())
                        .add(row.getString("tag")),
                parameters);

        Map<UUID, LinkedHashSet<UUID>> activeAppsByBundle = new LinkedHashMap<>();
        Map<UUID, Map<String, LinkedHashSet<UUID>>> platformAppsByBundle = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT DISTINCT
                    item.bundle_id,
                    app.id AS software_app_id,
                    item.sort_order,
                    CASE
                        WHEN app.catalog_status = 'available'
                         AND source.resolution_status IN ('direct', 'fallback')
                         AND source.validation_status = 'valid'
                         AND source.catalog_available = 1
                         AND artifact.catalog_downloadable = 1
                         AND source.operating_system IN ('windows', 'linux', 'macos')
                        THEN source.operating_system
                        ELSE NULL
                    END AS operating_system
                FROM bundle_items item
                JOIN software_apps app ON app.id = item.software_app_id
                LEFT JOIN download_sources source ON source.software_app_id = item.software_app_id
                LEFT JOIN resolved_sources artifact ON artifact.download_source_id = source.id
                WHERE item.bundle_id IN (%s)
                  AND app.app_status = 'active'
                ORDER BY item.bundle_id, item.sort_order, operating_system
                """.formatted(placeholders),
                (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    UUID bundleId = UuidBytes.toUuid(row.getBytes("bundle_id"));
                    UUID appId = UuidBytes.toUuid(row.getBytes("software_app_id"));
                    activeAppsByBundle
                            .computeIfAbsent(bundleId, ignored -> new LinkedHashSet<>())
                            .add(appId);
                    String operatingSystem = row.getString("operating_system");
                    if (operatingSystem != null) {
                        platformAppsByBundle
                                .computeIfAbsent(bundleId, ignored -> new LinkedHashMap<>())
                                .computeIfAbsent(operatingSystem, ignored -> new LinkedHashSet<>())
                                .add(appId);
                    }
                },
                parameters);

        LinkedHashSet<UUID> previewIds = new LinkedHashSet<>();
        activeAppsByBundle.values().forEach(ids -> ids.stream().limit(6).forEach(previewIds::add));
        platformAppsByBundle.values().forEach(platforms ->
                platforms.values().forEach(ids -> ids.stream().limit(6).forEach(previewIds::add)));
        Map<UUID, AppListItem> apps = catalog.listItems(previewIds);

        return bundles.stream().map(bundle -> {
            List<UUID> activeIds = List.copyOf(activeAppsByBundle.getOrDefault(
                    bundle.id(), new LinkedHashSet<>()));
            Map<String, LinkedHashSet<UUID>> platformIds = platformAppsByBundle.getOrDefault(
                    bundle.id(), Map.of());
            List<PlatformAvailability> availability = List.of("windows", "linux", "macos").stream()
                    .filter(platformIds::containsKey)
                    .map(operatingSystem -> {
                        List<UUID> ids = List.copyOf(platformIds.get(operatingSystem));
                        return new PlatformAvailability(
                                operatingSystem,
                                ids.size(),
                                ids.stream()
                                        .limit(6)
                                        .map(apps::get)
                                        .filter(java.util.Objects::nonNull)
                                        .toList());
                    })
                    .toList();
            return new BundleSummary(
                    bundle.id().toString(),
                    bundle.slug(),
                    bundle.name(),
                    bundle.description(),
                    bundle.type(),
                    bundle.visibility(),
                    bundle.starCount(),
                    activeIds.size(),
                    availability.stream().map(PlatformAvailability::operatingSystem).toList(),
                    availability,
                    List.copyOf(tagsByBundle.getOrDefault(bundle.id(), List.of())),
                    activeIds.stream()
                            .limit(6)
                            .map(apps::get)
                            .filter(java.util.Objects::nonNull)
                            .toList(),
                    bundle.updatedAt());
        }).toList();
    }

    /**
     * Ejecuta la operación {@code detailsFromRow}.
     *
     * @param rs Valor de {@code rs} utilizado por la operación.
     * @return Resultado producido por {@code detailsFromRow}.
     * @throws SQLException Si no puede completarse la operación bajo las condiciones requeridas.
     */
    private BundleDetails detailsFromRow(ResultSet rs) throws SQLException {
        UUID id = UuidBytes.toUuid(rs.getBytes("id"));
        List<PlatformAvailability> availability = platformAvailability(id);
        return new BundleDetails(
                id.toString(),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("type"),
                rs.getString("visibility"),
                rs.getInt("star_count"),
                activeAppCount(id),
                availability.stream().map(PlatformAvailability::operatingSystem).toList(),
                availability,
                tags(id),
                previewApps(id, 0),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    /**
     * Ejecuta la operación {@code previewApps}.
     *
     * @param bundleId Identificador de {@code bundle} utilizado por la operación.
     * @param limit Número máximo de elementos que se recuperarán.
     * @return Colección de elementos obtenidos por la operación.
     */
    private List<AppListItem> previewApps(UUID bundleId, int limit) {
        String sql = """
                SELECT a.id FROM bundle_items bi
                JOIN software_apps a ON a.id = bi.software_app_id
                WHERE bi.bundle_id = ?
                  AND a.app_status = 'active'
                ORDER BY bi.sort_order ASC
                """ + (limit > 0 ? " LIMIT ?" : "");
        Object[] parameters = limit > 0
                ? new Object[] {UuidBytes.fromUuid(bundleId), limit}
                : new Object[] {UuidBytes.fromUuid(bundleId)};
        List<UUID> appIds = jdbc.query(
                        sql,
                        (rs, rowNum) -> UuidBytes.toUuid(rs.getBytes("id")),
                        parameters);
        Map<UUID, AppListItem> apps = catalog.listItems(appIds);
        return appIds.stream()
                .map(apps::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Ejecuta la operación {@code tags}.
     *
     * @param bundleId Identificador de {@code bundle} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    private List<String> tags(UUID bundleId) {
        return jdbc.queryForList(
                "SELECT tag FROM bundle_tags WHERE bundle_id = ? ORDER BY tag",
                String.class,
                UuidBytes.fromUuid(bundleId));
    }

    /**
     * Ejecuta la operación {@code availableOperatingSystems}.
     *
     * @param bundleId Identificador de {@code bundle} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    List<String> availableOperatingSystems(UUID bundleId) {
        return platformAvailability(bundleId).stream()
                .map(PlatformAvailability::operatingSystem)
                .toList();
    }

    /**
     * Ejecuta la operación {@code platformAvailability}.
     *
     * @param bundleId Identificador de {@code bundle} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    private List<PlatformAvailability> platformAvailability(UUID bundleId) {
        Map<String, List<UUID>> appIdsBySystem = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT source.operating_system, app.id AS software_app_id, MIN(item.sort_order) AS app_order
                FROM bundle_items item
                JOIN software_apps app ON app.id = item.software_app_id
                JOIN download_sources source ON source.software_app_id = item.software_app_id
                JOIN resolved_sources artifact ON artifact.download_source_id = source.id
                WHERE item.bundle_id = ?
                  AND app.app_status = 'active'
                  AND app.catalog_status = 'available'
                  AND source.resolution_status IN ('direct', 'fallback')
                  AND source.validation_status = 'valid'
                  AND source.catalog_available = 1
                  AND artifact.catalog_downloadable = 1
                  AND source.operating_system IN ('windows', 'linux', 'macos')
                GROUP BY source.operating_system, app.id
                ORDER BY FIELD(source.operating_system, 'windows', 'linux', 'macos'), app_order
                """,
                (org.springframework.jdbc.core.RowCallbackHandler) row -> appIdsBySystem
                        .computeIfAbsent(row.getString("operating_system"), ignored -> new java.util.ArrayList<>())
                        .add(UuidBytes.toUuid(row.getBytes("software_app_id"))),
                UuidBytes.fromUuid(bundleId));
        LinkedHashSet<UUID> previewIds = new LinkedHashSet<>();
        appIdsBySystem.values().forEach(ids -> ids.stream().limit(6).forEach(previewIds::add));
        Map<UUID, AppListItem> previewApps = catalog.listItems(previewIds);
        return appIdsBySystem.entrySet().stream()
                .map(entry -> new PlatformAvailability(
                        entry.getKey(),
                        entry.getValue().size(),
                        entry.getValue().stream()
                                .limit(6)
                                .map(previewApps::get)
                                .filter(java.util.Objects::nonNull)
                                .toList()))
                .toList();
    }

    /**
     * Ejecuta la operación {@code activeAppCount}.
     *
     * @param bundleId Identificador de {@code bundle} utilizado por la operación.
     * @return Resultado producido por {@code activeAppCount}.
     */
    private int activeAppCount(UUID bundleId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM bundle_items item
                JOIN software_apps app ON app.id = item.software_app_id
                WHERE item.bundle_id = ?
                  AND app.app_status = 'active'
                """,
                Integer.class,
                UuidBytes.fromUuid(bundleId));
        return count == null ? 0 : count;
    }

    /** Convierte de forma segura una columna UUID textual opcional. */
    private UUID nullableUuid(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private record BundleRecord(BundleDetails details, UUID ownerId) {}

    private record BundleBase(
            UUID id,
            String slug,
            String name,
            String description,
            String type,
            String visibility,
            int starCount,
            LocalDateTime updatedAt) {}

    private record BundleAccess(UUID id, String visibility, UUID ownerId) {}
}
