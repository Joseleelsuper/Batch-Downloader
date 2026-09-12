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

/**
 * Gestiona bundles personales privados o públicos y evita sobrescrituras concurrentes mediante
 * propiedad UUID y versión esperada.
 *
 * @see es.ubu.batchdownloader.bundle.BundleRepository
 * @see es.ubu.batchdownloader.bundle.UserBundleController
 * @since 0.1.0
 * @version 0.1.0
 * @category Bundles
 */
@Repository
public class UserBundleRepository {
    private static final int MAX_APPS = 100;
    private final JdbcTemplate jdbc;
    private final CatalogRepository catalog;
    private final BundleRepository publicBundles;

    /**
     * Conecta SQL, resolución de aplicaciones públicas y enriquecimiento compartido del detalle.
     *
     * @param jdbc Acceso SQL que participa en la transacción del llamador.
     * @param catalog Consulta del catálogo para resolver identidades y enriquecer aplicaciones
     *     mediante lotes.
     * @param publicBundles Consultas compartidas de detalle y enriquecimiento, utilizadas solo
     *     después de comprobar propiedad.
     */
    public UserBundleRepository(
            JdbcTemplate jdbc, CatalogRepository catalog, BundleRepository publicBundles) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.publicBundles = publicBundles;
    }

    /**
     * Consulta la página de bundles user del propietario y carga sus etiquetas en lote.
     *
     * @param ownerId UUID canónico de la cuenta propietaria; null para bundles sin propietario
     *     asignado.
     * @param page Página numerada desde uno; los controladores acotan valores inferiores.
     * @param pageSize Elementos por página; los controladores limitan el rango a 1–60.
     * @return resúmenes personales por fecha de actualización descendente.
     */
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

    /**
     * Cuenta todos los bundles user pertenecientes al UUID, cualquiera que sea su visibilidad.
     *
     * @param ownerId UUID canónico de la cuenta propietaria; null para bundles sin propietario
     *     asignado.
     * @return total personal antes de paginar.
     */
    public long count(UUID ownerId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bundles WHERE owner_id = ? AND type = 'user'",
                Long.class, ownerId.toString());
        return count == null ? 0 : count;
    }

    /**
     * Exige propiedad antes de enriquecer el detalle y conserva la versión usada por el editor.
     *
     * @param ownerId UUID canónico de la cuenta propietaria; null para bundles sin propietario
     *     asignado.
     * @param publicId UUID textual o slug del bundle solicitado.
     * @return detalle personal.
     * @throws es.ubu.batchdownloader.common.NotFoundException si falta el bundle o pertenece a otra
     *     cuenta.
     */
    public OwnBundleDetails details(UUID ownerId, String publicId) {
        OwnBundleRow row = requireOwned(ownerId, publicId);
        BundleDtos.BundleDetails details = publicBundles.detailsInternal(row.id().toString());
        return new OwnBundleDetails(
                details.id(), details.slug(), details.name(), details.description(),
                details.visibility(), details.appCount(), details.tags(), details.apps(),
                details.updatedAt(), row.version());
    }

    /**
     * Resuelve aplicaciones públicas y etiquetas, genera un slug libre y guarda bundle privado,
     * etiquetas e items de forma atómica.
     *
     * @param ownerId UUID canónico de la cuenta propietaria; null para bundles sin propietario
     *     asignado.
     * @param request Datos validados del bundle y su selección; las escrituras personales incluyen
     *     control de versión.
     * @return detalle personal creado.
     * @throws es.ubu.batchdownloader.common.ConflictException si se excede la selección o una
     *     inserción encuentra un slug ocupado.
     */
    @Transactional
    public OwnBundleDetails create(
            UUID ownerId, CreateOwnBundleRequest request) {
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
                    (id, slug, name, description, type, visibility, owner_id,
                     star_count, app_count, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, 'user', 'private', ?, 0, ?, ?, ?, 0)
                    """,
                    UuidBytes.fromUuid(id), slug, request.name().strip(), request.description(),
                    ownerId.toString(), appIds.size(), now, now);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("bundle_slug_exists", "Ya existe un bundle con ese slug.");
        }
        insertTags(id, tags);
        insertItems(id, appIds);
        return details(ownerId, id.toString());
    }

    /**
     * Exige propietario y versión antes de reemplazar etiquetas e items; un conflicto conserva
     * intacta la selección anterior.
     *
     * @param ownerId UUID canónico de la cuenta propietaria; null para bundles sin propietario
     *     asignado.
     * @param publicId UUID textual o slug del bundle solicitado.
     * @param request Datos validados del bundle y su selección; las escrituras personales incluyen
     *     control de versión.
     * @return detalle con versión incrementada.
     * @throws es.ubu.batchdownloader.common.NotFoundException si el bundle ya no pertenece a la
     *     cuenta o desaparece.
     * @throws es.ubu.batchdownloader.common.ConflictException si cambió la versión o el slug ya
     *     está ocupado.
     */
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

    /**
     * Comprueba la propiedad y la vuelve a exigir en el DELETE para no eliminar recursos ajenos.
     *
     * @param ownerId UUID canónico de la cuenta propietaria; null para bundles sin propietario
     *     asignado.
     * @param publicId UUID textual o slug del bundle solicitado.
     */
    @Transactional
    public void delete(UUID ownerId, String publicId) {
        OwnBundleRow current = requireOwned(ownerId, publicId);
        int deleted = jdbc.update(
                "DELETE FROM bundles WHERE id = ? AND owner_id = ? AND type = 'user'",
                UuidBytes.fromUuid(current.id()), ownerId.toString());
        if (deleted == 0) throw notFound();
    }

    /**
     * Consulta el UUID o slug dentro de los bundles user del propietario, ocultando los ajenos como
     * ausentes.
     *
     * @param ownerId UUID canónico de la cuenta propietaria; null para bundles sin propietario
     *     asignado.
     * @param publicId UUID textual o slug del bundle solicitado.
     * @return fila personal con su versión.
     * @throws es.ubu.batchdownloader.common.NotFoundException si no existe un bundle propio
     *     coincidente.
     */
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

    /**
     * Comprueba si la fila sigue perteneciendo al usuario tras una actualización que no afectó
     * registros.
     *
     * @param ownerId UUID canónico de la cuenta propietaria; null para bundles sin propietario
     *     asignado.
     * @param id UUID estable del bundle.
     * @return true permite distinguir versión obsoleta de recurso desaparecido.
     */
    private boolean ownedExists(UUID ownerId, UUID id) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bundles WHERE id = ? AND owner_id = ? AND type = 'user'",
                Long.class, UuidBytes.fromUuid(id), ownerId.toString());
        return count != null && count > 0;
    }

    /**
     * Recorta y deduplica identificadores antes de resolver exclusivamente aplicaciones públicas
     * del catálogo.
     *
     * @param values Valores recibidos del cliente antes de recortar, deduplicar y validar su
     *     selección.
     * @return UUID en el orden solicitado.
     * @throws es.ubu.batchdownloader.common.ConflictException si hay más de cien identificadores
     *     distintos.
     */
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

    /**
     * Conserva la primera etiqueta de cada texto recortado sin distinguir mayúsculas y exige como
     * máximo treinta.
     *
     * @param values Valores recibidos del cliente antes de recortar, deduplicar y validar su
     *     selección.
     * @return etiquetas únicas con su primera grafía visible.
     * @throws es.ubu.batchdownloader.common.BadRequestException si el conjunto supera treinta
     *     etiquetas.
     */
    private List<String> normalizedTags(List<String> values) {
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        if (values != null) values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .forEach(value -> unique.putIfAbsent(value.toLowerCase(Locale.ROOT), value));
        if (unique.size() > 30) throw new BadRequestException("too_many_tags", "Se admiten como máximo 30 etiquetas.");
        return List.copyOf(unique.values());
    }

    /**
     * Inserta etiquetas ya validadas con su clave en minúsculas y una fecha compartida para todo el
     * lote.
     *
     * @param bundleId UUID del bundle, o su UUID textual o slug cuando así lo exige la ruta
     *     pública.
     * @param tags Etiquetas visibles del bundle, normalizadas para comparar y guardar según su
     *     flujo.
     */
    private void insertTags(UUID bundleId, List<String> tags) {
        LocalDateTime now = LocalDateTime.now();
        for (String tag : tags) {
            jdbc.update(
                    "INSERT INTO bundle_tags (id, bundle_id, tag, normalized_tag, created_at) VALUES (?, ?, ?, ?, ?)",
                    UuidBytes.fromUuid(UUID.randomUUID()), UuidBytes.fromUuid(bundleId), tag,
                    tag.toLowerCase(Locale.ROOT), now);
        }
    }

    /**
     * Inserta los UUID ya resueltos con orden consecutivo desde cero y una fecha común.
     *
     * @param bundleId UUID del bundle, o su UUID textual o slug cuando así lo exige la ruta
     *     pública.
     * @param appIds Identificadores o slugs de aplicaciones en el orden solicitado; se admite un
     *     máximo de cien.
     */
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

    /**
     * Recupera etiquetas de todos los bundles de la página con una consulta y orden alfabético.
     *
     * @param bundleIds UUID de los bundles de la página que se enriquecen mediante una sola
     *     consulta de etiquetas.
     * @return etiquetas indexadas por UUID; mapa vacío para una página vacía.
     */
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

    /**
     * Conserva el slug base si está libre y prueba sufijos numéricos desde dos cuando está ocupado.
     *
     * @param base Slug normalizado que se intenta reservar primero sin sufijo.
     * @return primer candidato libre en el momento de la consulta; la escritura mantiene la
     *     restricción de unicidad.
     */
    private String uniqueSlug(String base) {
        String candidate = base;
        int suffix = 2;
        while (existsSlug(candidate)) candidate = base + "-" + suffix++;
        return candidate;
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
     * Recorta y normaliza a letras ASCII minúsculas, dígitos y guiones; genera bundle-UUID si no
     * queda contenido.
     *
     * @param value Texto de entrada que se normaliza según el contrato de la operación.
     * @return slug candidato que aún debe comprobarse contra colisiones.
     */
    private String normalizeSlug(String value) {
        String slug = (value == null ? "" : value).strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "bundle-" + UUID.randomUUID() : slug;
    }

    /**
     * Admite exclusivamente private o public para impedir que un usuario publique como official.
     *
     * @param value Texto de entrada que se normaliza según el contrato de la operación.
     * @return visibilidad personal validada.
     * @throws es.ubu.batchdownloader.common.BadRequestException si el valor no es private ni
     *     public.
     */
    private String normalizedUserVisibility(String value) {
        if ("private".equals(value) || "public".equals(value)) return value;
        throw new BadRequestException("invalid_bundle_visibility", "La visibilidad debe ser private o public.");
    }

    /**
     * Selecciona el texto preferido únicamente si está presente y no es blanco.
     *
     * @param preferred Texto preferido; si es null o blanco se utiliza la alternativa.
     * @param fallback Texto alternativo cuando falta una preferencia válida.
     * @return preferencia o alternativa sin normalización adicional.
     */
    private String textOr(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    /**
     * Permite buscar por UUID binario cuando el identificador tiene ese formato y por slug en los
     * demás casos.
     *
     * @param value Texto de entrada que se normaliza según el contrato de la operación.
     * @return dieciséis bytes o null si falta o no es un UUID.
     */
    private byte[] uuidBytesOrNull(String value) {
        try {
            return value == null ? null : UuidBytes.fromUuid(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Oculta de forma uniforme ausencia y falta de propiedad de un bundle personal.
     *
     * @return error bundle_not_found.
     */
    private NotFoundException notFound() {
        return new NotFoundException("bundle_not_found", "El bundle no existe.");
    }

    /**
     * Transporta metadatos y versión de un bundle cuya propiedad se consulta mediante UUID
     * canónico.
     *
     * @param id UUID estable del bundle.
     * @param slug Identificador legible del bundle dentro de las rutas públicas.
     * @param name Nombre visible del conjunto de aplicaciones.
     * @param description Descripción opcional de la finalidad del bundle.
     * @param visibility Visibilidad public, private u official; las ediciones personales solo
     *     admiten public o private.
     * @param appCount Número de aplicaciones del bundle; las proyecciones públicas cuentan las
     *     activas.
     * @param updatedAt Fecha del último cambio persistido del bundle.
     * @param version Versión persistida que la siguiente edición personal debe devolver como
     *     expectedVersion.
     * @since 0.1.0
     * @version 0.1.0
     * @category Bundles
     */
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
