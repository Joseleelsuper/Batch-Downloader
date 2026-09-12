package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminCatalogDtos.PatchAppRequest;
import es.ubu.batchdownloader.admin.AdminCatalogDtos.PatchSourceRequest;
import es.ubu.batchdownloader.admin.AdminCatalogDtos.UpsertAppRequest;
import es.ubu.batchdownloader.admin.InstallerAbsenceDtos.InstallerAbsenceVerification;
import es.ubu.batchdownloader.admin.InstallerAbsenceDtos.InstallerAbsenceVerificationRequest;
import es.ubu.batchdownloader.admin.InstallerAbsenceDtos.InstallerAbsenceVerificationSummary;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica altas, cambios y borrados administrativos sobre el catálogo con sus etiquetas, fuentes y
 * bundles dentro de una transacción. Delega las proyecciones y la evidencia de ausencia en
 * colaboradores específicos.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.AdminAppSourceRepository
 * @see es.ubu.batchdownloader.admin.AdminAppExportRepository
 * @see es.ubu.batchdownloader.admin.InstallerAbsenceRepository
 * @see es.ubu.batchdownloader.catalog.CatalogRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración del catálogo
 */
@Repository
public class AdminAppRepository {
    /**
     * Constante que define {@code DELETE_BATCH_SIZE}.
     */
    private static final int DELETE_BATCH_SIZE = 500;
    /**
     * Estado {@code jdbc} mantenido por {@code AdminAppRepository}.
     */
    private final JdbcTemplate jdbc;
    /**
     * Estado {@code catalog} mantenido por {@code AdminAppRepository}.
     */
    private final CatalogRepository catalog;
    /** Proyección aislada de las exportaciones administrativas. */
    private final AdminAppExportRepository exports;
    /** Persistencia aislada de las verificaciones de ausencia. */
    private final InstallerAbsenceRepository absences;
    /** Mutaciones aisladas de fuentes de descarga. */
    private final AdminAppSourceRepository sources;
    /** Reloj inyectado para escrituras reproducibles. */
    private final Clock clock;

    /**
     * Compone persistencia, proyecciones, exportación y evidencias usando el mismo reloj de
     * aplicación.
     *
     * @param jdbc Acceso SQL que participa en la transacción administrativa del llamador.
     * @param catalog Consulta de proyecciones e identificadores internos del catálogo.
     * @param exports Exportación de aplicaciones activas y referencias exactas por plataforma.
     * @param absences Registro de evidencias vigentes de ausencia de instaladores.
     * @param sources Edición de fuentes iniciales con comprobación de pertenencia a la aplicación.
     * @param clock Reloj utilizado para fechar los cambios persistidos.
     */
    public AdminAppRepository(
            JdbcTemplate jdbc,
            CatalogRepository catalog,
            AdminAppExportRepository exports,
            InstallerAbsenceRepository absences,
            AdminAppSourceRepository sources,
            Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.exports = exports;
        this.absences = absences;
        this.sources = sources;
        this.clock = clock;
    }

    /**
     * Crea una aplicación manual, sustituye sus etiquetas y añade una fuente Windows pendiente de
     * revisión; devuelve la proyección recién persistida.
     *
     * @param request Campos validados de la creación, edición o confirmación solicitada.
     * @return detalle público de la nueva aplicación, todavía sin instalador validado.
     */
    @Transactional
    public AppDetails create(UpsertAppRequest request) {
        UUID id = UUID.randomUUID();
        String slug = normalizeSlug(isBlank(request.slug()) ? request.name() : request.slug());
        String winstallId = isBlank(request.winstallId()) ? "manual." + slug : request.winstallId().trim();
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update(
                """
                INSERT INTO software_apps
                (id, winstall_id, slug, name, normalized_name, description, long_description,
                 long_description_status, publisher, icon_url, official_url, latest_version,
                 app_status, metadata_json, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, JSON_OBJECT('source', 'admin'), 0, ?, ?)
                """,
                UuidBytes.fromUuid(id),
                winstallId,
                slug,
                request.name().trim(),
                normalizeText(request.name()),
                request.description(),
                request.longDescription(),
                isBlank(request.longDescription()) ? "pending" : "completed",
                request.publisher(),
                request.iconUrl(),
                request.officialUrl(),
                request.latestVersion(),
                isBlank(request.appStatus()) ? "active" : request.appStatus(),
                now,
                now);
        replaceTags(id, request.tags(), "admin");
        createDefaultSource(id, request.officialUrl(), now);
        return catalog.details(id.toString());
    }

