package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminDtos.InstallerAbsenceVerification;
import es.ubu.batchdownloader.admin.AdminDtos.InstallerAbsenceVerificationRequest;
import es.ubu.batchdownloader.admin.AdminDtos.InstallerAbsenceVerificationSummary;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conserva y valida la evidencia explícita de aplicaciones sin instalador.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Repository
public class InstallerAbsenceRepository {
    private final JdbcTemplate jdbc;
    private final CatalogRepository catalog;
    private final Clock clock;

    /**
     * Inicializa el repositorio de evidencias negativas.
     *
     * @param jdbc acceso JDBC al catálogo
     * @param catalog resolución canónica de identificadores públicos
     * @param clock reloj inyectado para operaciones deterministas
     */
    public InstallerAbsenceRepository(JdbcTemplate jdbc, CatalogRepository catalog, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.clock = clock;
    }

    /**
     * Registra una ausencia únicamente cuando las comprobaciones son concluyentes.
     *
     * @param publicId aplicación revisada
     * @param request evidencia estructurada
     * @param actor administrador responsable
     * @return acta activa recién creada
     */
    @Transactional
    public InstallerAbsenceVerification confirm(
            String publicId,
            InstallerAbsenceVerificationRequest request,
            String actor) {
        UUID appId = catalog.softwareAppId(publicId);
        AbsenceAppState app = lockApp(appId);
        if (app == null) {
            throw new NotFoundException("app_not_found", "Aplicación no encontrada.");
        }
        if (!isBlank(app.officialUrl()) && isBlank(request.officialPageUrl())) {
            throw new ConflictException(
                    "official_site_verification_required",
                    "Debes comprobar también una página oficial accesible.");
        }
        rejectValidatedInstaller(appId);

        LocalDateTime now = LocalDateTime.now(clock);
        supersedeActive(appId, now);
        UUID verificationId = UUID.randomUUID();
        insert(verificationId, appId, app, request, actor, now);
        jdbc.update(
                """
                UPDATE download_sources
                SET resolution_status = 'missing', validation_status = 'unchecked',
                    updated_at = ?, version = version + 1
                WHERE software_app_id = ?
                """,
                now,
                UuidBytes.fromUuid(appId));
        return byId(verificationId);
    }

    /** Obtiene el acta activa más reciente de una aplicación. */
    public InstallerAbsenceVerification active(String publicId) {
        UUID appId = catalog.softwareAppId(publicId);
        List<InstallerAbsenceVerification> rows = jdbc.query(
                """
                SELECT * FROM installer_absence_verifications
                WHERE software_app_id = ? AND status = 'active'
                ORDER BY verified_at DESC LIMIT 1
                """,
                this::map,
                UuidBytes.fromUuid(appId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Resume los {@code missing} sin evidencia usando la proyección autoritativa. */
    public InstallerAbsenceVerificationSummary summary() {
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

    /** Invalida evidencia activa cuando cambia una fuente autoritativa. */
    void invalidate(UUID appId, String reason) {
        LocalDateTime now = LocalDateTime.now(clock);
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

    private AbsenceAppState lockApp(UUID appId) {
        return jdbc.query(
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
    }

    private void rejectValidatedInstaller(UUID appId) {
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
    }

    private void supersedeActive(UUID appId, LocalDateTime now) {
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
    }

    private void insert(
            UUID verificationId,
            UUID appId,
            AbsenceAppState app,
            InstallerAbsenceVerificationRequest request,
            String actor,
            LocalDateTime now) {
        boolean hasOfficialPage = !isBlank(request.officialPageUrl());
        String checkedUrls = hasOfficialPage ? "JSON_ARRAY(?, ?, ?)" : "JSON_ARRAY(?, ?)";
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
        List<Object> parameters = new ArrayList<>();
        parameters.add(UuidBytes.fromUuid(verificationId));
        parameters.add(UuidBytes.fromUuid(appId));
        parameters.add(request.reasonCode());
        parameters.add(request.notes());
        parameters.add("https://winstall.app/apps/" + app.winstallId());
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

    private InstallerAbsenceVerification byId(UUID verificationId) {
        return jdbc.queryForObject(
                "SELECT * FROM installer_absence_verifications WHERE id = ?",
                this::map,
                UuidBytes.fromUuid(verificationId));
    }

    private InstallerAbsenceVerification map(ResultSet rs, int rowNum) throws SQLException {
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

    private LocalDateTime nullableDate(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record AbsenceAppState(
            String winstallId,
            String officialUrl,
            long version,
            String winstallLatestVersion,
            String summaryFingerprint,
            String detailFingerprint) {}
}
