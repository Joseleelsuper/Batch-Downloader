package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.bundle.BundleDtos.BundleDetails;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleSummary;
import es.ubu.batchdownloader.bundle.BundleDtos.PlatformAvailability;
import es.ubu.batchdownloader.bundle.BundleDtos.UpsertBundleRequest;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppListItem;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestiona la persistencia y consulta de {@code BundleRepository}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Repository
public class BundleRepository {
    /**
     * Constante que define {@code MAX_BUNDLE_APPS}.
     */
    private static final int MAX_BUNDLE_APPS = 100;
    /**
     * Estado {@code jdbc} mantenido por {@code BundleRepository}.
     */
    private final JdbcTemplate jdbc;
    /**
     * Estado {@code catalog} mantenido por {@code BundleRepository}.
     */
    private final CatalogRepository catalog;

    /**
     * Inicializa una instancia de {@code BundleRepository}.
     *
     * @param jdbc Valor de {@code jdbc} utilizado por la operación.
     * @param catalog Acceso al catálogo utilizado por la operación.
     */
    public BundleRepository(JdbcTemplate jdbc, CatalogRepository catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    /**
     * Enumera los elementos solicitados mediante {@code list}.
     *
     * @param type Valor de {@code type} utilizado por la operación.
     * @param sort Valor de {@code sort} utilizado por la operación.
     * @param page Número de página solicitado.
     * @param pageSize Número máximo de elementos incluidos en una página.
     * @return Colección de elementos obtenidos por la operación.
     */
    public List<BundleSummary> list(String type, String sort, int page, int pageSize) {
        String order = "stars".equals(sort) ? "star_count DESC, updated_at DESC" : "updated_at DESC";
        String sql = """
                SELECT * FROM bundles
                WHERE (? IS NULL OR type = ?) AND visibility IN ('public', 'official')
                ORDER BY %s
                LIMIT ? OFFSET ?
                """.formatted(order);
        List<BundleBase> bundles = jdbc.query(
                sql,
                (rs, rowNum) -> bundleBase(rs),
                blankToNull(type),
                blankToNull(type),
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
                WHERE (? IS NULL OR type = ?) AND visibility IN ('public', 'official')
                """,
                Long.class,
                blankToNull(type),
                blankToNull(type));
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
                blankToNull(type),
                blankToNull(type),
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
                blankToNull(type),
                blankToNull(type));
        return count == null ? 0 : count;
    }

    /**
     * Ejecuta la operación {@code details}.
     *
     * @param publicId Identificador de {@code public} utilizado por la operación.
     * @param username Valor de {@code username} utilizado por la operación.
     * @param administrator Valor de {@code administrator} utilizado por la operación.
     * @return Resultado producido por {@code details}.
     * @throws NotFoundException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    public BundleDetails details(String publicId, String username, boolean administrator) {
        BundleRecord bundle = findBundle(publicId);
        if (!isVisibleTo(bundle, username, administrator)) {
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
     * @param username Valor de {@code username} utilizado por la operación.
     * @param administrator Valor de {@code administrator} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     * @throws NotFoundException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     * @throws ConflictException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    public List<UUID> appIdsForDownload(String publicId, String username, boolean administrator) {
        BundleAccess bundle = findBundleAccess(publicId);
        if (!isVisibleTo(bundle, username, administrator)) {
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
        if (appIds.size() > MAX_BUNDLE_APPS) {
            throw new ConflictException(
                    "bundle_too_large",
                    "Este bundle supera el máximo de " + MAX_BUNDLE_APPS + " aplicaciones y debe reducirse antes de descargarse.");
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
                SELECT id, visibility, owner_id, owner_username
                FROM bundles
                WHERE (? IS NOT NULL AND id = ?) OR slug = ?
                LIMIT 1
                """,
                (rs, rowNum) -> new BundleAccess(
                        UuidBytes.toUuid(rs.getBytes("id")),
                        rs.getString("visibility"),
                        nullableUuid(rs, "owner_id"),
                        rs.getString("owner_username")),
                uuidBytesOrNull(publicId),
                uuidBytesOrNull(publicId),
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
                        nullableUuid(rs, "owner_id"),
                        rs.getString("owner_username")),
                uuidBytesOrNull(publicId),
                uuidBytesOrNull(publicId),
                publicId);
        if (bundles.isEmpty()) {
            throw new NotFoundException("bundle_not_found", "El bundle no existe.");
        }
        return bundles.get(0);
    }

