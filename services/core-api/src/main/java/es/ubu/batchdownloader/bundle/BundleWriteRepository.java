package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.bundle.BundleDtos.BundleDetails;
import es.ubu.batchdownloader.bundle.BundleDtos.UpsertBundleRequest;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Ejecuta las mutaciones transaccionales de bundles y sus relaciones. */
@Repository
public class BundleWriteRepository {
    private final JdbcTemplate jdbc;
    private final CatalogRepository catalog;
    private final BundleReadRepository reads;

    /** Inicializa las escrituras con sus dependencias de resolución y proyección. */
    public BundleWriteRepository(
            JdbcTemplate jdbc,
            CatalogRepository catalog,
            BundleReadRepository reads) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.reads = reads;
    }

    /** Crea un bundle y todas sus relaciones en una única transacción. */
    @Transactional
    public BundleDetails create(UpsertBundleRequest request, UUID ownerId) {
        String requestedSlug = BundleValues.normalizeSlug(
                request.slug() == null || request.slug().isBlank() ? request.name() : request.slug());
        if (request.slug() != null && !request.slug().isBlank() && existsSlug(requestedSlug)) {
            throw new ConflictException("bundle_slug_exists", "Ya existe un bundle con ese slug.");
        }
        String slug = request.slug() == null || request.slug().isBlank()
                ? uniqueSlug(requestedSlug)
                : requestedSlug;
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                """
                INSERT INTO bundles
                (id, slug, name, description, type, visibility, owner_id,
                 star_count, app_count, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, 0)
                """,
                UuidBytes.fromUuid(id),
                slug,
                request.name().trim(),
                request.description(),
                BundleValues.normalizedType(request.type()),
                BundleValues.normalizedVisibility(request.visibility()),
                ownerId.toString(),
                now,
                now);
        replaceTags(id, request.tags());
        replaceItems(id, request.appIds());
        return reads.detailsInternal(slug);
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
        String nextSlug = BundleValues.normalizeSlug(
                request.slug() == null || request.slug().isBlank() ? currentSlug : request.slug());
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
                BundleValues.normalizedType(request.type()),
                BundleValues.normalizedVisibility(request.visibility()),
                LocalDateTime.now(),
                UuidBytes.fromUuid(id));
        replaceTags(id, request.tags());
        replaceItems(id, request.appIds());
        return reads.detailsInternal(nextSlug);
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
        if (requested.size() > BundleValues.MAX_BUNDLE_APPS) {
            throw new ConflictException(
                    "bundle_too_large",
                    "Un bundle no puede contener más de " + BundleValues.MAX_BUNDLE_APPS
                            + " aplicaciones.");
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
                BundleValues.uuidBytesOrNull(publicId),
                BundleValues.uuidBytesOrNull(publicId),
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

}
