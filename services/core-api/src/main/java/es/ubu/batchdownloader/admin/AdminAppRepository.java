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
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /**
     * Inicializa una instancia de {@code AdminAppRepository}.
     *
     * @param jdbc Valor de {@code jdbc} utilizado por la operación.
     * @param catalog Acceso al catálogo utilizado por la operación.
     */
    public AdminAppRepository(JdbcTemplate jdbc, CatalogRepository catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
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
        LocalDateTime now = LocalDateTime.now();
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
        UUID appId = softwareAppId(publicId);
        AbsenceAppState app = jdbc.query(
                        """
                        SELECT winstall_id, official_url, version, winstall_latest_version,
                               winstall_summary_fingerprint, winstall_detail_fingerprint
                        FROM software_apps
                        WHERE id = ?
                        FOR UPDATE
                        """,
                        rs -> rs.next()
                                ? new AbsenceAppState(
                                        rs.getString("winstall_id"),
                                        rs.getString("official_url"),
                                        rs.getLong("version"),
                                        rs.getString("winstall_latest_version"),
                                        rs.getString("winstall_summary_fingerprint"),
                                        rs.getString("winstall_detail_fingerprint"))
                                : null,
                        UuidBytes.fromUuid(appId));
        if (app == null) {
            throw new NotFoundException("app_not_found", "Aplicación no encontrada.");
        }
        if (!isBlank(app.officialUrl()) && isBlank(request.officialPageUrl())) {
            throw new ConflictException(
                    "official_site_verification_required",
                    "Debes comprobar también una página oficial accesible.");
        }
        Long candidates = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM resolved_sources rs
                JOIN download_sources ds ON ds.id = rs.download_source_id
                WHERE ds.software_app_id = ? AND rs.catalog_downloadable = 1
                """,
                Long.class,
                UuidBytes.fromUuid(appId));
        if (candidates != null && candidates > 0) {
            throw new ConflictException(
                    "validated_installer_exists",
                    "La aplicación ya tiene un instalador validado.");
        }

        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                """
                UPDATE installer_absence_verifications
                SET status = 'superseded', invalidated_at = ?,
                    invalidation_reason = 'reverified', updated_at = ?
                WHERE software_app_id = ? AND status = 'active'
                """,
                now,
                now,
                UuidBytes.fromUuid(appId));
        UUID verificationId = UUID.randomUUID();
        insertAbsenceVerification(verificationId, appId, app, request, actor, now);
        jdbc.update(
                """
                UPDATE download_sources
                SET resolution_status = 'missing', validation_status = 'unchecked',
                    updated_at = ?, version = version + 1
                WHERE software_app_id = ?
                """,
                now,
                UuidBytes.fromUuid(appId));
        return absenceVerification(verificationId);
    }

    /** Obtiene el acta activa más reciente de una aplicación. */
    public InstallerAbsenceVerification activeAbsenceVerification(String publicId) {
        UUID appId = softwareAppId(publicId);
        List<InstallerAbsenceVerification> rows = jdbc.query(
                """
                SELECT * FROM installer_absence_verifications
                WHERE software_app_id = ? AND status = 'active'
                ORDER BY verified_at DESC LIMIT 1
                """,
                this::mapAbsenceVerification,
                UuidBytes.fromUuid(appId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Resume los ``missing`` sin evidencia usando la proyección autoritativa. */
    public InstallerAbsenceVerificationSummary absenceVerificationSummary() {
        return jdbc.queryForObject(
                """
                SELECT
                    (SELECT COUNT(*) FROM installer_absence_verifications
                     WHERE status = 'active') active,
                    SUM(a.catalog_status = 'missing') missing_count,
                    SUM(a.catalog_status = 'review') review_count,
                    SUM(a.catalog_status = 'missing' AND NOT EXISTS (
                        SELECT 1 FROM installer_absence_verifications v
                        WHERE v.software_app_id = a.id AND v.status = 'active'
                    )) missing_without_evidence
                FROM software_apps a
                WHERE a.app_status = 'active'
                """,
                (rs, rowNum) -> new InstallerAbsenceVerificationSummary(
                        rs.getLong("active"),
                        rs.getLong("missing_count"),
                        rs.getLong("missing_without_evidence"),
                        rs.getLong("review_count")));
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
                LocalDateTime.now(),
                UuidBytes.fromUuid(id));
        if (request.officialUrl() != null
                && !request.officialUrl().equals(before.officialUrl())) {
            invalidateAbsenceEvidence(id, "official_url_changed");
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
        jdbc.update("UPDATE bundles SET app_count = 0, updated_at = ? WHERE app_count <> 0", LocalDateTime.now());
        return count == null ? 0 : count;
    }

    /**
     * Ejecuta la operación {@code exportCsv}.
     *
     * @return Resultado producido por {@code exportCsv}.
     */
    public AppCsvExport exportCsv() {
        List<ExportCandidate> candidates = jdbc.query(
                """
                SELECT HEX(a.id) AS app_key, a.name, a.winstall_id, a.official_url,
                       ds.operating_system, rs.extension, BIN_TO_UUID(rs.id) AS source_ref
                FROM software_apps a
                LEFT JOIN download_sources ds ON ds.software_app_id = a.id
                    AND ds.catalog_available = 1
                LEFT JOIN resolved_sources rs ON rs.download_source_id = ds.id
                    AND rs.catalog_downloadable = 1
                WHERE a.app_status = 'active'
                ORDER BY a.normalized_name ASC,
                         a.id ASC,
                         rs.is_latest DESC,
                         COALESCE(rs.release_rank, 9999) ASC,
                         (JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.is_primary')) = 'true') DESC,
                         rs.score DESC,
                         rs.checked_at DESC
                """,
                this::mapExportCandidate);
        Map<String, ExportRow> rows = new LinkedHashMap<>();
        for (ExportCandidate candidate : candidates) {
            ExportRow row = rows.computeIfAbsent(candidate.appKey(), key -> new ExportRow(
                    candidate.name(),
                    winstallUrl(candidate.winstallId()),
                    blankToNone(candidate.officialUrl())));
            String platform = platformKey(candidate.operatingSystem(), candidate.extension());
            if (platform != null && candidate.sourceRef() != null && !candidate.sourceRef().isBlank()) {
                row.putIfMissing(platform, candidate.sourceRef());
            }
        }

        StringBuilder csv = new StringBuilder(
                "Nombre,Winstall,URL,WindowsSourceRef,LinuxSourceRef,MacOSSourceRef\r\n");
        for (ExportRow row : rows.values()) {
            csv.append(csvCell(row.name()))
                    .append(',')
                    .append(csvCell(row.winstall()))
                    .append(',')
                    .append(csvCell(row.officialUrl()))
                    .append(',')
                    .append(csvCell(row.windows()))
                    .append(',')
                    .append(csvCell(row.linux()))
                    .append(',')
                    .append(csvCell(row.macos()))
                    .append("\r\n");
        }
        return new AppCsvExport(csv.toString(), rows.size());
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
        UUID applicationId = softwareAppId(appId);
        UUID id = parseUuid(sourceId);
        int updated = jdbc.update(
                """
                UPDATE download_sources
                SET operating_system = COALESCE(?, operating_system),
                    architecture = COALESCE(?, architecture),
                    initial_url = COALESCE(?, initial_url),
                    resolver_type = COALESCE(?, resolver_type),
                    resolution_status = COALESCE(?, resolution_status),
                    validation_status = COALESCE(?, validation_status),
                    updated_at = ?,
                    version = version + 1
                WHERE id = ? AND software_app_id = ?
                """,
                blankToNull(request.operatingSystem()),
                blankToNull(request.architecture()),
                blankToNull(request.initialUrl()),
                blankToNull(request.resolverType()),
                blankToNull(request.resolutionStatus()),
                blankToNull(request.validationStatus()),
                LocalDateTime.now(),
                UuidBytes.fromUuid(id),
                UuidBytes.fromUuid(applicationId));
        if (updated == 0) {
            throw new NotFoundException("source_not_found", "La fuente no existe.");
        }
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
     * Transforma el valor recibido mediante {@code mapExportCandidate}.
     *
     * @param rs Valor de {@code rs} utilizado por la operación.
     * @param rowNum Valor de {@code rowNum} utilizado por la operación.
     * @return Resultado producido por {@code mapExportCandidate}.
     * @throws SQLException Si no puede completarse la operación bajo las condiciones requeridas.
     */
    private ExportCandidate mapExportCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new ExportCandidate(
                rs.getString("app_key"),
                rs.getString("name"),
                rs.getString("winstall_id"),
                rs.getString("official_url"),
                rs.getString("operating_system"),
                rs.getString("extension"),
                rs.getString("source_ref"));
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
                    LocalDateTime.now(),
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
        LocalDateTime now = LocalDateTime.now();
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

    /** Inserta el acta con dos o tres páginas revisadas, nunca con URLs de binarios. */
    private void insertAbsenceVerification(
            UUID verificationId,
            UUID appId,
            AbsenceAppState app,
            InstallerAbsenceVerificationRequest request,
            String actor,
            LocalDateTime now) {
        String winstallUrl = "https://winstall.app/apps/" + app.winstallId();
        boolean hasOfficialPage = !isBlank(request.officialPageUrl());
        String checkedUrls = hasOfficialPage
                ? "JSON_ARRAY(?, ?, ?)"
                : "JSON_ARRAY(?, ?)";
        String sql = """
                INSERT INTO installer_absence_verifications
                (id, software_app_id, status, reason_code, notes, checked_urls_json,
                evidence_json, verified_by, verified_at, app_version,
                 winstall_latest_version, winstall_summary_fingerprint,
                 winstall_detail_fingerprint, official_url_fingerprint,
                 invalidated_at, invalidation_reason, created_at, updated_at)
                VALUES (?, ?, 'active', ?, ?, %s,
                        JSON_OBJECT('winstall', TRUE, 'manifest', TRUE, 'official', ?,
                                    'ambiguousAccess', FALSE),
                        ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?)
                """.formatted(checkedUrls);
        List<Object> parameters = new java.util.ArrayList<>();
        parameters.add(UuidBytes.fromUuid(verificationId));
        parameters.add(UuidBytes.fromUuid(appId));
        parameters.add(request.reasonCode());
        parameters.add(request.notes());
        parameters.add(winstallUrl);
        parameters.add(request.manifestUrl());
        if (hasOfficialPage) {
            parameters.add(request.officialPageUrl());
        }
        parameters.add(hasOfficialPage && request.officialConfirmedAbsent());
        parameters.add(actor);
        parameters.add(now);
        parameters.add(app.version());
        parameters.add(app.winstallLatestVersion());
        parameters.add(app.summaryFingerprint());
        parameters.add(app.detailFingerprint());
        parameters.add(fingerprint(app.officialUrl()));
        parameters.add(now);
        parameters.add(now);
        jdbc.update(sql, parameters.toArray());
    }

    /** Carga una verificación por su clave estable. */
    private InstallerAbsenceVerification absenceVerification(UUID verificationId) {
        return jdbc.queryForObject(
                "SELECT * FROM installer_absence_verifications WHERE id = ?",
                this::mapAbsenceVerification,
                UuidBytes.fromUuid(verificationId));
    }

    /** Convierte una fila de evidencia sin descifrar ni exponer instaladores. */
    private InstallerAbsenceVerification mapAbsenceVerification(ResultSet rs, int rowNum)
            throws SQLException {
        return new InstallerAbsenceVerification(
                UuidBytes.toUuid(rs.getBytes("id")).toString(),
                UuidBytes.toUuid(rs.getBytes("software_app_id")).toString(),
                rs.getString("status"),
                rs.getString("reason_code"),
                rs.getString("notes"),
                rs.getString("checked_urls_json"),
                rs.getString("verified_by"),
                rs.getTimestamp("verified_at").toLocalDateTime(),
                rs.getLong("app_version"),
                rs.getString("winstall_latest_version"),
                rs.getString("winstall_summary_fingerprint"),
                rs.getString("winstall_detail_fingerprint"),
                rs.getString("official_url_fingerprint"),
                nullableDate(rs, "invalidated_at"),
                rs.getString("invalidation_reason"));
    }

    /** Invalida la evidencia al cambiar la página oficial y devuelve el caso a revisión. */
    private void invalidateAbsenceEvidence(UUID appId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        int invalidated = jdbc.update(
                """
                UPDATE installer_absence_verifications
                SET status = 'invalidated', invalidated_at = ?, invalidation_reason = ?,
                    updated_at = ?
                WHERE software_app_id = ? AND status = 'active'
                """,
                now,
                reason,
                now,
                UuidBytes.fromUuid(appId));
        if (invalidated == 0) {
            return;
        }
        Long available = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM download_sources
                WHERE software_app_id = ? AND catalog_available = 1
                """,
                Long.class,
                UuidBytes.fromUuid(appId));
        if (available == null || available == 0) {
            jdbc.update(
                    """
                    UPDATE download_sources
                    SET resolution_status = 'requires_manual_review',
                        validation_status = 'unchecked', updated_at = ?, version = version + 1
                    WHERE software_app_id = ?
                    """,
                    now,
                    UuidBytes.fromUuid(appId));
        }
    }

    /** Calcula una huella de página sin almacenar cabeceras, credenciales ni instaladores. */
    private String fingerprint(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
    }

    /** Lee una fecha SQL opcional. */
    private LocalDateTime nullableDate(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /**
     * Analiza el contenido recibido mediante {@code parseUuid}.
     *
     * @param raw Valor de {@code raw} utilizado por la operación.
     * @return Resultado producido por {@code parseUuid}.
     * @throws NotFoundException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw new NotFoundException("source_not_found", "La fuente no existe.");
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
     * Ejecuta la operación {@code blankToNull}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code blankToNull}.
     */
    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
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
     * Ejecuta la operación {@code winstallUrl}.
     *
     * @param winstallId Identificador de {@code winstall} utilizado por la operación.
     * @return Resultado producido por {@code winstallUrl}.
     */
    private String winstallUrl(String winstallId) {
        if (isBlank(winstallId) || winstallId.startsWith("manual.")) {
            return "None";
        }
        return "https://winstall.app/apps/" + winstallId.trim();
    }

    /**
     * Ejecuta la operación {@code blankToNone}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code blankToNone}.
     */
    private String blankToNone(String value) {
        return isBlank(value) ? "None" : value.trim();
    }

    /**
     * Ejecuta la operación {@code platformKey}.
     *
     * @param operatingSystem Valor de {@code operatingSystem} utilizado por la operación.
     * @param extension Valor de {@code extension} utilizado por la operación.
     * @return Resultado producido por {@code platformKey}.
     */
    private String platformKey(String operatingSystem, String extension) {
        String os = operatingSystem == null ? "" : operatingSystem.toLowerCase(Locale.ROOT).trim();
        if (os.contains("windows") || os.equals("win")) {
            return "windows";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        if (os.contains("mac") || os.contains("darwin") || os.contains("osx")) {
            return "macos";
        }

        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT).replace(".", "").trim();
        return switch (ext) {
            case "exe", "msi", "msix", "appx" -> "windows";
            case "deb", "rpm", "appimage", "flatpak" -> "linux";
            case "dmg", "pkg" -> "macos";
            default -> null;
        };
    }

    /**
     * Ejecuta la operación {@code csvCell}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code csvCell}.
     */
    private String csvCell(String value) {
        String safe = isBlank(value) ? "None" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    /**
     * Representa los datos inmutables de {@code AppCsvExport}.
     *
     * @param content Valor de {@code content} incluido en el record.
     * @param rowCount Valor de {@code rowCount} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record AppCsvExport(String content, int rowCount) {}

    /** Campos locales que sellan una verificación negativa frente a cambios posteriores. */
    private record AbsenceAppState(
            String winstallId,
            String officialUrl,
            long version,
            String winstallLatestVersion,
            String summaryFingerprint,
            String detailFingerprint) {}

    /**
     * Representa los datos inmutables de {@code ExportCandidate}.
     *
     * @param appKey Valor de {@code appKey} incluido en el record.
     * @param name Valor de {@code name} incluido en el record.
     * @param winstallId Valor de {@code winstallId} incluido en el record.
     * @param officialUrl Valor de {@code officialUrl} incluido en el record.
     * @param operatingSystem Valor de {@code operatingSystem} incluido en el record.
     * @param extension Valor de {@code extension} incluido en el record.
     * @param sourceRef Valor de {@code sourceRef} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    private record ExportCandidate(
            String appKey,
            String name,
            String winstallId,
            String officialUrl,
            String operatingSystem,
            String extension,
            String sourceRef) {}

    /**
     * Implementa el componente {@code ExportRow}.
     *
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    private static final class ExportRow {
        /**
         * Estado {@code name} mantenido por {@code ExportRow}.
         */
        private final String name;
        /**
         * Estado {@code winstall} mantenido por {@code ExportRow}.
         */
        private final String winstall;
        /**
         * Estado {@code officialUrl} mantenido por {@code ExportRow}.
         */
        private final String officialUrl;
        /**
         * Estado {@code windows} mantenido por {@code ExportRow}.
         */
        private String windows = "None";
        /**
         * Estado {@code linux} mantenido por {@code ExportRow}.
         */
        private String linux = "None";
        /**
         * Estado {@code macos} mantenido por {@code ExportRow}.
         */
        private String macos = "None";

        /**
         * Inicializa una instancia de {@code ExportRow}.
         *
         * @param name Nombre del elemento sobre el que se actúa.
         * @param winstall Valor de {@code winstall} utilizado por la operación.
         * @param officialUrl Dirección de {@code official} que debe procesarse.
         */
        private ExportRow(String name, String winstall, String officialUrl) {
            this.name = name;
            this.winstall = winstall;
            this.officialUrl = officialUrl;
        }

        /**
         * Ejecuta la operación {@code putIfMissing}.
         *
         * @param platform Valor de {@code platform} utilizado por la operación.
         * @param url URL del recurso que debe procesarse.
         */
        private void putIfMissing(String platform, String url) {
            if ("windows".equals(platform) && "None".equals(windows)) {
                windows = url;
            } else if ("linux".equals(platform) && "None".equals(linux)) {
                linux = url;
            } else if ("macos".equals(platform) && "None".equals(macos)) {
                macos = url;
            }
        }

        /**
         * Ejecuta la operación {@code name}.
         *
         * @return Resultado producido por {@code name}.
         */
        private String name() {
            return name;
        }

        /**
         * Ejecuta la operación {@code winstall}.
         *
         * @return Resultado producido por {@code winstall}.
         */
        private String winstall() {
            return winstall;
        }

        /**
         * Ejecuta la operación {@code officialUrl}.
         *
         * @return Resultado producido por {@code officialUrl}.
         */
        private String officialUrl() {
            return officialUrl;
        }

        /**
         * Ejecuta la operación {@code windows}.
         *
         * @return Resultado producido por {@code windows}.
         */
        private String windows() {
            return windows;
        }

        /**
         * Ejecuta la operación {@code linux}.
         *
         * @return Resultado producido por {@code linux}.
         */
        private String linux() {
            return linux;
        }

        /**
         * Ejecuta la operación {@code macos}.
         *
         * @return Resultado producido por {@code macos}.
         */
        private String macos() {
            return macos;
        }
    }
}
