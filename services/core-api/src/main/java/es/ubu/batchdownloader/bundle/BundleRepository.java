package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.bundle.BundleDtos.BundleDetails;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleSummary;
import es.ubu.batchdownloader.bundle.BundleDtos.UpsertBundleRequest;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppListItem;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BundleRepository {
    private static final int MAX_BUNDLE_APPS = 100;
    private final JdbcTemplate jdbc;
    private final CatalogRepository catalog;

    public BundleRepository(JdbcTemplate jdbc, CatalogRepository catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    public List<BundleSummary> list(String type, String sort, int page, int pageSize) {
        String order = "stars".equals(sort) ? "star_count DESC, updated_at DESC" : "updated_at DESC";
        String sql = """
                SELECT * FROM bundles
                WHERE (? IS NULL OR type = ?) AND visibility IN ('public', 'official')
                ORDER BY %s
                LIMIT ? OFFSET ?
                """.formatted(order);
        return jdbc.query(sql, (rs, rowNum) -> summary(rs), blankToNull(type), blankToNull(type), pageSize, (page - 1) * pageSize);
    }

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

    public List<BundleSummary> listForAdministration(String type, String sort, int page, int pageSize) {
        String order = "stars".equals(sort) ? "star_count DESC, updated_at DESC" : "updated_at DESC";
        String sql = """
                SELECT * FROM bundles
                WHERE (? IS NULL OR type = ?)
                ORDER BY %s
                LIMIT ? OFFSET ?
                """.formatted(order);
        return jdbc.query(
                sql,
                (rs, rowNum) -> summary(rs),
                blankToNull(type),
                blankToNull(type),
                pageSize,
                (page - 1) * pageSize);
    }

    public long countForAdministration(String type) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bundles WHERE (? IS NULL OR type = ?)",
                Long.class,
                blankToNull(type),
                blankToNull(type));
        return count == null ? 0 : count;
    }

    public BundleDetails details(String publicId, String username, boolean administrator) {
        BundleRecord bundle = findBundle(publicId);
        if (!isVisibleTo(bundle, username, administrator)) {
            // Do not reveal whether a private id or slug exists.
            throw new NotFoundException("bundle_not_found", "El bundle no existe.");
        }
        return bundle.details();
    }

    public BundleDetails detailsInternal(String publicId) {
        return findBundle(publicId).details();
    }

    public List<UUID> appIdsForDownload(String publicId, String username, boolean administrator) {
        BundleRecord bundle = findBundle(publicId);
        if (!isVisibleTo(bundle, username, administrator)) {
            throw new NotFoundException("bundle_not_found", "El bundle no existe.");
        }
        if (bundle.details().appCount() > MAX_BUNDLE_APPS) {
            throw new ConflictException(
                    "bundle_too_large",
                    "Este bundle supera el máximo de " + MAX_BUNDLE_APPS + " aplicaciones y debe reducirse antes de descargarse.");
        }
        List<UUID> appIds = jdbc.query(
                """
                SELECT item.software_app_id
                FROM bundle_items item
                JOIN software_apps app ON app.id = item.software_app_id
                WHERE item.bundle_id = ?
                  AND app.app_status = 'active'
                ORDER BY item.sort_order ASC
                """,
                (rs, rowNum) -> UuidBytes.toUuid(rs.getBytes("software_app_id")),
                UuidBytes.fromUuid(UUID.fromString(bundle.details().id())));
        if (appIds.size() > MAX_BUNDLE_APPS) {
            throw new ConflictException(
                    "bundle_too_large",
                    "Este bundle supera el máximo de " + MAX_BUNDLE_APPS + " aplicaciones y debe reducirse antes de descargarse.");
        }
        return List.copyOf(appIds);
    }

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
                ownerId,
                now,
                now);
        replaceTags(id, request.tags());
        replaceItems(id, request.appIds());
        return detailsInternal(slug);
    }

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

    @Transactional
    public void delete(String publicId) {
        UUID id = idByPublicId(publicId);
        jdbc.update("DELETE FROM bundles WHERE id = ?", UuidBytes.fromUuid(id));
    }

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

    private BundleSummary summary(ResultSet rs) throws SQLException {
        UUID id = UuidBytes.toUuid(rs.getBytes("id"));
        return new BundleSummary(
                id.toString(),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("type"),
                rs.getString("visibility"),
                rs.getInt("star_count"),
                activeAppCount(id),
                availableOperatingSystems(id),
                tags(id),
                previewApps(id, 6),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private BundleDetails detailsFromRow(ResultSet rs) throws SQLException {
        UUID id = UuidBytes.toUuid(rs.getBytes("id"));
        return new BundleDetails(
                id.toString(),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("type"),
                rs.getString("visibility"),
                rs.getInt("star_count"),
                activeAppCount(id),
                availableOperatingSystems(id),
                tags(id),
                previewApps(id, 0),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

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
        return jdbc.query(
                        sql,
                        (rs, rowNum) -> UuidBytes.toUuid(rs.getBytes("id")).toString(),
                        parameters)
                .stream()
                .map(catalog::details)
                .map(details -> new AppListItem(
                        details.id(),
                        details.slug(),
                        details.packageId(),
                        details.name(),
                        details.publisher(),
                        details.description(),
                        details.longDescription(),
                        details.tags(),
                        details.operatingSystems(),
                        details.iconUrl(),
                        details.latestVersion(),
                        details.sourceLabel(),
                        details.resolutionStatus(),
                        details.validationStatus(),
                        details.downloadable(),
                        details.updatedAt()))
                .toList();
    }

    private List<String> tags(UUID bundleId) {
        return jdbc.queryForList(
                "SELECT tag FROM bundle_tags WHERE bundle_id = ? ORDER BY tag",
                String.class,
                UuidBytes.fromUuid(bundleId));
    }

    /**
     * Returns each system for which at least one active bundle application has
     * a selectable installer. Job creation applies the selected system again
     * and reports applications without a compatible source as omitted.
     */
    List<String> availableOperatingSystems(UUID bundleId) {
        return jdbc.query(
                """
                SELECT DISTINCT source.operating_system
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
                ORDER BY FIELD(source.operating_system, 'windows', 'linux', 'macos')
                """,
                (rs, rowNum) -> rs.getString("operating_system"),
                UuidBytes.fromUuid(bundleId));
    }

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

    private boolean existsSlug(String slug) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM bundles WHERE slug = ?", Long.class, slug);
        return count != null && count > 0;
    }

    private String normalizedType(String type) {
        if ("community".equals(type) || "user".equals(type)) {
            return type;
        }
        return "official";
    }

    private String normalizedVisibility(String visibility) {
        if ("private".equals(visibility) || "public".equals(visibility)) {
            return visibility;
        }
        return "official";
    }

    private String normalizeSlug(String value) {
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "bundle-" + UUID.randomUUID() : slug;
    }

    private String uniqueSlug(String baseSlug) {
        String candidate = baseSlug;
        int suffix = 2;
        while (existsSlug(candidate)) {
            candidate = baseSlug + "-" + suffix++;
        }
        return candidate;
    }

    private byte[] uuidBytesOrNull(String publicId) {
        try {
            return publicId == null || publicId.isBlank()
                    ? null
                    : UuidBytes.fromUuid(UUID.fromString(publicId));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private boolean isVisibleTo(BundleRecord bundle, String username, boolean administrator) {
        String visibility = bundle.details().visibility();
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

    private UUID nullableUuid(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private record BundleRecord(BundleDetails details, UUID ownerId, String ownerUsername) {}
}