    /**
     * Crea el recurso solicitado mediante {@code create}.
     *
     * @param request Solicitud recibida por la operación.
     * @param ownerUsername Valor de {@code ownerUsername} utilizado por la operación.
     * @return Resultado producido por {@code create}.
     * @throws ConflictException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    @Transactional
    public BundleDetails create(UpsertBundleRequest request, String ownerUsername) {
        String requestedSlug = normalizeSlug(request.slug() == null || request.slug().isBlank() ? request.name() : request.slug());
        if (request.slug() != null && !request.slug().isBlank() && existsSlug(requestedSlug)) {
            throw new ConflictException("bundle_slug_exists", "Ya existe un bundle con ese slug.");
        }
        String slug = request.slug() == null || request.slug().isBlank()
                ? uniqueSlug(requestedSlug)
                : requestedSlug;
        UUID id = UUID.randomUUID();
        UUID ownerId = userId(ownerUsername);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                """
                INSERT INTO bundles
                (id, slug, name, description, type, visibility, owner_username, owner_id, star_count, app_count, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, 0)
                """,
                UuidBytes.fromUuid(id),
                slug,
                request.name().trim(),
                request.description(),
                normalizedType(request.type()),
                normalizedVisibility(request.visibility()),
                ownerUsername == null || ownerUsername.isBlank() ? "admin" : ownerUsername,
                ownerId == null ? null : ownerId.toString(),
                now,
                now);
        replaceTags(id, request.tags());
        replaceItems(id, request.appIds());
        return detailsInternal(slug);
    }

    /**
     * Actualiza el recurso solicitado mediante {@code update}.
     *
     * @param publicId Identificador de {@code public} utilizado por la operación.
     * @param request Solicitud recibida por la operación.
     * @return Resultado producido por {@code update}.
     * @throws ConflictException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    @Transactional
    public BundleDetails update(String publicId, UpsertBundleRequest request) {
        UUID id = idByPublicId(publicId);
        String currentSlug = slugById(id);
        String nextSlug = normalizeSlug(request.slug() == null || request.slug().isBlank() ? currentSlug : request.slug());
        if (!nextSlug.equals(currentSlug) && existsSlug(nextSlug)) {
            throw new ConflictException("bundle_slug_exists", "Ya existe un bundle con ese slug.");
        }
        jdbc.update(
                """
                UPDATE bundles
                SET slug = ?, name = ?, description = ?, type = ?, visibility = ?, updated_at = ?, version = version + 1
                WHERE id = ?
                """,
                nextSlug,
                request.name().trim(),
                request.description(),
                normalizedType(request.type()),
                normalizedVisibility(request.visibility()),
                LocalDateTime.now(),
                UuidBytes.fromUuid(id));
        replaceTags(id, request.tags());
        replaceItems(id, request.appIds());
        return detailsInternal(nextSlug);
    }

    /**
     * Elimina el recurso solicitado mediante {@code delete}.
     *
     * @param publicId Identificador de {@code public} utilizado por la operación.
     */
    @Transactional
    public void delete(String publicId) {
        UUID id = idByPublicId(publicId);
        jdbc.update("DELETE FROM bundles WHERE id = ?", UuidBytes.fromUuid(id));
    }