    /**
     * Delega la comprobación y sustitución atómica de la evidencia de ausencia de instaladores.
     *
     * @param publicId UUID público textual de la aplicación que se consulta o modifica.
     * @param request Campos validados de la creación, edición o confirmación solicitada.
     * @param actor UUID textual del administrador que confirma la evidencia.
     * @return evidencia activa vinculada a la versión comprobada.
     * @see es.ubu.batchdownloader.admin.InstallerAbsenceRepository
     */
    @Transactional
    public InstallerAbsenceVerification confirmInstallerAbsence(
            String publicId,
            InstallerAbsenceVerificationRequest request,
            String actor) {
        return absences.confirm(publicId, request, actor);
    }

    /**
     * Consulta la evidencia que todavía está activa para la aplicación indicada.
     *
     * @param publicId UUID público textual de la aplicación que se consulta o modifica.
     * @return evidencia vigente o null si no existe.
     * @see es.ubu.batchdownloader.admin.InstallerAbsenceRepository
     */
    public InstallerAbsenceVerification activeAbsenceVerification(String publicId) {
        return absences.active(publicId);
    }

    /**
     * Consulta los recuentos de ausencia confirmada y aplicaciones pendientes de evidencia.
     *
     * @return resumen administrativo de la cobertura de comprobaciones.
     */
    public InstallerAbsenceVerificationSummary absenceVerificationSummary() {
        return absences.summary();
    }

    /**
     * Actualiza metadatos conservando los campos null, recalcula nombre normalizado y estado de
     * descripción e invalida la evidencia si cambia la página oficial. Un estado de aplicación
     * vacío se interpreta como active.
     *
     * @param publicId UUID público textual de la aplicación que se consulta o modifica.
     * @param request Campos validados de la creación, edición o confirmación solicitada.
     * @return detalle público posterior a la edición.
     */
    @Transactional
    public AppDetails patch(String publicId, PatchAppRequest request) {
        UUID id = softwareAppId(publicId);
        AppDetails before = catalog.details(publicId);
        jdbc.update(
                """
                UPDATE software_apps
                SET name = ?, normalized_name = ?, publisher = ?, description = ?, long_description = ?,
                    long_description_status = ?, icon_url = ?, official_url = ?, latest_version = ?,
                    app_status = ?, updated_at = ?, version = version + 1
                WHERE id = ?
                """,
                coalesce(request.name(), before.name()),
                normalizeText(coalesce(request.name(), before.name())),
                coalesce(request.publisher(), before.publisher()),
                coalesce(request.description(), before.description()),
                coalesce(request.longDescription(), before.longDescription()),
                isBlank(coalesce(request.longDescription(), before.longDescription())) ? "pending" : "completed",
                coalesce(request.iconUrl(), before.iconUrl()),
                coalesce(request.officialUrl(), before.officialUrl()),
                coalesce(request.latestVersion(), before.latestVersion()),
                isBlank(request.appStatus()) ? "active" : request.appStatus(),
                LocalDateTime.now(clock),
                UuidBytes.fromUuid(id));
        if (request.officialUrl() != null
                && !request.officialUrl().equals(before.officialUrl())) {
            absences.invalidate(id, "official_url_changed");
        }
        return catalog.details(id.toString());
    }

    /**
     * Sustituye todas las etiquetas de la aplicación por las propuestas con origen admin.
     *
     * @param publicId UUID público textual de la aplicación que se consulta o modifica.
     * @param tags Etiquetas propuestas; null elimina todas las asociaciones existentes.
     */
    @Transactional
    public void replaceTags(String publicId, List<String> tags) {
        replaceTags(softwareAppId(publicId), tags, "admin");
    }

    /**
     * Borra una aplicación y sus relaciones cuando el scraper está inactivo y recalcula los
     * contadores de sus bundles.
     *
     * @param publicId UUID público textual de la aplicación que se consulta o modifica.
     * @throws es.ubu.batchdownloader.common.ConflictException si hay una ejecución, trabajo en cola
     *     o trabajo en curso del scraper.
     */
    @Transactional
    public void delete(String publicId) {
        assertScraperIdleForDeletion();
        UUID appId = softwareAppId(publicId);
        List<UUID> affectedBundles = jdbc.query(
                "SELECT bundle_id FROM bundle_items WHERE software_app_id = ?",
                (rs, rowNum) -> UuidBytes.toUuid(rs.getBytes("bundle_id")),
                UuidBytes.fromUuid(appId));
        deleteApps("WHERE id = ?", List.<Object>of(UuidBytes.fromUuid(appId)));
        refreshBundleCounts(affectedBundles);
    }

