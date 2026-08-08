package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.bundle.BundleDtos.CreateOwnBundleRequest;
import es.ubu.batchdownloader.bundle.BundleDtos.OwnBundleDetails;
import es.ubu.batchdownloader.bundle.BundleDtos.OwnBundleSummary;
import es.ubu.batchdownloader.bundle.BundleDtos.UpdateOwnBundleRequest;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persistencia acotada de bundles propios; toda escritura incluye {@code owner_id}. */
@Repository
public class UserBundleRepository {
    private static final int MAX_APPS = 100;
    private final JdbcTemplate jdbc;
    private final CatalogRepository catalog;
    private final BundleRepository publicBundles;

    public UserBundleRepository(
            JdbcTemplate jdbc, CatalogRepository catalog, BundleRepository publicBundles) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.publicBundles = publicBundles;
    }

    public List<OwnBundleSummary> list(UUID ownerId, int page, int pageSize) {
        List<OwnBundleRow> rows = jdbc.query(
                """
                SELECT id, slug, name, description, visibility, app_count, updated_at, version
                FROM bundles
                WHERE owner_id = ? AND type = 'user'
                ORDER BY updated_at DESC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> new OwnBundleRow(
                        UuidBytes.toUuid(rs.getBytes("id")),
                        rs.getString("slug"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("visibility"),
                        rs.getInt("app_count"),
                        rs.getTimestamp("updated_at").toLocalDateTime(),
                        rs.getLong("version")),
                ownerId.toString(), pageSize, (page - 1) * pageSize);
        Map<UUID, List<String>> tags = tagsFor(rows.stream().map(OwnBundleRow::id).toList());
        return rows.stream().map(row -> new OwnBundleSummary(
                row.id().toString(), row.slug(), row.name(), row.description(), row.visibility(),
                row.appCount(), tags.getOrDefault(row.id(), List.of()), row.updatedAt(), row.version()))
                .toList();
    }

    public long count(UUID ownerId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bundles WHERE owner_id = ? AND type = 'user'",
                Long.class, ownerId.toString());
        return count == null ? 0 : count;
    }

    public OwnBundleDetails details(UUID ownerId, String publicId) {
        OwnBundleRow row = requireOwned(ownerId, publicId);
        BundleDtos.BundleDetails details = publicBundles.detailsInternal(row.id().toString());
        return new OwnBundleDetails(
                details.id(), details.slug(), details.name(), details.description(),
                details.visibility(), details.appCount(), details.tags(), details.apps(),
                details.updatedAt(), row.version());
    }

    @Transactional
    public OwnBundleDetails create(
            UUID ownerId, String ownerUsername, CreateOwnBundleRequest request) {
        List<UUID> appIds = resolveAppIds(request.appIds());
        List<String> tags = normalizedTags(request.tags());
        String baseSlug = normalizeSlug(textOr(request.slug(), request.name()));
        String slug = uniqueSlug(baseSlug);
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        try {
            jdbc.update(
                    """
                    INSERT INTO bundles
                    (id, slug, name, description, type, visibility, owner_username, owner_id,
                     star_count, app_count, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, 'user', 'private', ?, ?, 0, ?, ?, ?, 0)
                    """,
                    UuidBytes.fromUuid(id), slug, request.name().strip(), request.description(),
                    ownerUsername, ownerId.toString(), appIds.size(), now, now);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("bundle_slug_exists", "Ya existe un bundle con ese slug.");
        }
        insertTags(id, tags);
        insertItems(id, appIds);
        return details(ownerId, id.toString());
    }

    @Transactional
    public OwnBundleDetails update(
            UUID ownerId, String publicId, UpdateOwnBundleRequest request) {
        OwnBundleRow current = requireOwned(ownerId, publicId);
        List<UUID> appIds = resolveAppIds(request.appIds());
        List<String> tags = normalizedTags(request.tags());
        String visibility = normalizedUserVisibility(request.visibility());
        String nextSlug = normalizeSlug(textOr(request.slug(), current.slug()));
        if (!nextSlug.equals(current.slug()) && existsSlug(nextSlug)) {
            throw new ConflictException("bundle_slug_exists", "Ya existe un bundle con ese slug.");
        }
        int updated;
        try {
            updated = jdbc.update(
                    """
                    UPDATE bundles
                    SET slug = ?, name = ?, description = ?, visibility = ?, app_count = ?,
                        updated_at = ?, version = version + 1
                    WHERE id = ? AND owner_id = ? AND type = 'user' AND version = ?
                    """,
                    nextSlug, request.name().strip(), request.description(), visibility,
                    appIds.size(), LocalDateTime.now(), UuidBytes.fromUuid(current.id()),
                    ownerId.toString(), request.expectedVersion());
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("bundle_slug_exists", "Ya existe un bundle con ese slug.");
        }
        if (updated == 0) {
            if (ownedExists(ownerId, current.id())) {
                throw new ConflictException("bundle_conflict", "El bundle ha cambiado; recarga antes de guardar.");
            }
            throw notFound();
        }
        jdbc.update("DELETE FROM bundle_tags WHERE bundle_id = ?", UuidBytes.fromUuid(current.id()));
        jdbc.update("DELETE FROM bundle_items WHERE bundle_id = ?", UuidBytes.fromUuid(current.id()));
        insertTags(current.id(), tags);
        insertItems(current.id(), appIds);
        return details(ownerId, current.id().toString());
    }

    @Transactional
    public void delete(UUID ownerId, String publicId) {
        OwnBundleRow current = requireOwned(ownerId, publicId);
        int deleted = jdbc.update(
                "DELETE FROM bundles WHERE id = ? AND owner_id = ? AND type = 'user'",
                UuidBytes.fromUuid(current.id()), ownerId.toString());
        if (deleted == 0) throw notFound();
    }

    private OwnBundleRow requireOwned(UUID ownerId, String publicId) {
        byte[] binaryId = uuidBytesOrNull(publicId);
        List<OwnBundleRow> rows = jdbc.query(
                """
                SELECT id, slug, name, description, visibility, app_count, updated_at, version
                FROM bundles
                WHERE owner_id = ? AND type = 'user'
                  AND ((? IS NOT NULL AND id = ?) OR slug = ?)
                LIMIT 1
                """,
                (rs, rowNum) -> new OwnBundleRow(
                        UuidBytes.toUuid(rs.getBytes("id")), rs.getString("slug"),
                        rs.getString("name"), rs.getString("description"),
                        rs.getString("visibility"), rs.getInt("app_count"),
                        rs.getTimestamp("updated_at").toLocalDateTime(), rs.getLong("version")),
                ownerId.toString(), binaryId, binaryId, publicId);
        if (rows.isEmpty()) throw notFound();
        return rows.getFirst();
    }

    private boolean ownedExists(UUID ownerId, UUID id) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bundles WHERE id = ? AND owner_id = ? AND type = 'user'",
                Long.class, UuidBytes.fromUuid(id), ownerId.toString());
        return count != null && count > 0;
    }

    private List<UUID> resolveAppIds(List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (values != null) values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .forEach(unique::add);
        if (unique.size() > MAX_APPS) {
            throw new ConflictException("bundle_too_large", "Un bundle no puede contener más de 100 aplicaciones.");
        }
        return unique.stream().map(catalog::publicSoftwareAppId).toList();
    }

    private List<String> normalizedTags(List<String> values) {
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        if (values != null) values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .forEach(value -> unique.putIfAbsent(value.toLowerCase(Locale.ROOT), value));
        if (unique.size() > 30) throw new BadRequestException("too_many_tags", "Se admiten como máximo 30 etiquetas.");
        return List.copyOf(unique.values());
    }

    private void insertTags(UUID bundleId, List<String> tags) {
        LocalDateTime now = LocalDateTime.now();
        for (String tag : tags) {
            jdbc.update(
                    "INSERT INTO bundle_tags (id, bundle_id, tag, normalized_tag, created_at) VALUES (?, ?, ?, ?, ?)",
                    UuidBytes.fromUuid(UUID.randomUUID()), UuidBytes.fromUuid(bundleId), tag,
                    tag.toLowerCase(Locale.ROOT), now);
        }
    }

    private void insertItems(UUID bundleId, List<UUID> appIds) {
        LocalDateTime now = LocalDateTime.now();
        int order = 0;
        for (UUID appId : appIds) {
            jdbc.update(
                    "INSERT INTO bundle_items (id, bundle_id, software_app_id, sort_order, created_at) VALUES (?, ?, ?, ?, ?)",
                    UuidBytes.fromUuid(UUID.randomUUID()), UuidBytes.fromUuid(bundleId),
                    UuidBytes.fromUuid(appId), order++, now);
        }
    }

    private Map<UUID, List<String>> tagsFor(List<UUID> bundleIds) {
        if (bundleIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(bundleIds.size(), "?"));
        List<Object> parameters = bundleIds.stream().map(UuidBytes::fromUuid).map(value -> (Object) value).toList();
        Map<UUID, List<String>> result = new LinkedHashMap<>();
        jdbc.query(
                "SELECT bundle_id, tag FROM bundle_tags WHERE bundle_id IN (" + placeholders + ") ORDER BY tag",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result
                        .computeIfAbsent(UuidBytes.toUuid(rs.getBytes("bundle_id")), ignored -> new ArrayList<>())
                        .add(rs.getString("tag")),
                parameters.toArray());
        return result;
    }

    private String uniqueSlug(String base) {
        String candidate = base;
        int suffix = 2;
        while (existsSlug(candidate)) candidate = base + "-" + suffix++;
        return candidate;
    }

    private boolean existsSlug(String slug) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM bundles WHERE slug = ?", Long.class, slug);
        return count != null && count > 0;
    }

    private String normalizeSlug(String value) {
        String slug = (value == null ? "" : value).strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "bundle-" + UUID.randomUUID() : slug;
    }

    private String normalizedUserVisibility(String value) {
        if ("private".equals(value) || "public".equals(value)) return value;
        throw new BadRequestException("invalid_bundle_visibility", "La visibilidad debe ser private o public.");
    }

    private String textOr(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private byte[] uuidBytesOrNull(String value) {
        try {
            return value == null ? null : UuidBytes.fromUuid(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private NotFoundException notFound() {
        return new NotFoundException("bundle_not_found", "El bundle no existe.");
    }

    private record OwnBundleRow(
            UUID id,
            String slug,
            String name,
            String description,
            String visibility,
            int appCount,
            LocalDateTime updatedAt,
            long version) {}
}
