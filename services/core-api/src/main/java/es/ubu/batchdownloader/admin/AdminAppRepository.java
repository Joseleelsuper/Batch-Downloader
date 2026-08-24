package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminDtos.PatchAppRequest;
import es.ubu.batchdownloader.admin.AdminDtos.PatchSourceRequest;
import es.ubu.batchdownloader.admin.AdminDtos.UpsertAppRequest;
import es.ubu.batchdownloader.admin.AdminDtos.InstallerAbsenceVerification;
import es.ubu.batchdownloader.admin.AdminDtos.InstallerAbsenceVerificationRequest;
import es.ubu.batchdownloader.admin.AdminDtos.InstallerAbsenceVerificationSummary;
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
 * Gestiona la persistencia y consulta de {@code AdminAppRepository}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Inicializa una instancia de {@code AdminAppRepository}.
     *
     * @param jdbc Valor de {@code jdbc} utilizado por la operación.
     * @param catalog Acceso al catálogo utilizado por la operación.
     * @param exports Proyección utilizada para construir exportaciones.
     * @param absences Persistencia de verificaciones de ausencia.
     * @param sources Persistencia de fuentes de descarga.
     * @param clock Reloj de aplicación.
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
     * Crea el recurso solicitado mediante {@code create}.
     *
     * @param request Solicitud recibida por la operación.
     * @return Resultado producido por {@code create}.
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
     * Registra una ausencia únicamente cuando las tres comprobaciones son concluyentes.
     *
     * @param publicId Aplicación revisada.
     * @param request Evidencia estructurada.
     * @param actor Administrador responsable.
     * @return Acta activa recién creada.
     */
    @Transactional
    public InstallerAbsenceVerification confirmInstallerAbsence(
            String publicId,
            InstallerAbsenceVerificationRequest request,
            String actor) {
        return absences.confirm(publicId, request, actor);
    }

    /** Obtiene el acta activa más reciente de una aplicación. */
    public InstallerAbsenceVerification activeAbsenceVerification(String publicId) {
        return absences.active(publicId);
    }

    /** Resume los ``missing`` sin evidencia usando la proyección autoritativa. */
    public InstallerAbsenceVerificationSummary absenceVerificationSummary() {
        return absences.summary();
    }

    /**
     * Ejecuta la operación {@code patch}.
     *
     * @param publicId Identificador de {@code public} utilizado por la operación.
     * @param request Solicitud recibida por la operación.
     * @return Resultado producido por {@code patch}.
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
     * Ejecuta la operación {@code replaceTags}.
     *
     * @param publicId Identificador de {@code public} utilizado por la operación.
     * @param tags Valor de {@code tags} utilizado por la operación.
     */
    @Transactional
    public void replaceTags(String publicId, List<String> tags) {
        replaceTags(softwareAppId(publicId), tags, "admin");
    }

    /**
     * Elimina el recurso solicitado mediante {@code delete}.
     *
     * @param publicId Identificador de {@code public} utilizado por la operación.
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
     * Elimina el recurso solicitado mediante {@code deleteAll}.
     *
     * @return Número de elementos afectados por la operación.
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
     * Ejecuta la operación {@code exportCsv}.
     *
     * @return Resultado producido por {@code exportCsv}.
     */
    public AppCsvExport exportCsv() {
        return exports.exportCsv();
    }

    /**
     * Ejecuta la operación {@code patchSource}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param sourceId Identificador de {@code source} utilizado por la operación.
     * @param request Solicitud recibida por la operación.
     * @throws NotFoundException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    @Transactional
    public void patchSource(String appId, String sourceId, PatchSourceRequest request) {
        sources.patch(appId, sourceId, request);
    }

    /**
     * Ejecuta la operación {@code softwareAppId}.
     *
     * @param publicId Identificador de {@code public} utilizado por la operación.
     * @return Resultado producido por {@code softwareAppId}.
     */
    public UUID softwareAppId(String publicId) {
        return catalog.softwareAppId(publicId);
    }

    /**
     * Elimina el recurso solicitado mediante {@code deleteApps}.
     *
     * @param appWhereClause Valor de {@code appWhereClause} utilizado por la operación.
     * @param appWhereParams Valor de {@code appWhereParams} utilizado por la operación.
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
     * Ejecuta la operación {@code selectIdsByForeignKey}.
     *
     * @param table Valor de {@code table} utilizado por la operación.
     * @param foreignKey Valor de {@code foreignKey} utilizado por la operación.
     * @param ownerIds Colección de identificadores de {@code owner}.
     * @return Colección de elementos obtenidos por la operación.
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
     * Elimina el recurso solicitado mediante {@code deleteByIds}.
     *
     * @param table Valor de {@code table} utilizado por la operación.
     * @param ids Valor de {@code ids} utilizado por la operación.
     */
    private void deleteByIds(String table, List<byte[]> ids) {
        deleteByForeignKey(table, "id", ids);
    }

    /**
     * Elimina el recurso solicitado mediante {@code deleteByForeignKey}.
     *
     * @param table Valor de {@code table} utilizado por la operación.
     * @param column Valor de {@code column} utilizado por la operación.
     * @param ids Valor de {@code ids} utilizado por la operación.
     */
    private void deleteByForeignKey(String table, String column, List<byte[]> ids) {
        forEachDeleteBatch(ids, batch -> jdbc.update(
                "DELETE FROM " + table + " WHERE " + column + " IN (" + placeholders(batch.size()) + ")",
                batch.toArray()));
    }

    /**
     * Ejecuta la operación {@code forEachDeleteBatch}.
     *
     * @param ids Valor de {@code ids} utilizado por la operación.
     * @param operation Valor de {@code operation} utilizado por la operación.
     */
    private void forEachDeleteBatch(List<byte[]> ids, java.util.function.Consumer<List<byte[]>> operation) {
        for (int start = 0; start < ids.size(); start += DELETE_BATCH_SIZE) {
            operation.accept(ids.subList(start, Math.min(start + DELETE_BATCH_SIZE, ids.size())));
        }
    }

    /**
     * Ejecuta la operación {@code placeholders}.
     *
     * @param count Valor de {@code count} utilizado por la operación.
     * @return Resultado producido por {@code placeholders}.
     */
    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    /**
     * Ejecuta la operación {@code assertScraperIdleForDeletion}.
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
     * Ejecuta la operación {@code scraperRunningConflict}.
     *
     * @return Resultado producido por {@code scraperRunningConflict}.
     */
    private ConflictException scraperRunningConflict() {
        return new ConflictException(
                "scraper_running",
                "No se pueden eliminar aplicaciones mientras el scraper está en ejecución.");
    }

    /**
     * Ejecuta la operación {@code refreshBundleCounts}.
     *
     * @param bundleIds Colección de identificadores de {@code bundle}.
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
     * Crea el recurso solicitado mediante {@code createDefaultSource}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param officialUrl Dirección de {@code official} que debe procesarse.
     * @param now Valor de {@code now} utilizado por la operación.
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
     * Ejecuta la operación {@code replaceTags}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param tags Valor de {@code tags} utilizado por la operación.
     * @param source Fuente de descarga sobre la que se actúa.
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
     * Normaliza el valor recibido mediante {@code normalizeSlug}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code normalizeSlug}.
     */
    private String normalizeSlug(String value) {
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "app-" + UUID.randomUUID() : slug;
    }

    /**
     * Normaliza el valor recibido mediante {@code normalizeText}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code normalizeText}.
     */
    private String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Ejecuta la operación {@code coalesce}.
     *
     * @param next Valor de {@code next} utilizado por la operación.
     * @param current Valor de {@code current} utilizado por la operación.
     * @return Resultado producido por {@code coalesce}.
     */
    private String coalesce(String next, String current) {
        return next == null ? current : next;
    }

    /**
     * Indica si se cumple la condición mediante {@code isBlank}.
     *
     * @param value Valor que debe procesarse.
     * @return Indica si se cumple la condición evaluada.
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Representa los datos inmutables de {@code AppCsvExport}.
     *
     * @param content Valor de {@code content} incluido en el record.
     * @param rowCount Valor de {@code rowCount} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record AppCsvExport(String content, int rowCount) {}
}