    /**
     * Borra el catálogo, trabajos y snapshots del scraper y pone a cero los contadores de bundles
     * cuando no hay actividad pendiente.
     *
     * @return número de aplicaciones existentes antes del borrado.
     * @throws es.ubu.batchdownloader.common.ConflictException si el scraper tiene ejecuciones o
     *     trabajos pendientes.
     */
    @Transactional
    public int deleteAll() {
        assertScraperIdleForDeletion();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM software_apps", Integer.class);
        jdbc.update("DELETE FROM scraper_worker_snapshots");
        jdbc.update("DELETE FROM scraper_metric_snapshots");
        jdbc.update("DELETE FROM scraper_work_items");
        deleteApps("", List.of());
        jdbc.update(
                "UPDATE bundles SET app_count = 0, updated_at = ? WHERE app_count <> 0",
                LocalDateTime.now(clock));
        return count == null ? 0 : count;
    }

    /**
     * Delega la exportación de aplicaciones activas con una referencia exacta por plataforma.
     *
     * @return contenido CSV y cantidad de aplicaciones incluidas.
     */
    public AppCsvExport exportCsv() {
        return exports.exportCsv();
    }

    /**
     * Delega una edición parcial sin permitir que se modifique una fuente perteneciente a otra
     * aplicación.
     *
     * @param appId Identificador de la aplicación propietaria de los datos modificados.
     * @param sourceId UUID textual de la fuente inicial que debe pertenecer a la aplicación.
     * @param request Campos validados de la creación, edición o confirmación solicitada.
     */
    @Transactional
    public void patchSource(String appId, String sourceId, PatchSourceRequest request) {
        sources.patch(appId, sourceId, request);
    }

    /**
     * Resuelve el identificador público a la clave persistida utilizada en las relaciones del
     * catálogo.
     *
     * @param publicId UUID público textual de la aplicación que se consulta o modifica.
     * @return UUID interno de la aplicación.
     * @throws es.ubu.batchdownloader.common.NotFoundException si el catálogo no puede resolver la
     *     aplicación indicada.
     */
    public UUID softwareAppId(String publicId) {
        return catalog.softwareAppId(publicId);
    }

    /**
     * Obtiene primero las claves afectadas y elimina dependencias en orden para evitar conflictos
     * de disparadores MySQL y respetar las claves foráneas.
     *
     * @param appWhereClause Cláusula WHERE interna parametrizada; vacía selecciona todas las
     *     aplicaciones.
     * @param appWhereParams Valores de la cláusula de selección en orden de aparición.
     */
    private void deleteApps(String appWhereClause, List<Object> appWhereParams) {
        String scopedApps = appWhereClause.isBlank()
                ? "SELECT id FROM software_apps"
                : "SELECT id FROM software_apps " + appWhereClause;
        List<byte[]> appIds = jdbc.query(
                scopedApps,
                (rs, rowNum) -> rs.getBytes("id"),
                appWhereParams.toArray());
        if (appIds.isEmpty()) {
            return;
        }

        List<byte[]> sourceIds = selectIdsByForeignKey("download_sources", "software_app_id", appIds);
        List<byte[]> resolvedIds = selectIdsByForeignKey("resolved_sources", "download_source_id", sourceIds);

        // Las tablas con disparadores se borran siempre por sus claves primarias.
        // DELETE ... IN (SELECT ... FROM download_sources) haría que el disparador de
        // fuentes resueltas actualizase una tabla ya usada por la sentencia, algo que
        // MySQL rechaza. Los lotes acotados también mantienen los reinicios grandes
        // por debajo del límite de parámetros del controlador y del servidor.
        deleteByForeignKey("resolver_logs", "download_source_id", sourceIds);
        deleteByIds("resolved_sources", resolvedIds);
        deleteByIds("download_sources", sourceIds);
        deleteByForeignKey("software_app_tags", "software_app_id", appIds);
        deleteByForeignKey("bundle_items", "software_app_id", appIds);
        deleteByIds("software_apps", appIds);
    }