    /**
     * Ejecuta la operación {@code replaceTags}.
     *
     * @param bundleId Identificador de {@code bundle} utilizado por la operación.
     * @param tags Valor de {@code tags} utilizado por la operación.
     */
    private void replaceTags(UUID bundleId, List<String> tags) {
        jdbc.update("DELETE FROM bundle_tags WHERE bundle_id = ?", UuidBytes.fromUuid(bundleId));
        if (tags == null) {
            return;
        }
        for (String tag : tags.stream().filter(value -> value != null && !value.isBlank()).distinct().toList()) {
            jdbc.update(
                    """
                    INSERT INTO bundle_tags (id, bundle_id, tag, normalized_tag, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    UuidBytes.fromUuid(UUID.randomUUID()),
                    UuidBytes.fromUuid(bundleId),
                    tag.trim(),
                    tag.toLowerCase(Locale.ROOT).trim(),
                    LocalDateTime.now());
        }
    }

    /**
     * Ejecuta la operación {@code replaceItems}.
     *
     * @param bundleId Identificador de {@code bundle} utilizado por la operación.
     * @param appIds Colección de identificadores de {@code app}.
     * @throws ConflictException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private void replaceItems(UUID bundleId, List<String> appIds) {
        List<String> requested = appIds == null
                ? List.of()
                : appIds.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                        .stream()
                        .toList();
        if (requested.size() > MAX_BUNDLE_APPS) {
            throw new ConflictException(
                    "bundle_too_large",
                    "Un bundle no puede contener más de " + MAX_BUNDLE_APPS + " aplicaciones.");
        }
        List<UUID> softwareAppIds = requested.stream().map(catalog::softwareAppId).toList();
        jdbc.update("DELETE FROM bundle_items WHERE bundle_id = ?", UuidBytes.fromUuid(bundleId));
        int order = 0;
        for (UUID softwareAppId : softwareAppIds) {
            jdbc.update(
                    """
                    INSERT INTO bundle_items (id, bundle_id, software_app_id, sort_order, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    UuidBytes.fromUuid(UUID.randomUUID()),
                    UuidBytes.fromUuid(bundleId),
                    UuidBytes.fromUuid(softwareAppId),
                    order++,
                    LocalDateTime.now());
        }
        jdbc.update("UPDATE bundles SET app_count = ? WHERE id = ?", order, UuidBytes.fromUuid(bundleId));
    }

    /**
     * Ejecuta la operación {@code summary}.
     *
     * @param rs Valor de {@code rs} utilizado por la operación.
     * @return Resultado producido por {@code summary}.
     * @throws SQLException Si no puede completarse la operación bajo las condiciones requeridas.
     */
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

    /**
     * Ejecuta la operación {@code idByPublicId}.
     *
     * @param publicId Identificador de {@code public} utilizado por la operación.
     * @return Resultado producido por {@code idByPublicId}.
     * @throws NotFoundException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private UUID idByPublicId(String publicId) {
        List<UUID> ids = jdbc.query(
                """
                SELECT id FROM bundles
                WHERE (? IS NOT NULL AND id = ?) OR slug = ?
                LIMIT 1
                """,
                (rs, rowNum) -> UuidBytes.toUuid(rs.getBytes("id")),
                uuidBytesOrNull(publicId),
                uuidBytesOrNull(publicId),
                publicId);
        if (ids.isEmpty()) {
            throw new NotFoundException("bundle_not_found", "El bundle no existe.");
        }
        return ids.get(0);
    }

    /**
     * Ejecuta la operación {@code slugById}.
     *
     * @param id Identificador del recurso sobre el que se actúa.
     * @return Resultado producido por {@code slugById}.
     * @throws NotFoundException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private String slugById(UUID id) {
        String slug = jdbc.queryForObject(
                "SELECT slug FROM bundles WHERE id = ?",
                String.class,
                UuidBytes.fromUuid(id));
        if (slug == null) {
            throw new NotFoundException("bundle_not_found", "El bundle no existe.");
        }
        return slug;
    }

    /**
     * Ejecuta la operación {@code existsSlug}.
     *
     * @param slug Valor de {@code slug} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    private boolean existsSlug(String slug) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM bundles WHERE slug = ?", Long.class, slug);
        return count != null && count > 0;
    }

    /**
     * Normaliza el valor recibido mediante {@code normalizedType}.
     *
     * @param type Valor de {@code type} utilizado por la operación.
     * @return Resultado producido por {@code normalizedType}.
     */
    private String normalizedType(String type) {
        if ("community".equals(type) || "user".equals(type)) {
            return type;
        }
        return "official";
    }

    /**
     * Normaliza el valor recibido mediante {@code normalizedVisibility}.
     *
     * @param visibility Valor de {@code visibility} utilizado por la operación.
     * @return Resultado producido por {@code normalizedVisibility}.
     */
    private String normalizedVisibility(String visibility) {
        if ("private".equals(visibility) || "public".equals(visibility)) {
            return visibility;
        }
        return "official";
    }

    /**
     * Normaliza el valor recibido mediante {@code normalizeSlug}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code normalizeSlug}.
     */
    private String normalizeSlug(String value) {
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "bundle-" + UUID.randomUUID() : slug;
    }

    /**
     * Ejecuta la operación {@code uniqueSlug}.
     *
     * @param baseSlug Valor de {@code baseSlug} utilizado por la operación.
     * @return Resultado producido por {@code uniqueSlug}.
     */
    private String uniqueSlug(String baseSlug) {
        String candidate = baseSlug;
        int suffix = 2;
        while (existsSlug(candidate)) {
            candidate = baseSlug + "-" + suffix++;
        }
        return candidate;
    }

    /**
     * Ejecuta la operación {@code uuidBytesOrNull}.
     *
     * @param publicId Identificador de {@code public} utilizado por la operación.
     * @return Resultado producido por {@code uuidBytesOrNull}.
     */
    private byte[] uuidBytesOrNull(String publicId) {
        try {
            return publicId == null || publicId.isBlank()
                    ? null
                    : UuidBytes.fromUuid(UUID.fromString(publicId));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Ejecuta la operación {@code blankToNull}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code blankToNull}.
     */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Indica si se cumple la condición mediante {@code isVisibleTo}.
     *
     * @param bundle Valor de {@code bundle} utilizado por la operación.
     * @param username Valor de {@code username} utilizado por la operación.
     * @param administrator Valor de {@code administrator} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    private boolean isVisibleTo(BundleRecord bundle, String username, boolean administrator) {
        return isVisibleTo(
                new BundleAccess(
                        UUID.fromString(bundle.details().id()),
                        bundle.details().visibility(),
                        bundle.ownerId(),
                        bundle.ownerUsername()),
                username,
                administrator);
    }

    /** Comprueba la visibilidad utilizando únicamente los datos mínimos de acceso. */
    private boolean isVisibleTo(BundleAccess bundle, String username, boolean administrator) {
        String visibility = bundle.visibility();
        if ("public".equals(visibility) || "official".equals(visibility) || administrator) {
            return true;
        }
        if (username == null || username.isBlank()) {
            return false;
        }
        UUID userId = userId(username);
        if (bundle.ownerId() != null && bundle.ownerId().equals(userId)) {
            return true;
        }
        return bundle.ownerUsername() != null && bundle.ownerUsername().equalsIgnoreCase(username.trim());
    }

    /**
     * Ejecuta la operación {@code userId}.
     *
     * @param username Valor de {@code username} utilizado por la operación.
     * @return Resultado producido por {@code userId}.
     */
    private UUID userId(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        List<UUID> ids = jdbc.query(
                "SELECT id FROM core_users WHERE normalized_username = ? LIMIT 1",
                (rs, rowNum) -> UUID.fromString(rs.getString("id")),
                username.trim().toLowerCase(Locale.ROOT));
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * Ejecuta la operación {@code nullableUuid}.
     *
     * @param row Valor de {@code row} utilizado por la operación.
     * @param column Valor de {@code column} utilizado por la operación.
     * @return Resultado producido por {@code nullableUuid}.
     * @throws SQLException Si no puede completarse la operación bajo las condiciones requeridas.
     */
    private UUID nullableUuid(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    /**
     * Representa los datos inmutables de {@code BundleRecord}.
     *
     * @param details Valor de {@code details} incluido en el record.
     * @param ownerId Valor de {@code ownerId} incluido en el record.
     * @param ownerUsername Valor de {@code ownerUsername} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    private record BundleRecord(BundleDetails details, UUID ownerId, String ownerUsername) {}

    /** Fila base de una página, todavía sin relaciones. */
    private record BundleBase(
            UUID id,
            String slug,
            String name,
            String description,
            String type,
            String visibility,
            int starCount,
            LocalDateTime updatedAt) {}

    /** Datos mínimos para autorizar el acceso a un bundle. */
    private record BundleAccess(UUID id, String visibility, UUID ownerId, String ownerUsername) {}
}
