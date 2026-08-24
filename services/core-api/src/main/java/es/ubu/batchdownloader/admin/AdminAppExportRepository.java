package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminAppRepository.AppCsvExport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Construye exportaciones administrativas sin mezclar su proyección con el CRUD del catálogo.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Repository
public class AdminAppExportRepository {
    private final JdbcTemplate jdbc;

    /**
     * Inicializa la proyección de exportación.
     *
     * @param jdbc acceso JDBC al catálogo
     */
    public AdminAppExportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Genera el CSV canónico con una referencia de instalador por plataforma.
     *
     * @return contenido CSV y número de aplicaciones exportadas
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
                this::mapCandidate);
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

    private ExportCandidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new ExportCandidate(
                rs.getString("app_key"),
                rs.getString("name"),
                rs.getString("winstall_id"),
                rs.getString("official_url"),
                rs.getString("operating_system"),
                rs.getString("extension"),
                rs.getString("source_ref"));
    }

    private String winstallUrl(String winstallId) {
        if (isBlank(winstallId) || winstallId.startsWith("manual.")) {
            return "None";
        }
        return "https://winstall.app/apps/" + winstallId.trim();
    }

    private String blankToNone(String value) {
        return isBlank(value) ? "None" : value.trim();
    }

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

    private String csvCell(String value) {
        String safe = isBlank(value) ? "None" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ExportCandidate(
            String appKey,
            String name,
            String winstallId,
            String officialUrl,
            String operatingSystem,
            String extension,
            String sourceRef) {}

    private static final class ExportRow {
        private final String name;
        private final String winstall;
        private final String officialUrl;
        private String windows = "None";
        private String linux = "None";
        private String macos = "None";

        private ExportRow(String name, String winstall, String officialUrl) {
            this.name = name;
            this.winstall = winstall;
            this.officialUrl = officialUrl;
        }

        private void putIfMissing(String platform, String sourceRef) {
            if ("windows".equals(platform) && "None".equals(windows)) {
                windows = sourceRef;
            } else if ("linux".equals(platform) && "None".equals(linux)) {
                linux = sourceRef;
            } else if ("macos".equals(platform) && "None".equals(macos)) {
                macos = sourceRef;
            }
        }

        private String name() {
            return name;
        }

        private String winstall() {
            return winstall;
        }

        private String officialUrl() {
            return officialUrl;
        }

        private String windows() {
            return windows;
        }

        private String linux() {
            return linux;
        }

        private String macos() {
            return macos;
        }
    }
}
