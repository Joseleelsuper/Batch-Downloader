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

/**
 * Consulta bundles con políticas de visibilidad y enriquece sus listados mediante lotes para evitar
 * consultas por cada tarjeta.
 *
 * @see es.ubu.batchdownloader.bundle.BundleAccessPolicy
 * @see es.ubu.batchdownloader.catalog.CatalogRepository
 * @see es.ubu.batchdownloader.bundle.BundleRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Bundles
 */
@Repository
public class BundleReadRepository {
    private final JdbcTemplate jdbc;
    private final CatalogRepository catalog;

    /**
     * Conecta SQL de bundles con el enriquecimiento por lotes del catálogo.
     *
     * @param jdbc Acceso SQL que participa en la transacción del llamador.
     * @param catalog Consulta del catálogo para resolver identidades y enriquecer aplicaciones
     *     mediante lotes.
     */
    public BundleReadRepository(JdbcTemplate jdbc, CatalogRepository catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    /**
     * Selecciona bundles públicos u oficiales; community incluye también los de tipo user y el
     * enriquecimiento se realiza por página.
     *
     * @param type Tipo de bundle; null o blanco no filtra. La consulta pública trata community como
     *     community o user.
     * @param sort stars prioriza estrellas y fecha; cualquier otro valor ordena por actualización
     *     descendente.
     * @param page Página numerada desde uno; los controladores acotan valores inferiores.
     * @param pageSize Elementos por página; los controladores limitan el rango a 1–60.
     * @return resúmenes en el orden solicitado.
     */
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
     * Cuenta los mismos tipos y visibilidades que el listado público, sin paginación.
     *
     * @param type Tipo de bundle; null o blanco no filtra. La consulta pública trata community como
     *     community o user.
     * @return total público filtrado.
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
     * Selecciona una página de cualquier visibilidad y aplica el tipo literalmente, sin expandir
     * community a user.
     *
     * @param type Tipo de bundle; null o blanco no filtra. La consulta pública trata community como
     *     community o user.
     * @param sort stars prioriza estrellas y fecha; cualquier otro valor ordena por actualización
     *     descendente.
     * @param page Página numerada desde uno; los controladores acotan valores inferiores.
     * @param pageSize Elementos por página; los controladores limitan el rango a 1–60.
     * @return resúmenes administrativos enriquecidos por lotes.
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
     * Cuenta bundles de cualquier visibilidad con el filtro literal de tipo del listado
     * administrativo.
     *
     * @param type Tipo de bundle; null o blanco no filtra. La consulta pública trata community como
     *     community o user.
     * @return total administrativo filtrado.
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
     * Carga el detalle por UUID o slug y aplica la misma regla de visibilidad utilizada al
     * descargar.
     *
     * @param publicId UUID textual o slug del bundle solicitado.
     * @param viewerId UUID de quien consulta, o null para visitantes anónimos.
     * @param administrator Permite al administrador consultar bundles de cualquier visibilidad.
     * @return detalle accesible.
     * @throws es.ubu.batchdownloader.common.NotFoundException si falta el bundle o es privado para
     *     otro propietario.
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
     * Carga un detalle sin comprobar identidad; solo debe usarse desde una operación que ya
     * autorizó el recurso.
     *
     * @param publicId UUID textual o slug del bundle solicitado.
     * @return detalle del bundle existente.
     * @throws es.ubu.batchdownloader.common.NotFoundException si no existe el UUID o slug.
     */
    public BundleDetails detailsInternal(String publicId) {
        return findBundle(publicId).details();
    }

    /**
     * Consulta solo acceso e identidades de aplicaciones activas y conserva su orden; materializa
     * como máximo 101 para detectar exceso.
     *
     * @param publicId UUID textual o slug del bundle solicitado.
     * @param viewerId UUID de quien consulta, o null para visitantes anónimos.
     * @param administrator Permite al administrador consultar bundles de cualquier visibilidad.
     * @return hasta cien UUID de aplicaciones.
     * @throws es.ubu.batchdownloader.common.NotFoundException si el bundle no existe o no es
     *     accesible.
     * @throws es.ubu.batchdownloader.common.ConflictException si contiene más de cien aplicaciones
     *     activas.
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
     * Carga únicamente UUID, visibilidad y propietario para autorizar una descarga sin enriquecer
     * tarjetas.
     *
     * @param publicId UUID textual o slug del bundle solicitado.
     * @return datos mínimos de acceso.
     * @throws es.ubu.batchdownloader.common.NotFoundException si el UUID o slug no existe.
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
     * Carga el detalle y la identidad propietaria para aplicar después la política de visibilidad.
     *
     * @param publicId UUID textual o slug del bundle solicitado.
     * @return detalle acompañado de su propietario.
     * @throws es.ubu.batchdownloader.common.NotFoundException si no se encuentra UUID ni slug.
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

    /**
     * Lee los metadatos comunes de una fila sin consultar todavía etiquetas ni aplicaciones.
     *
     * @param rs Fila SQL posicionada en un registro de bundle o aplicación.
     * @return datos base del resumen.
     * @throws java.sql.SQLException si una columna no puede leerse con el tipo esperado.
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
     * Carga etiquetas y aplicaciones de toda la página, deduplica muestras y consulta su proyección
     * de catálogo una sola vez.
     * Conserva orden de bundles y de aplicaciones y limita las muestras globales y por plataforma a
     * seis.
     *
     * @param bundles Filas base de la página en el orden de presentación.
     * @return resúmenes completos con recuentos de aplicaciones activas y descargables por
     *     plataforma.
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
     * Combina metadatos de la fila con etiquetas, aplicaciones activas y disponibilidad por
     * plataforma del mismo bundle.
     *
     * @param rs Fila SQL posicionada en un registro de bundle o aplicación.
     * @return detalle completo del bundle.
     * @throws java.sql.SQLException si no puede leer las columnas de metadatos.
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
     * Carga UUID de aplicaciones activas en el orden del bundle y enriquece la selección mediante
     * una consulta de catálogo por lote.
     *
     * @param bundleId UUID del bundle, o su UUID textual o slug cuando así lo exige la ruta
     *     pública.
     * @param limit Máximo de aplicaciones de muestra; cero o negativo carga todas las activas.
     * @return aplicaciones proyectadas, omitiendo las que dejaron de estar disponibles.
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
     * Consulta las etiquetas del bundle ordenadas por su texto.
     *
     * @param bundleId UUID del bundle, o su UUID textual o slug cuando así lo exige la ruta
     *     pública.
     * @return lista de etiquetas visibles.
     */
    private List<String> tags(UUID bundleId) {
        return jdbc.queryForList(
                "SELECT tag FROM bundle_tags WHERE bundle_id = ? ORDER BY tag",
                String.class,
                UuidBytes.fromUuid(bundleId));
    }

    /**
     * Proyecta las plataformas con al menos un instalador seleccionable del bundle.
     *
     * @param bundleId UUID del bundle, o su UUID textual o slug cuando así lo exige la ruta
     *     pública.
     * @return plataformas en orden windows, linux, macos.
     */
    List<String> availableOperatingSystems(UUID bundleId) {
        return platformAvailability(bundleId).stream()
                .map(PlatformAvailability::operatingSystem)
                .toList();
    }

    /**
     * Agrupa instaladores seleccionables por plataforma, sin excluirlos solo por antigüedad, y
     * enriquece seis muestras por plataforma en un lote.
     *
     * @param bundleId UUID del bundle, o su UUID textual o slug cuando así lo exige la ruta
     *     pública.
     * @return recuentos y muestras por plataforma.
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
     * Cuenta elementos del bundle cuya aplicación continúa activa en el catálogo.
     *
     * @param bundleId UUID del bundle, o su UUID textual o slug cuando así lo exige la ruta
     *     pública.
     * @return número de aplicaciones activas o cero si no hay resultado.
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
     * Interpreta una columna UUID textual opcional de propietario.
     *
     * @param row Fila SQL de la que se lee una columna de identidad.
     * @param column Nombre de la columna que guarda un UUID textual o null.
     * @return UUID o null si la columna es nula o blanca.
     * @throws java.sql.SQLException si falla la lectura de la columna.
     * @throws IllegalArgumentException si el texto presente no es un UUID.
     */
    private UUID nullableUuid(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    /**
     * Asocia el detalle enriquecido con el propietario necesario para autorizar su lectura.
     *
     * @param details Proyección completa del bundle que acompaña a los datos de acceso.
     * @param ownerId UUID canónico de la cuenta propietaria; null para bundles sin propietario
     *     asignado.
     * @since 0.1.0
     * @version 0.1.0
     * @category Bundles
     */
    private record BundleRecord(BundleDetails details, UUID ownerId) {}

    /**
     * Conserva metadatos de una fila antes de enriquecer la página con etiquetas y aplicaciones.
     *
     * @param id UUID estable del bundle.
     * @param slug Identificador legible del bundle dentro de las rutas públicas.
     * @param name Nombre visible del conjunto de aplicaciones.
     * @param description Descripción opcional de la finalidad del bundle.
     * @param type Tipo de bundle; null o blanco no filtra. La consulta pública trata community como
     *     community o user.
     * @param visibility Visibilidad public, private u official; las ediciones personales solo
     *     admiten public o private.
     * @param starCount Estrellas registradas para ordenar y presentar el bundle.
     * @param updatedAt Fecha del último cambio persistido del bundle.
     * @since 0.1.0
     * @version 0.1.0
     * @category Bundles
     */
    private record BundleBase(
            UUID id,
            String slug,
            String name,
            String description,
            String type,
            String visibility,
            int starCount,
            LocalDateTime updatedAt) {}

    /**
     * Acota la consulta de autorización de descargas a UUID, visibilidad y propietario.
     *
     * @param id UUID estable del bundle.
     * @param visibility Visibilidad public, private u official; las ediciones personales solo
     *     admiten public o private.
     * @param ownerId UUID canónico de la cuenta propietaria; null para bundles sin propietario
     *     asignado.
     * @since 0.1.0
     * @version 0.1.0
     * @category Bundles
     */
    private record BundleAccess(UUID id, String visibility, UUID ownerId) {}
}