    /**
     * Recupera en lotes las claves primarias de los registros dependientes antes de ejecutar los
     * borrados.
     *
     * @param table Nombre de tabla elegido internamente, nunca recibido de una petición.
     * @param foreignKey Columna de relación elegida internamente para localizar los registros
     *     dependientes.
     * @param ownerIds UUID binarios de los propietarios de los registros buscados.
     * @return copia inmutable de las claves encontradas; vacía si no hay propietarios.
     */
    private List<byte[]> selectIdsByForeignKey(String table, String foreignKey, List<byte[]> ownerIds) {
        if (ownerIds.isEmpty()) {
            return List.of();
        }
        List<byte[]> ids = new java.util.ArrayList<>();
        forEachDeleteBatch(ownerIds, batch -> ids.addAll(jdbc.query(
                "SELECT id FROM " + table + " WHERE " + foreignKey + " IN (" + placeholders(batch.size()) + ")",
                (rs, rowNum) -> rs.getBytes("id"),
                batch.toArray())));
        return List.copyOf(ids);
    }

    /**
     * Borra registros de una tabla interna usando su clave primaria y los límites de lote comunes.
     *
     * @param table Nombre de tabla elegido internamente, nunca recibido de una petición.
     * @param ids UUID binarios que se reparten en lotes acotados para consultar o borrar.
     */
    private void deleteByIds(String table, List<byte[]> ids) {
        deleteByForeignKey(table, "id", ids);
    }

    /**
     * Borra por una relación o clave interna en lotes acotados, sin consultas anidadas sobre tablas
     * modificadas por disparadores.
     *
     * @param table Nombre de tabla elegido internamente, nunca recibido de una petición.
     * @param column Columna interna por la que se acota el borrado.
     * @param ids UUID binarios que se reparten en lotes acotados para consultar o borrar.
     */
    private void deleteByForeignKey(String table, String column, List<byte[]> ids) {
        forEachDeleteBatch(ids, batch -> jdbc.update(
                "DELETE FROM " + table + " WHERE " + column + " IN (" + placeholders(batch.size()) + ")",
                batch.toArray()));
    }

    /**
     * Entrega sublistas consecutivas de hasta DELETE_BATCH_SIZE UUID a la operación; una lista
     * vacía no ejecuta nada.
     *
     * @param ids UUID binarios que se reparten en lotes acotados para consultar o borrar.
     * @param operation Operación que consume cada sublista, dentro de la transacción del llamador.
     */
    private void forEachDeleteBatch(List<byte[]> ids, java.util.function.Consumer<List<byte[]>> operation) {
        for (int start = 0; start < ids.size(); start += DELETE_BATCH_SIZE) {
            operation.accept(ids.subList(start, Math.min(start + DELETE_BATCH_SIZE, ids.size())));
        }
    }

    /**
     * Construye la lista de interrogantes para enlazar valores de un lote JDBC.
     *
     * @param count Número de parámetros posicionales necesarios para el lote.
     * @return parámetros separados por coma; cadena vacía para cero.
     */
    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    /**
     * Bloquea las filas de ejecuciones activas y trabajos pendientes antes de permitir que la
     * transacción borre el catálogo.
     *
     * @throws es.ubu.batchdownloader.common.ConflictException si encuentra una ejecución running o
     *     un trabajo queued o in_progress.
     */
    private void assertScraperIdleForDeletion() {
        boolean running = !jdbc.queryForList(
                "SELECT id FROM scrape_runs WHERE status = 'running' FOR UPDATE").isEmpty();
        if (running) {
            throw scraperRunningConflict();
        }
        boolean queuedOrActive = !jdbc.queryForList(
                """
                SELECT id
                FROM scraper_work_items
                WHERE status IN ('queued', 'in_progress')
                FOR UPDATE
                """).isEmpty();
        if (queuedOrActive) {
            throw scraperRunningConflict();
        }
    }

    /**
     * Construye el conflicto estable que impide borrar datos mientras el scraper puede utilizarlos.
     *
     * @return conflicto con código scraper_running.
     */
    private ConflictException scraperRunningConflict() {
        return new ConflictException(
                "scraper_running",
                "No se pueden eliminar aplicaciones mientras el scraper está en ejecución.");
    }

