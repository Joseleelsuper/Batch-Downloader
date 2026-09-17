package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.InstallerAbsenceDtos.InstallerAbsenceVerification;
import es.ubu.batchdownloader.admin.InstallerAbsenceDtos.InstallerAbsenceVerificationRequest;
import es.ubu.batchdownloader.admin.InstallerAbsenceDtos.InstallerAbsenceVerificationSummary;
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
 * Conserva comprobaciones humanas de ausencia de instaladores vinculadas a la versión y las huellas
 * del catálogo. Sustituye o invalida evidencia en la transacción que modifica la aplicación.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.AdminAppRepository
 * @see InstallerAbsenceDtos.InstallerAbsenceVerificationRequest
 * @since 0.1.0
 * @version 0.1.0
 * @category Operaciones administrativas
 */
@Repository
public class InstallerAbsenceRepository {
    private final JdbcTemplate jdbc;
    private final CatalogRepository catalog;
    private final Clock clock;

    /**
     * Conecta las evidencias con el acceso SQL, la resolución de aplicaciones y el reloj de
     * cambios.
     *
     * @param jdbc Acceso JDBC a las tablas operativas y evidencias del catálogo.
     * @param catalog Resolución de aplicaciones públicas y sus claves persistidas.
     * @param clock Reloj que fecha evidencias y determina el corte de retención.
     */
    public InstallerAbsenceRepository(JdbcTemplate jdbc, CatalogRepository catalog, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.clock = clock;
    }

    /**
     * Bloquea la aplicación, exige comprobar su página oficial cuando existe y rechaza una ausencia
     * si ya hay instalador descargable. Sustituye la evidencia activa y marca las fuentes missing
     * en la misma transacción.
     *
     * @param publicId UUID público textual de la aplicación cuya evidencia se consulta o confirma.
     * @param request Evidencias de las páginas comprobadas, ya validadas por la entrada HTTP.
     * @param actor UUID textual de la cuenta administrativa que solicitó la operación.
     * @return evidencia recién creada con las huellas comprobadas.
     * @throws es.ubu.batchdownloader.common.NotFoundException si la aplicación desapareció o no
     *     existe.
     * @throws es.ubu.batchdownloader.common.ConflictException si falta comprobación oficial o
     *     existe un instalador validado.
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

    /**
     * Consulta la evidencia activa más reciente para la aplicación resuelta.
     *
     * @param publicId UUID público textual de la aplicación cuya evidencia se consulta o confirma.
     * @return evidencia activa o null cuando no hay ninguna.
     */
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

    /**
     * Cuenta evidencias activas y aplicaciones publicadas en missing o review, incluyendo las
     * missing sin evidencia activa.
     *
     * @return recuentos administrativos para priorizar comprobaciones.
     */
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

    /**
     * Invalida la evidencia activa con motivo y fecha. Solo si retiró evidencia y no queda ninguna
     * fuente disponible, devuelve las fuentes a revisión manual.
     *
     * @param appId UUID persistido de la aplicación propietaria de la evidencia.
     * @param reason Código que identifica el cambio que invalida la comprobación anterior.
     */
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

    /**
     * Lee versión, página oficial y huellas Winstall bajo FOR UPDATE para que la evidencia
     * corresponda a un estado estable.
     *
     * @param appId UUID persistido de la aplicación propietaria de la evidencia.
     * @return estado bloqueado o null si ya no existe la aplicación.
     */
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

    /**
     * Comprueba que ninguna fuente resuelta de la aplicación sea descargable antes de certificar
     * una ausencia.
     *
     * @param appId UUID persistido de la aplicación propietaria de la evidencia.
     * @throws es.ubu.batchdownloader.common.ConflictException si encuentra al menos un instalador
     *     catalog_downloadable.
     */
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

    /**
     * Conserva las evidencias anteriores como superseded con motivo reverified antes de insertar la
     * nueva comprobación.
     *
     * @param appId UUID persistido de la aplicación propietaria de la evidencia.
     * @param now Fecha compartida por sustitución e inserción dentro de la misma transacción.
     */
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

    /**
     * Guarda las páginas comprobadas, confirmaciones, actor, versión y huellas como evidencia
     * activa; incluye la página oficial solo cuando se aportó.
     *
     * @param verificationId UUID único de la evidencia creada o consultada.
     * @param appId UUID persistido de la aplicación propietaria de la evidencia.
     * @param app Estado de la aplicación obtenido bajo bloqueo para registrar la versión y las
     *     huellas comprobadas.
     * @param request Evidencias de las páginas comprobadas, ya validadas por la entrada HTTP.
     * @param actor UUID textual de la cuenta administrativa que solicitó la operación.
     * @param now Fecha compartida por sustitución e inserción dentro de la misma transacción.
     */
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

    /**
     * Lee una evidencia que acaba de persistirse para devolver su representación completa.
     *
     * @param verificationId UUID único de la evidencia creada o consultada.
     * @return evidencia asociada al UUID indicado.
     */
    private InstallerAbsenceVerification byId(UUID verificationId) {
        return jdbc.queryForObject(
                "SELECT * FROM installer_absence_verifications WHERE id = ?",
                this::map,
                UuidBytes.fromUuid(verificationId));
    }

    /**
     * Convierte la evidencia SQL en su contrato administrativo, conservando la fecha y motivo de
     * invalidación opcionales.
     *
     * @param rs Fila actual de la consulta JDBC.
     * @param rowNum Índice de fila JDBC; no influye en la proyección.
     * @return comprobación con identificadores textuales y huellas persistidas.
     * @throws java.sql.SQLException si falla la lectura de una columna de la evidencia.
     */
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

    /**
     * Calcula SHA-256 del texto recortado en UTF-8 para detectar cambios de la página oficial sin
     * normalizar su URL.
     *
     * @param value Texto opcional cuya ausencia o huella se calcula.
     * @return huella hexadecimal o null cuando falta texto.
     * @throws IllegalStateException si el proveedor criptográfico no dispone de SHA-256.
     */
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

    /**
     * Lee una fecha JDBC opcional conservando la ausencia del dato.
     *
     * @param rs Fila actual de la consulta JDBC.
     * @param column Nombre interno de la columna nullable que se desea leer.
     * @return fecha local persistida o null.
     * @throws java.sql.SQLException si no se puede leer la columna de fecha.
     */
    private LocalDateTime nullableDate(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /**
     * Detecta si una página opcional carece de texto antes de exigirla o calcular su huella.
     *
     * @param value Texto opcional cuya ausencia o huella se calcula.
     * @return true para null o texto formado solo por espacios.
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Captura el estado bloqueado de la aplicación que queda vinculado a una confirmación de
     * ausencia.
     *
     * @param winstallId Identificador del paquete que se comprobó en Winstall.
     * @param officialUrl Página oficial conocida al confirmar la evidencia.
     * @param version Versión persistida de la aplicación en el momento de la comprobación.
     * @param winstallLatestVersion Última versión Winstall que se comprobó al generar la evidencia.
     * @param summaryFingerprint Huella del resumen Winstall que acompañó a la versión comprobada.
     * @param detailFingerprint Huella del detalle Winstall que acompañó a la versión comprobada.
     * @since 0.1.0
     * @version 0.1.0
     * @category Operaciones administrativas
     */
    private record AbsenceAppState(
            String winstallId,
            String officialUrl,
            long version,
            String winstallLatestVersion,
            String summaryFingerprint,
            String detailFingerprint) {}
}
