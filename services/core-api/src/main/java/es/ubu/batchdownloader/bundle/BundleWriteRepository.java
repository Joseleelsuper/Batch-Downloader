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

/**
 * Guarda metadatos, etiquetas y selección administrativa de bundles en una misma transacción y
 * conserva el orden de sus aplicaciones.
 *
 * @see es.ubu.batchdownloader.bundle.BundleReadRepository
 * @see es.ubu.batchdownloader.bundle.BundleValues
 * @since 0.1.0
 * @version 0.1.0
 * @category Bundles
 */
@Repository
public class BundleWriteRepository {
    private final JdbcTemplate jdbc;
    private final CatalogRepository catalog;
    private final BundleReadRepository reads;

    /**
     * Conecta SQL transaccional, resolución de aplicaciones y lectura del detalle actualizado.
     *
     * @param jdbc Acceso SQL que participa en la transacción del llamador.
     * @param catalog Consulta del catálogo para resolver identidades y enriquecer aplicaciones
     *     mediante lotes.
     * @param reads Consultas y proyecciones de bundles utilizadas después de confirmar sus datos en
     *     la transacción.
     */
    public BundleWriteRepository(
            JdbcTemplate jdbc,
            CatalogRepository catalog,
            BundleReadRepository reads) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.reads = reads;
    }

    /**
     * Reserva UUID y slug, guarda propietario y metadatos y sustituye etiquetas e items en la misma
     * transacción.
     *
     * @param request Datos validados del bundle y su selección; las escrituras personales incluyen
     *     control de versión.
     * @param ownerId UUID canónico de la cuenta propietaria; null para bundles sin propietario
     *     asignado.
     * @return detalle creado.
     * @throws es.ubu.batchdownloader.common.ConflictException si el slug explícito está ocupado o
     *     la selección supera cien aplicaciones.
     */
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
     * Conserva el slug anterior cuando no se indica otro y reemplaza metadatos, etiquetas e items
     * incrementando la versión.
     *
     * @param publicId UUID textual o slug del bundle solicitado.
     * @param request Datos validados del bundle y su selección; las escrituras personales incluyen
     *     control de versión.
     * @return detalle guardado.
     * @throws es.ubu.batchdownloader.common.NotFoundException si no existe el bundle.
     * @throws es.ubu.batchdownloader.common.ConflictException si el nuevo slug está ocupado o la
     *     selección excede cien aplicaciones.
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
     * Resuelve el UUID o slug y elimina el bundle dentro de una transacción.
     *
     * @param publicId UUID textual o slug del bundle solicitado.
     */
    @Transactional
    public void delete(String publicId) {
        UUID id = idByPublicId(publicId);
        jdbc.update("DELETE FROM bundles WHERE id = ?", UuidBytes.fromUuid(id));
    }

    /**
     * Elimina las etiquetas anteriores e inserta las recibidas no nulas ni blancas, deduplicadas
     * antes de recortar y normalizar.
     *
     * @param bundleId UUID del bundle, o su UUID textual o slug cuando así lo exige la ruta
     *     pública.
     * @param tags Etiquetas visibles del bundle, normalizadas para comparar y guardar según su
     *     flujo.
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
     * Resuelve hasta cien aplicaciones antes de borrar las anteriores, inserta su orden y actualiza
     * app_count en la misma transacción.
     *
     * @param bundleId UUID del bundle, o su UUID textual o slug cuando así lo exige la ruta
     *     pública.
     * @param appIds Identificadores o slugs de aplicaciones en el orden solicitado; se admite un
     *     máximo de cien.
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
     * Resuelve UUID o slug a la identidad binaria del bundle antes de escribir.
     *
     * @param publicId UUID textual o slug del bundle solicitado.
     * @return UUID existente.
     * @throws es.ubu.batchdownloader.common.NotFoundException si no se encuentra el identificador.
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
     * Consulta el slug actual del UUID para conservarlo o comprobar su cambio.
     *
     * @param id UUID estable del bundle.
     * @return slug guardado.
     * @throws es.ubu.batchdownloader.common.NotFoundException si la consulta devuelve un slug nulo.
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
     * Comprueba si un slug está ocupado por cualquier bundle.
     *
     * @param slug Identificador legible del bundle dentro de las rutas públicas.
     * @return true si existe al menos una coincidencia.
     */
    private boolean existsSlug(String slug) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM bundles WHERE slug = ?", Long.class, slug);
        return count != null && count > 0;
    }

    /**
     * Conserva el slug base si está libre y prueba sufijos numéricos desde dos cuando está ocupado.
     *
     * @param baseSlug Slug normalizado al que se añadirán sufijos si ya está ocupado.
     * @return primer candidato libre en el momento de la consulta; la escritura mantiene la
     *     restricción de unicidad.
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