    /**
     * Recuenta las aplicaciones restantes de cada bundle afectado y actualiza su fecha una vez por
     * UUID distinto.
     *
     * @param bundleIds Bundles afectados; cada UUID distinto se recalcula una sola vez.
     */
    private void refreshBundleCounts(List<UUID> bundleIds) {
        for (UUID bundleId : bundleIds.stream().distinct().toList()) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM bundle_items WHERE bundle_id = ?",
                    Integer.class,
                    UuidBytes.fromUuid(bundleId));
            jdbc.update(
                    "UPDATE bundles SET app_count = ?, updated_at = ? WHERE id = ?",
                    count == null ? 0 : count,
                    LocalDateTime.now(clock),
                    UuidBytes.fromUuid(bundleId));
        }
    }

    /**
     * Crea una fuente Windows x86_64 de revisión manual y validación pendiente a partir de la
     * página oficial, sin considerarla un instalador descargable.
     *
     * @param appId Identificador de la aplicación propietaria de los datos modificados.
     * @param officialUrl Página oficial pública de la aplicación; no es un instalador resuelto.
     * @param now Fecha compartida por la creación de la aplicación y su fuente inicial.
     */
    private void createDefaultSource(UUID appId, String officialUrl, LocalDateTime now) {
        jdbc.update(
                """
                INSERT INTO download_sources
                (id, software_app_id, operating_system, architecture, initial_url, resolver_type,
                 resolver_config, resolution_status, validation_status, version, created_at, updated_at)
                VALUES (?, ?, 'windows', 'x86_64', ?, 'generic_http', JSON_OBJECT('source', 'admin'),
                        'requires_manual_review', 'unchecked', 0, ?, ?)
                """,
                UuidBytes.fromUuid(UUID.randomUUID()),
                UuidBytes.fromUuid(appId),
                officialUrl,
                now,
                now);
    }

    /**
     * Elimina asociaciones anteriores e inserta etiquetas no blancas, recortadas y normalizadas,
     * conservando el origen y evitando duplicados persistidos.
     *
     * @param appId Identificador de la aplicación propietaria de los datos modificados.
     * @param tags Etiquetas propuestas; null elimina todas las asociaciones existentes.
     * @param source Origen que se conserva junto a cada asociación de etiqueta.
     */
    private void replaceTags(UUID appId, List<String> tags, String source) {
        jdbc.update("DELETE FROM software_app_tags WHERE software_app_id = ?", UuidBytes.fromUuid(appId));
        if (tags == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        for (String tag : tags.stream().filter(value -> !isBlank(value)).distinct().toList()) {
            jdbc.update(
                    """
                    INSERT IGNORE INTO software_app_tags
                    (id, software_app_id, tag, normalized_tag, source, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    UuidBytes.fromUuid(UUID.randomUUID()),
                    UuidBytes.fromUuid(appId),
                    tag.trim(),
                    normalizeText(tag),
                    source,
                    now);
        }
    }

    /**
     * Convierte el texto a minúsculas ASCII separadas por guiones; si no queda contenido crea un
     * slug app- seguido de UUID.
     *
     * @param value Texto que se normaliza o comprueba; se admite null donde se indica.
     * @return slug no vacío para el alta administrativa.
     */
    private String normalizeSlug(String value) {
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "app-" + UUID.randomUUID() : slug;
    }

    /**
     * Recorta el texto y lo convierte a minúsculas independientes de la configuración regional.
     *
     * @param value Texto que se normaliza o comprueba; se admite null donde se indica.
     * @return texto normalizado; cadena vacía para null.
     */
    private String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Conserva el valor anterior únicamente cuando la propuesta es null.
     *
     * @param next Nuevo texto; null significa conservar el actual y una cadena vacía sí lo
     *     sustituye.
     * @param current Texto que se mantiene cuando no hay sustitución.
     * @return propuesta o valor actual, permitiendo borrar mediante una cadena vacía.
     */
    private String coalesce(String next, String current) {
        return next == null ? current : next;
    }

    /**
     * Comprueba si un campo opcional carece de texto significativo.
     *
     * @param value Texto que se normaliza o comprueba; se admite null donde se indica.
     * @return true para null, texto vacío o solo espacios.
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Transporta la exportación completa y el número de aplicaciones para la respuesta descargable
     * y su auditoría.
     *
     * @param content CSV completo con cabecera y finales de línea CRLF.
     * @param rowCount Cantidad de aplicaciones exportadas, excluida la cabecera.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración del catálogo
     */
    public record AppCsvExport(String content, int rowCount) {}
}
